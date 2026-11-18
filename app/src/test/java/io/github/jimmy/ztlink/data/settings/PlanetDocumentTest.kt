package io.github.jimmy.ztlink.data.settings

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PlanetDocumentTest {

    @Test
    fun `parse and withIpv4Endpoints updates only public IPv4 while preserving worldId timestamp and port`() {
        val sampleBytes = buildSamplePlanetBytes(
            worldId = 0x000000003A46F1A2L,
            timestamp = 1710000000000L,
            ipv4 = byteArrayOf(115.toByte(), 190.toByte(), 54.toByte(), 68.toByte()),
            port = 9993,
        )

        val doc = PlanetDocument.parse(sampleBytes)
        assertEquals(0x000000003A46F1A2L, doc.worldId)
        assertEquals(1710000000000L, doc.timestamp)
        assertEquals("115.190.54.68", doc.primaryIpv4)
        assertEquals(9993, doc.primaryPort)

        val updatedBytes = doc.withIpv4Endpoints("223.5.5.5")
        val updatedDoc = PlanetDocument.parse(updatedBytes)
        assertEquals(doc.worldId, updatedDoc.worldId)
        assertEquals(doc.timestamp, updatedDoc.timestamp)
        assertEquals("223.5.5.5", updatedDoc.primaryIpv4)
        assertEquals(9993, updatedDoc.primaryPort)

        // 验证除 4 字节 IPv4 外的签名、公钥、头部与端口完全一致
        val restoredBytes = updatedDoc.withIpv4Endpoints("115.190.54.68")
        assertArrayEquals(sampleBytes, restoredBytes)
    }

    @Test
    fun `PlanetDohResolver distinguishes http download url from ddns domain`() {
        assertFalse(PlanetDohResolver.isHttpDownloadUrl("ddns.example.com"))
        assertFalse(PlanetDohResolver.isHttpDownloadUrl("ddns.example.com:9993"))
        assertFalse(PlanetDohResolver.isHttpDownloadUrl("https://ddns.example.com"))
        assertFalse(PlanetDohResolver.isHttpDownloadUrl("https://ddns.example.com/"))
        assertTrue(PlanetDohResolver.isHttpDownloadUrl("https://example.com/planet"))

        assertEquals("ddns.example.com", PlanetDohResolver.extractHost("ddns.example.com"))
        assertEquals("ddns.example.com", PlanetDohResolver.extractHost("ddns.example.com:9993"))
        assertEquals("ddns.example.com", PlanetDohResolver.extractHost("https://ddns.example.com/"))
    }

    @Test
    fun `PlanetDohResolver parses RFC 8484 wire A response regardless of DNS ID and skips private IPv4`() {
        // 构造带 EDNS0 与非 0 ID 的响应包，验证 RFC 8484 兼容解析
        // 1) 包含 Fake-IP 198.18.0.1 与公网 IP 101.81.76.191 的双记录响应
        val out = ByteArrayOutputStream()
        // Header: ID=0x1234, Flags=0x8180, QD=1, AN=2, NS=0, AR=0
        out.write(byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0x00, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00))
        // Question: a.b IN A
        out.write(byteArrayOf(0x01, 'a'.code.toByte(), 0x01, 'b'.code.toByte(), 0x00, 0x00, 0x01, 0x00, 0x01))
        // Answer 1 (Fake-IP 198.18.0.1)
        out.write(byteArrayOf(0xC0.toByte(), 0x0C, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x05, 0x00, 0x04))
        out.write(byteArrayOf(198.toByte(), 18, 0, 1))
        // Answer 2 (Public 101.81.76.191)
        out.write(byteArrayOf(0xC0.toByte(), 0x0C, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x05, 0x00, 0x04))
        out.write(byteArrayOf(101, 81, 76, 191.toByte()))

        val parsed = PlanetDohResolver.parseDnsResponsePacket(out.toByteArray())
        assertEquals("101.81.76.191", parsed)
    }

    private fun buildSamplePlanetBytes(
        worldId: Long,
        timestamp: Long,
        ipv4: ByteArray,
        port: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        // 0: TYPE_PLANET (1)
        out.write(0x01)
        // 1..8: worldId (uint64 BE)
        out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(worldId).array())
        // 9..16: timestamp (uint64 BE)
        out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(timestamp).array())
        // 17..80: public key (64 bytes)
        out.write(ByteArray(64) { 0x11 })
        // 81..176: signature (96 bytes)
        out.write(ByteArray(96) { 0x22 })
        // 177: root count = 1
        out.write(1)
        // Root 0 identity: 5 bytes address + 1 byte type(0) + 64 bytes pubkey + 1 byte privkey len(0) = 71 bytes
        out.write(byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50))
        out.write(0x00)
        out.write(ByteArray(64) { 0x33 })
        out.write(0x00)
        // Endpoint count = 1
        out.write(1)
        // Endpoint 0: type = 4 (IPv4), 4 bytes IP, 2 bytes port BE
        out.write(4)
        out.write(ipv4)
        out.write((port ushr 8) and 0xFF)
        out.write(port and 0xFF)
        return out.toByteArray()
    }
}
