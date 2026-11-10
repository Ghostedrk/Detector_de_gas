package com.example.detectordegas

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Reading(val value: Int?, val state: String, val connected: Boolean,
                   val time: Long = System.currentTimeMillis(), val source: String = "ESP32") {
    fun json() = JSONObject().put("lectura", value ?: JSONObject.NULL).put("estado", state)
        .put("conexion_arduino", connected).put("unidad", "ADC").put("time", time).put("source", source)
    companion object {
        fun parse(j: JSONObject, source: String = "ESP32"): Reading {
            require(j.optString("unidad") == "ADC") { "La unidad recibida no es ADC." }
            val state = j.getString("estado")
            require(state in setOf("calentando", "sin_alerta", "alerta", "prueba", "sin_datos")) {
                "Estado del sensor desconocido."
            }
            require(j.get("conexion_arduino") is Boolean) { "Indicador de conexión inválido." }
            val connected = j.getBoolean("conexion_arduino")
            val value = if (j.isNull("lectura")) null else {
                val raw = j.get("lectura")
                require(raw is Number && raw.toDouble() in 0.0..1023.0 &&
                    raw.toDouble() == raw.toInt().toDouble()) { "Lectura ADC inválida." }
                raw.toInt()
            }
            require((connected && value != null && value in 0..1023 && state != "sin_datos") ||
                (!connected && value == null && state == "sin_datos")) { "Lectura inconsistente del sensor." }
            return Reading(value, state, connected, j.optLong("time", System.currentTimeMillis()), source)
        }
    }
}

fun stateLabel(state: String) = when (state) {
    "calentando" -> "Espera inicial"
    "sin_alerta" -> "Sin alerta según el umbral"
    "alerta" -> "Alerta del sensor"
    "prueba" -> "Prueba manual"
    else -> "Sin datos recientes"
}

/** Solo admite IPv4 privada literal: no se resuelven nombres ni se siguen redirecciones. */
fun privateIp(input: String): String {
    val host = input.trim()
    val parts = host.split('.')
    require(parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.all { it in '0'..'9' } &&
        p.toIntOrNull() in 0..255 && (p == "0" || !p.startsWith('0')) }) {
        "Escribe solo la IP local del ESP32, por ejemplo 192.168.1.50."
    }
    val n = parts.map { it.toInt() }
    require(n[0] == 10 || (n[0] == 192 && n[1] == 168) || (n[0] == 172 && n[1] in 16..31)) {
        "La IP debe pertenecer a una red privada."
    }
    return host
}

fun espUrl(input: String) = "http://${privateIp(input)}/estado"

/**
 * Mensajes firmados con HMAC-SHA256 entre la app y el ESP32 (clave compartida de 32 bytes).
 * El mismo formato está implementado en firmware/ESP32/ESP32.ino.
 */
object EspAuth {
    fun request(key: ByteArray, method: String, path: String, nonce: String) =
        LocalCrypto.hmacHex(key, "$method $path\n$nonce")
    fun response(key: ByteArray, nonce: String, body: String) =
        LocalCrypto.hmacHex(key, "RESPUESTA\n$nonce\n$body")
    fun verifyResponse(key: ByteArray, nonce: String, body: String, signature: String?) =
        signature != null && java.security.MessageDigest.isEqual(
            response(key, nonce, body).toByteArray(Charsets.US_ASCII), signature.toByteArray(Charsets.US_ASCII))
}

/** Cliente HTTP del ESP32: solo IP privada literal, sin redirecciones y con solicitudes y respuestas autenticadas. */
object EspClient {
    private val nonceFormat = Regex("[0-9a-f]{32}")

    /** Lectura del sensor. El nonce aleatorio impide reutilizar respuestas antiguas. */
    suspend fun status(ip: String, key: ByteArray): JSONObject {
        val nonce = LocalCrypto.hex(LocalCrypto.randomBytes(16))
        return JSONObject(signedCall(ip, key, "GET", "/estado", nonce))
    }

    /** Orden de control: el ESP32 entrega un desafío de un solo uso y luego envía "T" al Arduino. */
    suspend fun test(ip: String, key: ByteArray): JSONObject {
        val challenge = JSONObject(call(ip, "GET", "/desafio", emptyMap()).second).getString("desafio")
        require(challenge.matches(nonceFormat)) { "Desafío del ESP32 inválido." }
        val result = JSONObject(signedCall(ip, key, "POST", "/prueba", challenge))
        require(result.optString("resultado") == "prueba_enviada") { "El ESP32 no confirmó la prueba." }
        return result
    }

    private suspend fun signedCall(ip: String, key: ByteArray, method: String, path: String, nonce: String): String {
        val (signature, body) = call(ip, method, path,
            mapOf("X-Nonce" to nonce, "X-Firma" to EspAuth.request(key, method, path, nonce)))
        require(EspAuth.verifyResponse(key, nonce, body, signature)) {
            "La respuesta del ESP32 no está firmada con la clave configurada."
        }
        return body
    }

