package com.example.detectordegas

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import org.json.JSONObject
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Nube opcional con Firebase: Authentication (correo y contraseña) y Cloud Firestore.
 * Estructura: users/{uid}/readings/{id}. Las reglas (firebase/firestore.rules) solo permiten
 * que cada cuenta lea y cree sus propios documentos; no se pueden modificar ni borrar.
 * Si falta app/google-services.json, [configured] es false y la app sigue funcionando en local.
 */
class CloudSync(context: Context) {
    private val app: FirebaseApp? = FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context)
    val configured get() = app != null
    private val auth get() = FirebaseAuth.getInstance(checkNotNull(app) { NOT_CONFIGURED })
    private val db get() = FirebaseFirestore.getInstance(checkNotNull(app) { NOT_CONFIGURED })

    val email: String? get() = if (app == null) null else auth.currentUser?.email

    /** Bloqueante: llamar desde Dispatchers.IO. */
    fun signIn(email: String, password: String, register: Boolean): String {
        val task = if (register) auth.createUserWithEmailAndPassword(email, password)
            else auth.signInWithEmailAndPassword(email, password)
        await(task)
        return auth.currentUser?.email ?: email
    }

    fun signOut() { if (app != null) auth.signOut() }

    /** Firestore guarda la escritura en caché y la envía cuando hay conexión; no bloquea. */
    fun upload(r: Reading, device: String) {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).collection("readings").add(cloudDocument(r, device))
    }

    /** Bloqueante: últimas lecturas de la cuenta en la nube, de la más reciente a la más antigua. */
    fun list(limit: Long = 100): List<Reading> {
        val uid = auth.currentUser?.uid ?: error("Inicia sesión en la nube desde Ajustes.")
        val snapshot = await(db.collection("users").document(uid).collection("readings")
            .orderBy("time", Query.Direction.DESCENDING).limit(limit).get())
        return snapshot.documents.mapNotNull { doc -> doc.data?.let { runCatching { readingFromCloud(it) }.getOrNull() } }
    }

    private fun <T> await(task: Task<T>): T = try {
        Tasks.await(task, 15, TimeUnit.SECONDS)
    } catch (e: ExecutionException) {
        throw IllegalStateException(cloudMessage(e.cause ?: e), e)
    } catch (_: TimeoutException) {
        throw IllegalStateException("La nube no respondió. Revisa la conexión a Internet.")
    }

    companion object {
        const val NOT_CONFIGURED = "Firebase no está configurado: falta app/google-services.json."
    }
}

/** Documento que se guarda en Firestore. Los campos coinciden con las reglas de seguridad. */
fun cloudDocument(r: Reading, device: String): Map<String, Any?> = mapOf(
    "value" to r.value?.toLong(), "state" to r.state, "connected" to r.connected,
    "source" to r.source.take(40), "time" to r.time, "device" to device.take(100)
)

/** Valida un documento de la nube con las mismas reglas que una lectura del ESP32. */
fun readingFromCloud(data: Map<String, Any?>): Reading {
    val time = data["time"] as? Long ?: error("Fecha inválida.")
    val source = (data["source"] as? String).orEmpty().take(40)
    val device = (data["device"] as? String).orEmpty().take(100)
    val json = JSONObject().put("lectura", data["value"] ?: JSONObject.NULL).put("estado", data["state"])
        .put("conexion_arduino", data["connected"]).put("unidad", "ADC").put("time", time)
    return Reading.parse(json, "Nube · $source${if (device.isNotEmpty()) " · $device" else ""}")
}

fun cloudMessage(e: Throwable): String = when (e) {
    is FirebaseAuthWeakPasswordException -> "La contraseña de la nube es demasiado débil."
    is FirebaseAuthUserCollisionException -> "Ya existe una cuenta en la nube con ese correo."
    is FirebaseAuthInvalidUserException, is FirebaseAuthInvalidCredentialsException -> "Correo o contraseña de la nube incorrectos."
    is FirebaseNetworkException -> "Sin conexión a Internet para usar la nube."
    is FirebaseFirestoreException -> if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED)
        "Firestore rechazó el acceso: revisa las reglas de seguridad." else "Error de Firestore: ${e.code}."
    else -> e.message ?: "No se pudo completar la operación en la nube."
}
