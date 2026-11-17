package com.example.lan_audio_android_mvp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class LasPacketParserTest {
    private fun wirePacket(v2: Boolean, payload: ByteArray, headerSize: Int = if (v2) 33 else 27): ByteArray {
        val out = ByteBuffer.allocate(headerSize + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put(if (v2) "LAV2".toByteArray() else "LAS1".toByteArray())
        if (v2) {
            out.put(2.toByte()).putShort(headerSize.toShort()).putShort(0x0206.toShort())
            out.putInt(-123).putLong(0x0102030405060708L)
            out.put(3.toByte()).put(2.toByte()).putInt(48000)
            out.putShort(5.toShort()).putShort(payload.size.toShort()).putShort(0.toShort())
        } else {
            out.put(1.toByte()).put(6.toByte()).putInt(-123).putLong(0x0102030405060708L)
            out.putInt(48000).put(2.toByte()).putShort(240.toShort()).putShort(payload.size.toShort())
        }
        out.position(headerSize)
        out.put(payload)
        return out.array()
    }

    @Test fun parsesBothProtocolsAtNonzeroOffsets() {
        for (v2 in listOf(false, true)) {
            for (headerSize in if (v2) listOf(33, 40) else listOf(27)) {
                val payload = byteArrayOf(0, 1, -1, 127, -128)
                val wire = wirePacket(v2, payload, headerSize)
                val receiveBuffer = ByteArray(8192) { 0x55 }
                wire.copyInto(receiveBuffer, 7)
                val packet = requireNotNull(LasPacketParser.parse(receiveBuffer, 7, wire.size))
                assertEquals(if (v2) 2 else 1, packet.protocolVersion)
                assertEquals(headerSize, packet.headerSize)
                assertEquals(if (v2) 0x0206 else 6, packet.flags)
                assertEquals(-123, packet.sequence)
                assertEquals(0x0102030405060708L, packet.timestampMs)
                assertEquals(if (v2) 3 else 1, packet.codec)
                assertEquals(48000, packet.sampleRate)
                assertEquals(2, packet.channels)
                assertEquals(240, packet.framesPerPacket)
                assertEquals(5, packet.frameDurationMs)
                assertTrue(packet.hasConfigChanged && packet.hasDiscontinuity)
                assertArrayEquals(payload, packet.payload)
                assertArrayEquals(payload, requireNotNull(LasPacketParser.parse(wire)).payload)
                // Simulate the next receive overwriting all scratch memory.
                receiveBuffer.fill(0)
                assertArrayEquals(payload, packet.payload)
            }
        }
    }

    @Test fun validatesTheDatagramLengthInsteadOfBackingArrayCapacity() {
        for (v2 in listOf(false, true)) {
            val wire = wirePacket(v2, ByteArray(100) { it.toByte() })
            val receiveBuffer = ByteArray(8192)
            wire.copyInto(receiveBuffer, 3)
            for (truncatedLength in 0 until wire.size) {
                assertNull(LasPacketParser.parse(receiveBuffer, 3, truncatedLength))
            }
            assertNull(LasPacketParser.parse(receiveBuffer, 3, wire.size + 1))
            assertNotNull(LasPacketParser.parse(receiveBuffer, 3, wire.size))
        }
    }

    @Test fun rejectsInvalidRangesWithoutOverflowOrExceptions() {
        val bytes = wirePacket(true, byteArrayOf(1, 2))
        for ((offset, length) in listOf(-1 to 4, Int.MIN_VALUE to 4,
            0 to -1, 0 to Int.MAX_VALUE, 1 to Int.MAX_VALUE,
            Int.MAX_VALUE to 4, bytes.size to 4)) {
            assertNull(LasPacketParser.parse(bytes, offset, length))
        }
        assertNull(LasPacketParser.parse(bytes, Int.MIN_VALUE))
        assertNull(LasPacketParser.parse(bytes, bytes.size + 1))
        assertNull(LasPacketParser.parse(ByteArray(0)))
    }

    @Test fun rejectsInvalidV2HeadersAndMagic() {
        for (mutation in listOf<(ByteArray) -> Unit>(
            { it[0] = 0 }, { it[4] = 3 }, { it[21] = 2 },
            { it[5] = 32 }, { it[5] = 127 }, { it[29] = 99 },
        )) {
            val wire = wirePacket(true, byteArrayOf(1))
            mutation(wire)
            assertNull(LasPacketParser.parse(wire))
        }
    }

    @Test fun payloadOwnershipSurvivesAlternatingPacketSizes() {
        val receiveBuffer = ByteArray(8192)
        val packets = listOf(4, 2048, 0, 4096, 1).map { length ->
            val wire = wirePacket(true, ByteArray(length) { (length + it).toByte() })
            wire.copyInto(receiveBuffer)
            requireNotNull(LasPacketParser.parse(receiveBuffer, 0, wire.size))
        }
        receiveBuffer.fill(0)
        for (packet in packets) {
            val length = packet.payload.size
            assertArrayEquals(ByteArray(length) { (length + it).toByte() }, packet.payload)
        }
    }

    @Test fun arbitraryShortDatagramsDoNotThrow() {
        val random = Random(42)
        repeat(10000) {
            val length = random.nextInt(80)
            val bytes = random.nextBytes(length + 10)
            if (length >= 4 && it % 2 == 0) {
                (if (it % 4 == 0) "LAS1" else "LAV2").toByteArray().copyInto(bytes, 5)
            }
            LasPacketParser.parse(bytes, 5, length)
        }
    }
}
