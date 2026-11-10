package com.example.detectordegas

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID

const val PEER_PORT = 8765
private const val MAGIC = 0x44474131

data class Review(val id: String, val sender: String, val device: String, val time: Long, val ack: String) {
    fun json() = JSONObject().put("id", id).put("sender", sender).put("device", device).put("time", time).put("ack", ack)
    companion object {
        fun parse(j: JSONObject) = Review(j.getString("id"), j.getString("sender"), j.getString("device"), j.getLong("time"), j.getString("ack"))
    }
}

/** Estado del emisor, independiente del usuario de Android y protegido entre hilos. */
class LocalHub {
    private var current: Reading? = null
    private var updatedAt = 0L
    private val reviews = ArrayList<Review>()
    private val seen = LinkedHashSet<String>()
    private val pendingTests = ArrayList<String>()
    private var command = ""
    @Synchronized fun update(r: Reading?) { current = r; updatedAt = System.nanoTime() }
    @Synchronized fun clear() { current = null; reviews.clear(); seen.clear(); pendingTests.clear(); command = "" }
    @Synchronized fun list(): List<Review> = reviews.toList()
    /** Pruebas de alarma pedidas por el receptor; el emisor las ejecuta en su ciclo de consulta. */
    @Synchronized fun takeTests(): List<String> = pendingTests.toList().also { pendingTests.clear() }
    @Synchronized fun setCommand(text: String) { command = text }
    @Synchronized fun command() = command
    @Synchronized fun handle(j: JSONObject): JSONObject {
        val id = j.getString("id")
        require(UUID.fromString(id).toString() == id && seen.add(id)) { "Solicitud duplicada o inválida." }
        while (seen.size > 256) seen.remove(seen.first())
        val sender = j.getString("sender")
        require(UUID.fromString(sender).toString() == sender)
        val name = j.getString("device")
        require(name.length in 1..100)
        when (j.getString("op")) {
            "status" -> Unit
            "request" -> {
                val reviewId = j.getString("reviewId")
                require(UUID.fromString(reviewId).toString() == reviewId)
                if (reviews.none { it.id == reviewId }) reviews.add(0, Review(reviewId, sender, name, System.currentTimeMillis(), ""))
                while (reviews.size > 20) reviews.removeAt(reviews.lastIndex)
            }
            "ack" -> {
                val index = reviews.indexOfFirst { it.id == j.getString("reviewId") }
                require(index >= 0) { "La solicitud ya no está disponible." }
                val r = reviews[index]
                require(r.sender != sender) { "Confirma desde el otro teléfono." }
                if (r.ack.isEmpty()) reviews[index] = r.copy(ack = name)
            }
            "test" -> {
                require(pendingTests.isEmpty()) { "Ya hay una prueba de alarma pendiente." }
                pendingTests.add(name)
                command = "Prueba de alarma pedida por $name · esperando al emisor"
            }
            else -> error("Operación desconocida.")
        }
        return JSONObject().put("id", id).put("reading", current?.json() ?: JSONObject.NULL)
            .put("age_ms", if (current == null) 0 else (System.nanoTime() - updatedAt) / 1000000)
            .put("reviews", JSONArray().apply { reviews.forEach { put(it.json()) } })
            .put("command", command)
    }
}

fun writeFrame(socket: Socket, packet: ByteArray) {
    require(packet.size in 28..65536)
    DataOutputStream(socket.getOutputStream()).apply { writeInt(MAGIC); writeInt(packet.size); write(packet); flush() }
}
fun readFrame(socket: Socket): ByteArray {
    val input = DataInputStream(socket.getInputStream())
    require(input.readInt() == MAGIC) { "Protocolo de enlace inválido." }
    val size = input.readInt()
    require(size in 28..65536) { "Tamaño de mensaje inválido." }
    return ByteArray(size).also { input.readFully(it) }
}

/** Servidor TCP local, con mensajes AES-GCM y un límite de lectura por conexión. */
class LocalPeerServer(private val key: ByteArray, private val hub: LocalHub,
                      private val requestedPort: Int = PEER_PORT, private val bind: InetAddress? = null) {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    private var thread: Thread? = null
    val port get() = server?.localPort ?: 0
    @Synchronized fun start() {
        if (server != null) return
        val listener = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bind, requestedPort))
        }
        server = listener
        thread = Thread({
            while (!listener.isClosed) {
                try {
                    listener.accept().use { socket ->
                        client = socket
                        try {
                            require(socket.inetAddress.isSiteLocalAddress || socket.inetAddress.isLoopbackAddress)
                            socket.soTimeout = 1500
                            val challenge = LocalCrypto.randomBytes(32)
                            socket.getOutputStream().apply { write(challenge); flush() }
                            val raw = LocalCrypto.open(key, readFrame(socket), "request")
                            val request = JSONObject(raw.toString(Charsets.UTF_8))
                            require(request.getString("challenge") == LocalCrypto.hex(challenge)) { "Desafío de conexión inválido." }
                            val response = hub.handle(request)
                            writeFrame(socket, LocalCrypto.seal(key, response.toString().toByteArray(Charsets.UTF_8), "response"))
                        } catch (_: Exception) {
                            // Mensajes no autenticados o inválidos no reciben información.
                        } finally { client = null }
                    }
                } catch (_: SocketException) { if (!listener.isClosed) Thread.yield() }
                catch (_: Exception) { if (!listener.isClosed) Thread.yield() }
            }
        }, "detector-local-peer").apply { isDaemon = true; start() }
    }
    @Synchronized fun stop() {
        server?.close(); client?.close(); server = null
        thread?.join(2000); thread = null
    }
}

object LocalPeerClient {
    /** La IP se valida sin DNS; esta función no admite servidores públicos. */
    fun exchange(ip: String, key: ByteArray, request: JSONObject): JSONObject {
        val host = privateIp(ip)
        return Socket().use { socket ->
            socket.connect(InetSocketAddress(host, PEER_PORT), 2500)
            socket.soTimeout = 3000
            val challenge = ByteArray(32).also { DataInputStream(socket.getInputStream()).readFully(it) }
            request.put("challenge", LocalCrypto.hex(challenge))
            writeFrame(socket, LocalCrypto.seal(key, request.toString().toByteArray(Charsets.UTF_8), "request"))
            val result = JSONObject(LocalCrypto.open(key, readFrame(socket), "response").toString(Charsets.UTF_8))
            require(result.getString("id") == request.getString("id")) { "Respuesta de otra solicitud." }
            result
        }
    }
}
