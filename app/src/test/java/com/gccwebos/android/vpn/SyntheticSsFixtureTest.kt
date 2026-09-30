package com.gccwebos.android.vpn

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import org.junit.Assert.*
import org.junit.Test

class SyntheticSsFixtureTest {
    @Test fun localFixtureAnswersAuthenticatedTcpWithoutForwarding() {
        SyntheticSsFixture().use { fixture ->
            Socket("127.0.0.1", fixture.port).use { socket ->
                socket.soTimeout = 3000
                val stream = SyntheticSsFixture.AeadStream(socket, fixture.key)
                stream.write(SyntheticSsFixture.address(SyntheticSsFixture.TCP_PORT) + "GET /${fixture.token} HTTP/1.1\r\nHost: fixture.invalid\r\n\r\n".toByteArray())
                assertTrue(String(stream.read()).endsWith(fixture.tcpProof))
            }
            assertEquals(1, fixture.tcpRequests.get()); assertTrue(fixture.errors.isEmpty())
        }
    }
    @Test fun localFixtureAnswersAuthenticatedUdp() {
        SyntheticSsFixture().use { fixture ->
            DatagramSocket().use { socket ->
                socket.soTimeout = 3000
                val salt = ByteArray(16).also(SecureRandom()::nextBytes)
                val encrypted = salt + SyntheticSsFixture.crypt(true, SyntheticSsFixture.subkey(fixture.key, salt), 0,
                    SyntheticSsFixture.address(SyntheticSsFixture.UDP_PORT) + fixture.udpRequest)
                socket.send(DatagramPacket(encrypted, encrypted.size, InetAddress.getByName("127.0.0.1"), fixture.port))
                val reply = DatagramPacket(ByteArray(2048), 2048); socket.receive(reply)
                val key = SyntheticSsFixture.subkey(fixture.key, reply.data.copyOfRange(0, 16))
                val decoded = SyntheticSsFixture.crypt(false, key, 0, reply.data.copyOfRange(16, reply.length))
                assertArrayEquals(fixture.udpProof, decoded.copyOfRange(7, decoded.size))
            }
            assertEquals(1, fixture.udpRequests.get()); assertTrue(fixture.errors.isEmpty())
        }
    }
    @Test fun dnsDestinationIsDecodedButNeverDialed() {
        SyntheticSsFixture().use { fixture ->
            Socket("127.0.0.1", fixture.port).use { socket ->
                socket.soTimeout = 3000; val stream = SyntheticSsFixture.AeadStream(socket, fixture.key)
                val query = fixture.dnsQuestion()
                stream.write(byteArrayOf(1, 1, 1, 1, 1, 0, 53, 0, query.size.toByte()) + query)
                val reply = stream.read()
                assertArrayEquals(byteArrayOf(198.toByte(), 18, 0, 10), reply.takeLast(4).toByteArray())
            }
            assertEquals(1, fixture.dnsRequests.get()); assertTrue(fixture.errors.isEmpty())
        }
    }
    @Test fun secretsArePerRunAndUnsupportedDestinationsAreRejected() {
        SyntheticSsFixture().use { fixture ->
            SyntheticSsFixture().use { other -> assertNotEquals(fixture.password, other.password) }
            Socket("127.0.0.1", fixture.port).use { socket ->
                socket.soTimeout = 3000; val stream = SyntheticSsFixture.AeadStream(socket, fixture.key)
                stream.write(byteArrayOf(1, 203.toByte(), 0, 113, 10, 1, 187.toByte()) + "not-forwarded".toByteArray())
                assertEquals(-1, socket.getInputStream().read())
            }
            assertEquals(listOf("TCP_DESTINATION_IllegalStateException_203.0.113.10:443"), fixture.errors.toList())
            assertEquals(0, fixture.tcpRequests.get())
        }
    }
    @Test fun androidPrivateDnsProbeIsRejectedLocallyWithoutForwardingOrGeneralAllowance() {
        SyntheticSsFixture().use { fixture ->
            Socket("127.0.0.1", fixture.port).use { socket ->
                socket.soTimeout = 3000
                val stream = SyntheticSsFixture.AeadStream(socket, fixture.key)
                stream.write(byteArrayOf(1, 172.toByte(), 19, 0, 2, 3, 85) + byteArrayOf(22, 3, 1))
                assertEquals(-1, socket.getInputStream().read())
            }
            assertEquals(1, fixture.privateDnsProbesRejected.get()); assertTrue(fixture.errors.isEmpty())
            assertEquals(0, fixture.tcpRequests.get()); assertEquals(0, fixture.dnsRequests.get())
            Socket("127.0.0.1", fixture.port).use { socket ->
                socket.soTimeout = 3000
                SyntheticSsFixture.AeadStream(socket, fixture.key).write(byteArrayOf(1, 172.toByte(), 19, 0, 3, 3, 85))
                assertEquals(-1, socket.getInputStream().read())
            }
            assertEquals(1, fixture.errors.size) // Adjacent addresses are not silently allowed.
        }
    }

}