    private suspend fun call(ip: String, method: String, path: String, headers: Map<String, String>): Pair<String?, String> =
        withContext(Dispatchers.IO) {
            require(path in setOf("/estado", "/desafio", "/prueba"))
            val c = URL("http://${privateIp(ip)}$path").openConnection() as HttpURLConnection
            c.connectTimeout = 3000; c.readTimeout = 3000
            c.instanceFollowRedirects = false; c.useCaches = false
            c.requestMethod = method
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            try {
                if (method == "POST") { c.doOutput = true; c.setFixedLengthStreamingMode(0); c.outputStream.close() }
                when (c.responseCode) {
                    200 -> Unit
                    401 -> error("El ESP32 rechazó la firma: revisa la clave del dispositivo.")
                    503 -> error("El ESP32 no tiene configurada su clave de dispositivo.")
                    else -> error("El ESP32 rechazó la consulta (código ${c.responseCode}).")
                }
                val body = c.inputStream.use { it.readBytesBounded(8192) }.toString(Charsets.UTF_8)
                c.getHeaderField("X-Firma") to body
            } finally { c.disconnect() }
        }
}

fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(1024)
    while (true) {
        val n = read(buffer)
        if (n == -1) break
        require(out.size() + n <= limit) { "Respuesta demasiado grande." }
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

class History(context: Context, name: String? = "detector_local.db") : SQLiteOpenHelper(context, name, null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE readings(id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT NOT NULL, time INTEGER NOT NULL, value INTEGER, state TEXT NOT NULL, connected INTEGER NOT NULL, source TEXT NOT NULL)")
        db.execSQL("CREATE INDEX owner_time ON readings(owner, time)")
        db.execSQL("CREATE TABLE accounts(id TEXT PRIMARY KEY, username TEXT UNIQUE NOT NULL, salt TEXT NOT NULL, hash TEXT NOT NULL, algorithm TEXT NOT NULL, failures INTEGER NOT NULL DEFAULT 0, locked_until INTEGER NOT NULL DEFAULT 0, role TEXT NOT NULL DEFAULT '$ROLE_OPERATOR')")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Versión 1 → 2: roles. Las cuentas existentes las creó el dueño del teléfono.
        if (oldVersion < 2) db.execSQL("ALTER TABLE accounts ADD COLUMN role TEXT NOT NULL DEFAULT '$ROLE_ADMIN'")
    }
    fun add(owner: String, r: Reading) {
        writableDatabase.insertOrThrow("readings", null, ContentValues().apply {
            put("owner", owner); put("time", r.time); put("value", r.value)
            put("state", r.state); put("connected", if (r.connected) 1 else 0); put("source", r.source)
        })
        writableDatabase.execSQL("DELETE FROM readings WHERE owner=? AND id NOT IN (SELECT id FROM readings WHERE owner=? ORDER BY id DESC LIMIT 500)", arrayOf(owner, owner))
    }
    fun list(owner: String): List<Reading> = readableDatabase.rawQuery(
        "SELECT value,state,connected,time,source FROM readings WHERE owner=? ORDER BY id DESC LIMIT 100", arrayOf(owner)
    ).use { c -> buildList {
        while (c.moveToNext()) add(Reading(if (c.isNull(0)) null else c.getInt(0), c.getString(1),
            c.getInt(2) == 1, c.getLong(3), c.getString(4)))
    } }
    fun register(username: String, password: String, algorithm: String): LocalUser {
        require(writableDatabase.rawQuery("SELECT id FROM accounts WHERE username=?", arrayOf(username)).use { !it.moveToFirst() }) { "Ese usuario ya existe en este teléfono." }
        val salt = LocalCrypto.randomBytes(16)
        val hash = PasswordHash.derive(password, salt, algorithm)
        val id = java.util.UUID.randomUUID().toString()
        val role = if (android.database.DatabaseUtils.queryNumEntries(writableDatabase, "accounts") == 0L) ROLE_ADMIN else ROLE_OPERATOR
        writableDatabase.insertOrThrow("accounts", null, ContentValues().apply {
            put("id", id); put("username", username); put("salt", LocalCrypto.hex(salt))
            put("hash", LocalCrypto.hex(hash)); put("algorithm", algorithm); put("role", role)
        })
        return LocalUser(id, username, role)
    }
    fun login(username: String, password: String): LocalUser {
        val row = readableDatabase.rawQuery("SELECT id,salt,hash,algorithm,failures,locked_until,role FROM accounts WHERE username=?", arrayOf(username)).use { c ->
            if (!c.moveToFirst()) null else arrayOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4).toString(), c.getLong(5).toString(), c.getString(6))
        } ?: error("Usuario o contraseña incorrectos.")
        require(System.currentTimeMillis() >= row[5].toLong()) { "Demasiados intentos. Espera 30 segundos." }
        val candidate = PasswordHash.derive(password, LocalCrypto.unhex(row[1]), row[3])
        if (!java.security.MessageDigest.isEqual(candidate, LocalCrypto.unhex(row[2]))) {
            val failures = row[4].toInt() + 1
            writableDatabase.update("accounts", ContentValues().apply {
                put("failures", if (failures >= 5) 0 else failures)
                put("locked_until", if (failures >= 5) System.currentTimeMillis() + 30000 else 0)
            }, "id=?", arrayOf(row[0]))
            error("Usuario o contraseña incorrectos.")
        }
        writableDatabase.update("accounts", ContentValues().apply { put("failures", 0); put("locked_until", 0) }, "id=?", arrayOf(row[0]))
        return LocalUser(row[0], username, row[6])
    }
    fun clear(owner: String) { writableDatabase.delete("readings", "owner=?", arrayOf(owner)) }
}
