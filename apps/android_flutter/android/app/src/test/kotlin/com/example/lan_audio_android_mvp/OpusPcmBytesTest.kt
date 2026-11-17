package com.example.lan_audio_android_mvp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class OpusPcmBytesTest {
    @Test fun preservesEveryPcm16ValueInLittleEndianOrder() {
        val samples = ShortArray(65536) { (it + Short.MIN_VALUE).toShort() }
        val reference = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { reference.putShort(it) }
        assertArrayEquals(reference.array(), OpusNativeDecoder.pcm16LittleEndian(samples, samples.size))
    }

    @Test fun emitsOnlyDecodedSamplesFromLargerScratchBuffer() {
        val scratch = shortArrayOf(0x1234, -2, Short.MIN_VALUE, 0x5678)
        assertArrayEquals(byteArrayOf(0x34, 0x12, -2, -1), OpusNativeDecoder.pcm16LittleEndian(scratch, 2))
        assertArrayEquals(ByteArray(0), OpusNativeDecoder.pcm16LittleEndian(scratch, 0))
    }

    @Test fun returnedPacketsOwnTheirDataAfterScratchReuse() {
        val scratch = shortArrayOf(1, 2, 3)
        val first = OpusNativeDecoder.pcm16LittleEndian(scratch, 3)
        scratch.fill(-1)
        val second = OpusNativeDecoder.pcm16LittleEndian(scratch, 3)
        assertArrayEquals(byteArrayOf(1, 0, 2, 0, 3, 0), first)
        assertArrayEquals(ByteArray(6) { -1 }, second)
    }

    @Test fun rejectsInvalidSampleCounts() {
        for (samples in listOf(-1, 4, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                OpusNativeDecoder.pcm16LittleEndian(ShortArray(3), samples)
            }
        }
    }
}
