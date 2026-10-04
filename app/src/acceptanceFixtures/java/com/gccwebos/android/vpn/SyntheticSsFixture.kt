package com.gccwebos.android.vpn

import java.io.EOFException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/** Test-only SS AEAD endpoint. Binds ONLY loopback and NEVER forwards or resolves a destination.
 * Every response is synthesized locally from a per-run nonce; no Internet server is involved.
 */
class SyntheticSsFixture(private val dnsAnswerLastOctet: Int = 10) : AutoCloseable {
    init { require(dnsAnswerLastOctet in 1..254) }
    private val random = SecureRandom()
    val password = "ephemeral-fixture-" + randomBytes(16).joinToString("") { "%02x".format(it) }
    val token = randomBytes(12).joinToString("") { "%02x".format(it) }
    val key: ByteArray = MessageDigest.getInstance("MD5").digest(password.toByteArray())
    private val running = AtomicBoolean(true)
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 200 }
    val port: Int get() = server.localPort
    private val datagrams = DatagramSocket(port, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 200 }
    private val clients = CopyOnWriteArrayList<Socket>()
    private val workers = CopyOnWriteArrayList<Thread>()
    val tcpRequests = AtomicInteger()
    val udpRequests = AtomicInteger()
    val dnsRequests = AtomicInteger()
    val privateDnsProbesRejected = AtomicInteger()
    val errors = CopyOnWriteArrayList<String>()
    val tcpProof get() = "GCC_TUN_TCP_$token"
    val udpRequest get() = "GCC_TUN_UDP_REQUEST_$token".toByteArray()
    val udpProof get() = "GCC_TUN_UDP_REPLY_$token".toByteArray()
    val queryName get() = "$token.invalid"

    init {
        workers += thread(name = "synthetic-ss-tcp", isDaemon = true) {
            while (running.get()) try {
                val socket = server.accept().apply { soTimeout = 5000 }
                clients += socket
                workers += thread(name = "synthetic-ss-client", isDaemon = true) { handle(socket) }
            } catch (_: SocketTimeoutException) { }
            catch (_: SocketException) { if (running.get()) errors += "TCP_LISTENER" }
        }
        workers += thread(name = "synthetic-ss-udp", isDaemon = true) {
            while (running.get()) try {
                val packet = DatagramPacket(ByteArray(2048), 2048)
                datagrams.receive(packet)
                val wire = packet.data.copyOf(packet.length)
                require(wire.size >= 32)
                val decoded = crypt(false, subkey(key, wire.copyOfRange(0, 16)), 0, wire.copyOfRange(16, wire.size))
                require(decoded.size >= 7 && decoded.copyOfRange(0, 7).contentEquals(address(UDP_PORT)))
                require(decoded.copyOfRange(7, decoded.size).contentEquals(udpRequest))
                udpRequests.incrementAndGet()
                val salt = randomBytes(16)
                val response = salt + crypt(true, subkey(key, salt), 0, address(UDP_PORT) + udpProof)
                datagrams.send(DatagramPacket(response, response.size, packet.socketAddress))
                decoded.fill(0); wire.fill(0)
            } catch (_: SocketTimeoutException) { }
            catch (_: Exception) { if (running.get()) errors += "UDP_REJECTED" }
        }
    }

    private fun handle(socket: Socket) {
        var stage = "FRAME"
        var addressLabel = "unparsed"
        try {
            val stream = AeadStream(socket, key)
            var first = stream.read()
            while (first.size < 7) first += stream.read()
            require(first[0] == 1.toByte()) // Literal IPv4 only; never invoke DNS/network forwarding.
            val destination = first.copyOfRange(0, 7)
            addressLabel = destination.drop(1).take(4).joinToString(".") { (it.toInt() and 255).toString() } + ":" + ushort(destination, 5)
            stage = "DESTINATION"
            var body = first.copyOfRange(7, first.size)
            when {
                destination.contentEquals(address(TCP_PORT)) -> {
                    stage = "HTTP"
                    while (!String(body, Charsets.US_ASCII).contains("\r\n\r\n")) {
                        require(body.size < 4096); body += stream.read()
                    }
                    require(String(body, Charsets.US_ASCII).startsWith("GET /$token HTTP/1.1\r\n"))
                    tcpRequests.incrementAndGet()
                    stream.write("HTTP/1.1 200 OK\r\nContent-Length: ${tcpProof.length}\r\nConnection: close\r\n\r\n$tcpProof".toByteArray())
                }
                destination.contentEquals(byteArrayOf(1, 1, 1, 1, 1, 0, 53)) -> {
                    stage = "DNS"
                    while (body.size < 2) body += stream.read()
                    val length = ushort(body, 0); require(length in 17..512)
                    while (body.size < length + 2) body += stream.read()
                    val q = body.copyOfRange(2, 2 + length)
                    require(q.size >= 12 + questionTail().size && q[4] == 0.toByte() && q[5] == 1.toByte())
                    require(q.copyOfRange(12, 12 + questionTail().size).contentEquals(questionTail()))
                    dnsRequests.incrementAndGet()
                    val response = q.copyOfRange(0, 2) + byteArrayOf(0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0) +
                        questionTail() + byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 1, 0, 4, 198.toByte(), 18, 0, dnsAnswerLastOctet.toByte())
                    stream.write(byteArrayOf((response.size shr 8).toByte(), response.size.toByte()) + response)
                }
                destination.contentEquals(byteArrayOf(1, 172.toByte(), 19, 0, 2, 3, 85)) -> {
                    // Android may validate the configured TUN DNS server with a DoT probe.
                    // This fixture does not offer TLS DNS: close locally, NEVER forward it.
                    privateDnsProbesRejected.incrementAndGet()
                }
                else -> error("Fixture destination rejected")
            }
            first.fill(0); body.fill(0)
        } catch (_: EOFException) { /* Core shutdown may close an idle stream. */ }
        catch (failure: Exception) { if (running.get()) errors += "TCP_${stage}_${failure.javaClass.simpleName}_$addressLabel" }
        finally { clients -= socket; runCatching { socket.close() } }
    }

    fun dnsQuestion(): ByteArray = byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0) + questionTail()
    private fun questionTail(): ByteArray = queryName.split('.').fold(byteArrayOf()) { out, label ->
        out + byteArrayOf(label.length.toByte()) + label.toByteArray(Charsets.US_ASCII)
    } + byteArrayOf(0, 0, 1, 0, 1)
    private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)

    override fun close() {
        running.set(false); server.close(); datagrams.close(); clients.forEach { runCatching { it.close() } }
        workers.forEach { it.join(1500) }; key.fill(0)
    }
    override fun toString() = "SyntheticSsFixture(<ephemeral>)"

    companion object {
        const val DESTINATION = "198.18.0.10"
        const val DNS = "172.19.0.2"
        const val TCP_PORT = 18080
        const val UDP_PORT = 18081
        fun address(port: Int) = byteArrayOf(1, 198.toByte(), 18, 0, 10, (port shr 8).toByte(), port.toByte())
        private fun ushort(bytes: ByteArray, at: Int) = ((bytes[at].toInt() and 255) shl 8) or (bytes[at + 1].toInt() and 255)
        internal fun subkey(key: ByteArray, salt: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(salt, "HmacSHA1")); val prk = mac.doFinal(key)
            return try {
                mac.init(SecretKeySpec(prk, "HmacSHA1"))
                mac.doFinal("ss-subkey".toByteArray() + byteArrayOf(1)).copyOf(16)
            } finally { prk.fill(0) }
        }
        internal fun crypt(encrypt: Boolean, key: ByteArray, nonce: Long, input: ByteArray): ByteArray {
            val iv = ByteArray(12); for (n in 0..7) iv[n] = (nonce ushr (8 * n)).toByte()
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                doFinal(input)
            }
        }
    }

    /** Separate directional random salts and little-endian incrementing nonces, SS AEAD framing. */
    class AeadStream(private val socket: Socket, private val master: ByteArray) {
        private var inputKey: ByteArray? = null
        private var outputKey: ByteArray? = null
        private var readNonce = 0L
        private var writeNonce = 0L
        fun read(): ByteArray {
            if (inputKey == null) inputKey = subkey(master, readExact(16))
            val length = ushort(crypt(false, requireNotNull(inputKey), readNonce++, readExact(18)), 0)
            require(length in 1..16383)
            return crypt(false, requireNotNull(inputKey), readNonce++, readExact(length + 16))
        }
        fun write(data: ByteArray) {
            require(data.size in 1..16383)
            val out = socket.getOutputStream()
            if (outputKey == null) {
                val salt = ByteArray(16).also(SecureRandom()::nextBytes)
                outputKey = subkey(master, salt); out.write(salt)
            }
            out.write(crypt(true, requireNotNull(outputKey), writeNonce++, byteArrayOf((data.size shr 8).toByte(), data.size.toByte())))
            out.write(crypt(true, requireNotNull(outputKey), writeNonce++, data)); out.flush()
        }
        private fun readExact(size: Int): ByteArray {
            val bytes = ByteArray(size); var count = 0
            while (count < size) {
                val read = socket.getInputStream().read(bytes, count, size - count)
                if (read < 0) throw EOFException()
                count += read
            }
            return bytes
        }
    }
}
