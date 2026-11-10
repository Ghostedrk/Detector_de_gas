package com.example.detectordegas

import android.app.Application
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.UUID

private const val PAIRING_SLOT = "device:pairing"
private const val ESP_KEY_SLOT = "device:esp32"

class DetectorModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("local_detector_settings", 0)
    private val history = History(application)
    private val vault = PairingVault(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val hub = LocalHub()
    private var server: LocalPeerServer? = null
    val deviceId: String = prefs.getString("device", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("device", it).apply()
    }
    val deviceName = "${Build.MODEL.take(70)} · ${deviceId.take(4)}"
    var ip by mutableStateOf(prefs.getString("ip", "192.168.1.50") ?: "")
    var peerIp by mutableStateOf(prefs.getString("peer_ip", "192.168.1.20") ?: "")
    var username by mutableStateOf("")
    var pairingCode by mutableStateOf("")
    var localIps by mutableStateOf("")
    var busy by mutableStateOf(false)
    var error by mutableStateOf("")
    var peerStatus by mutableStateOf("Enlace local detenido")
    var sensorStatus by mutableStateOf("Monitoreo detenido")
    var user by mutableStateOf<LocalUser?>(null)
        private set
    var demo by mutableStateOf(false)
        private set
    var running by mutableStateOf(false)
        private set
    var publisher by mutableStateOf(prefs.getBoolean("publisher", true))
        private set
    var reading by mutableStateOf<Reading?>(null)
        private set
    var localHistory by mutableStateOf(emptyList<Reading>())
        private set
    var reviews by mutableStateOf(emptyList<Review>())
        private set
    /** true: el emisor usa el ESP32 simulado en lugar del circuito real. */
    var simulatedSensor by mutableStateOf(prefs.getBoolean("sim_sensor", false))
        private set
    /** Clave compartida con el ESP32 (64 hex), igual a CLAVE_DISPOSITIVO del firmware. */
    var deviceKey by mutableStateOf("")
    var lastCommand by mutableStateOf("")
        private set
    private val simulator = SimulatedDetector()
    private var lastSave = 0L
    private var lastState = ""
    private val owner get() = user?.uid ?: "demo"
    /** Control de acceso: solo el administrador configura el equipo y envía órdenes al detector. */
    val isAdmin get() = demo || user?.role == ROLE_ADMIN

    private fun requireAdmin(): Boolean {
        if (!isAdmin) error = "Solo el administrador puede realizar esta acción."
        return isAdmin
    }
    fun saveSettings() {
        if (!requireAdmin()) return
        persistSettings()
    }
    private fun persistSettings() {
        prefs.edit().putString("ip", ip.trim()).putString("peer_ip", peerIp.trim())
            .putBoolean("sim_sensor", simulatedSensor).putBoolean("publisher", publisher).apply()
    }
    fun chooseSimulated(value: Boolean) {
        if (!running && !demo && requireAdmin()) { simulatedSensor = value; persistSettings() }
    }
    fun generateDeviceKey() {
        if (!running && !demo && requireAdmin()) deviceKey = LocalCrypto.hex(LocalCrypto.randomBytes(32))
    }
    fun login(password: String, register: Boolean) {
        if (busy) return
        error = ""
        val name = username.trim().lowercase(java.util.Locale.ROOT)
        if (!name.matches(Regex("[a-z0-9_]{3,30}"))) {
            error = "Usa entre 3 y 30 letras, números o guion bajo para tu usuario."; return
        }
        if (password.length < 8 || password.length > 128) { error = "La contraseña debe tener entre 8 y 128 caracteres."; return }
        scope.launch {
            busy = true
            try {
                mutex.withLock {
                    val session = withContext(Dispatchers.IO) {
                        if (register) history.register(name, password, if (Build.VERSION.SDK_INT >= 26) "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA1")
                        else history.login(name, password)
                    }
                    // Código de enlace y clave del ESP32 son del dispositivo: los configura el administrador
                    // y los usan también los operadores. Se leen las ubicaciones antiguas por compatibilidad.
                    val code = withContext(Dispatchers.IO) {
                        vault.load(PAIRING_SLOT) ?: vault.load(session.uid)
                            ?: LocalCrypto.hex(LocalCrypto.randomBytes(32)).also { vault.save(PAIRING_SLOT, it) }
                    }
                    val espKey = withContext(Dispatchers.IO) { vault.load(ESP_KEY_SLOT) ?: vault.load("esp:${session.uid}") ?: "" }
                    user = session; demo = false; resetDisplay(); pairingCode = code; deviceKey = espKey
                    refreshLocal()
                }
            } catch (e: Exception) { report(e) }
            finally { busy = false }
        }
    }
    fun enterDemo() {
        demo = true; user = null; resetDisplay(); error = ""; deviceKey = ""
        peerStatus = "Simulación sin conexiones"
        scope.launch { refreshLocal() }
    }
    fun logout() {
        stop()
        scope.launch { mutex.withLock {
            user = null; demo = false; resetDisplay(); pairingCode = ""; deviceKey = ""
            localHistory = emptyList(); error = ""
        } }
    }
    private fun resetDisplay() {
        stop(); hub.clear(); reviews = emptyList(); lastSave = 0; lastState = ""
    }
    fun choosePublisher(value: Boolean) {
        if (!running && publisher != value && requireAdmin()) {
            publisher = value; reading = null; reviews = emptyList(); hub.clear(); persistSettings()
        }
    }
    fun regenerateCode() {
        if (running || demo || user == null || !requireAdmin()) return
        scope.launch {
            try {
                pairingCode = LocalCrypto.hex(LocalCrypto.randomBytes(32))
                withContext(Dispatchers.IO) { vault.save(PAIRING_SLOT, pairingCode) }
            } catch (e: Exception) { report(e) }
        }
    }
    fun start() {
        error = ""
        try {
            if (!demo) {
                require(user != null)
                if (!publisher) privateIp(peerIp)
                else if (!simulatedSensor) { espUrl(ip); LocalCrypto.deviceKey(deviceKey) }
                LocalCrypto.pairingKey(pairingCode)
            }
            val admin = isAdmin
            if (admin) persistSettings()
            simulator.reset(); lastCommand = ""
            running = true
            val saveDeviceKey = !demo && publisher && !simulatedSensor
            scope.launch {
                try {
                    if (!demo && admin) withContext(Dispatchers.IO) {
                        vault.save(PAIRING_SLOT, pairingCode)
                        if (saveDeviceKey) vault.save(ESP_KEY_SLOT, deviceKey)
                    }
                } catch (e: Exception) { report(e); stop() }
            }
        } catch (e: Exception) { report(e) }
    }
    fun stop() {
        running = false; reading = null; sensorStatus = "Monitoreo detenido"
        peerStatus = "Enlace local detenido"; server?.stop(); server = null; hub.update(null)
    }
    /** Se ejecuta únicamente mientras la Activity está visible. */
    suspend fun poll() {
        try {
            while (true) {
                if (running) mutex.withLock {
                    if (running) {
                        if (demo) demoTick()
                        else if (publisher) {
                            try {
                                if (server == null) {
                                    val created = LocalPeerServer(LocalCrypto.pairingKey(pairingCode), hub)
                                    created.start(); server = created
                                    localIps = withContext(Dispatchers.IO) { localAddresses() }
                                }
                                peerStatus = "Emisor local activo · puerto $PEER_PORT"
                            } catch (e: Exception) { peerStatus = "Enlace detenido: ${friendly(e)}" }
                            hub.takeTests().forEach { origin -> runTest(origin) }
                            readSensor()
                            reviews = hub.list(); lastCommand = hub.command()
                        } else {
                            try { applyRemote(exchange("status")); peerStatus = "Conectado al emisor por Wi-Fi local" }
                            catch (e: Exception) {
                                if (e is CancellationException) throw e
                                reading = null; sensorStatus = "Sin datos del emisor"
                                peerStatus = "Sin conexión: revisa IP, código y monitor del emisor. ${friendly(e)}"
                            }
                        }
                    }
                }
                delay(2000)
            }
        } finally {
            server?.stop(); server = null; hub.update(null)
            reading = null; peerStatus = "Enlace en pausa; vuelve a la aplicación"
        }
    }
    private suspend fun demoTick() {
        val r = simulator.read()
        reading = r; sensorStatus = "Datos simulados · ningún circuito conectado"; saveReading(r)
    }
    private suspend fun readSensor() {
        try {
            val r = if (simulatedSensor) simulator.read()
                else Reading.parse(EspClient.status(ip, LocalCrypto.deviceKey(deviceKey)))
            if (!running) return
            reading = r; hub.update(r)
            sensorStatus = when {
                simulatedSensor -> "ESP32 simulado · ningún circuito conectado"
                r.connected -> "ESP32 conectado y autenticado · Arduino con datos"
                else -> "ESP32 conectado y autenticado · Arduino sin datos"
            }
            saveReading(r)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            reading = null; hub.update(null); sensorStatus = "Sin conexión al ESP32: ${friendly(e)}"
        }
    }
    private fun request(op: String, reviewId: String? = null) = JSONObject().put("id", UUID.randomUUID().toString())
        .put("op", op).put("sender", deviceId).put("device", deviceName).apply { reviewId?.let { put("reviewId", it) } }
    private suspend fun exchange(op: String, reviewId: String? = null): JSONObject {
        val request = request(op, reviewId)
        return if (publisher) hub.handle(request) else withContext(Dispatchers.IO) {
            LocalPeerClient.exchange(peerIp, LocalCrypto.pairingKey(pairingCode), request)
        }
    }
    private suspend fun applyRemote(j: JSONObject) {
        if (!running) return
        val array = j.getJSONArray("reviews")
        require(array.length() <= 20)
        reviews = (0 until array.length()).map { Review.parse(array.getJSONObject(it)) }
        lastCommand = j.optString("command").take(200)
        if (j.isNull("reading") || j.getLong("age_ms") !in 0L..10000L) {
            reading = null; sensorStatus = "El emisor no tiene lecturas recientes del ESP32"; return
        }
        val remote = j.getJSONObject("reading")
        val simulated = remote.optString("source") == SIMULATED_SOURCE
        val r = Reading.parse(remote, if (simulated) "Emisor · $SIMULATED_SOURCE" else "Emisor local")
            .copy(time = System.currentTimeMillis())
        reading = r
        sensorStatus = if (simulated) "Lectura simulada recibida del emisor por la red local" else "Lectura recibida por la red local"
        saveReading(r)
    }
    /** Control del dispositivo: equivale a enviar "T" al Arduino (LED rojo y buzzer durante 3 s). */
    fun testAlarm() = scope.launch { mutex.withLock {
        if (!running || busy || !requireAdmin()) return@withLock
        busy = true; error = ""
        try {
            if (demo || publisher) {
                runTest(deviceName)
                if (demo) demoTick() else readSensor()
            } else lastCommand = exchange("test").optString("command").take(200)
        } catch (e: Exception) { report(e) }
        finally { busy = false }
    } }
    private suspend fun runTest(origin: String) {
        val result = try {
            if (demo || simulatedSensor) simulator.triggerTest()
            else EspClient.test(ip, LocalCrypto.deviceKey(deviceKey))
            "Prueba de alarma enviada"
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            "Prueba de alarma no enviada: ${friendly(e)}"
        }
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        lastCommand = "$result · pedida por $origin · $time"
        hub.setCommand(lastCommand)
    }
    private suspend fun saveReading(r: Reading) {
        val now = System.currentTimeMillis()
        if (now - lastSave < 5000 && lastState == r.state) return
        val account = owner
        withContext(Dispatchers.IO) { history.add(account, r) }
        lastSave = now; lastState = r.state; refreshLocal()
    }
    private suspend fun refreshLocal() {
        val account = owner
        localHistory = withContext(Dispatchers.IO) { history.list(account) }
    }
    fun clearHistory() = scope.launch { mutex.withLock {
        if (!requireAdmin()) return@withLock
        val account = owner
        withContext(Dispatchers.IO) { history.clear(account) }
        refreshLocal()
    } }
    fun requestReview() = performReview("request", UUID.randomUUID().toString())
    fun acknowledge(review: Review) {
        if (review.sender == deviceId || review.ack.isNotEmpty()) return
        performReview("ack", review.id)
    }
    private fun performReview(op: String, id: String) = scope.launch { mutex.withLock {
        if (!running || demo || user == null) return@withLock
        busy = true; error = ""
        try {
            val response = exchange(op, id)
            val array = response.getJSONArray("reviews")
            reviews = (0 until array.length()).map { Review.parse(array.getJSONObject(it)) }
        } catch (e: Exception) { report(e) }
        finally { busy = false }
    } }
    private fun localAddresses(): String = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }.flatMap { network -> network.inetAddresses.toList()
            .filter { it is java.net.Inet4Address && it.isSiteLocalAddress }.map { "${it.hostAddress} (${network.name})" } }
        .joinToString("\n").ifEmpty { "No se encontró una IP privada. Conecta el Wi-Fi o activa el punto de acceso." }
    private fun report(e: Exception) { if (e is CancellationException) throw e; error = friendly(e) }
    private fun friendly(e: Exception) = when (e) {
        is java.net.SocketTimeoutException -> "La conexión tardó demasiado."
        is java.net.ConnectException -> "No se pudo conectar."
        is javax.crypto.AEADBadTagException -> "El código de enlace no coincide."
        is org.json.JSONException -> "Formato de datos inesperado."
        else -> e.message ?: "No se pudo completar la operación local."
    }
    override fun onCleared() { server?.stop(); scope.cancel(); history.close() }
}
