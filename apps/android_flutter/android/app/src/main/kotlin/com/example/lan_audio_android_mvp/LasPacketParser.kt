package com.example.lan_audio_android_mvp

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class LasPacket(
    val protocolVersion: Int,
    val headerSize: Int,
    val flags: Int,
    val sequence: Int,
    val timestampMs: Long,
    val codec: Int,
    val sampleRate: Int,
    val channels: Int,
    val framesPerPacket: Int,
    val payload: ByteArray,
) {
    companion object {
        const val CODEC_PCM16 = 1
        const val CODEC_OPUS_EXPERIMENTAL = 3
    }

    val frameDurationMs: Int
        get() = if (sampleRate <= 0) 0 else ((framesPerPacket * 1000.0) / sampleRate).toInt()

    val codecLabel: String
        get() = when (codec) {
            CODEC_OPUS_EXPERIMENTAL -> "opus"
            else -> "pcm16"
        }

    val hasConfigChanged: Boolean
        get() = (flags and 0x02) != 0

    val hasDiscontinuity: Boolean
        get() = (flags and 0x04) != 0
}

object LasPacketParser {
    // The receive buffer may be reused immediately after this call. Only the
    // payload is copied, so packets retained by the jitter buffer stay owned.
    fun parse(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): LasPacket? {
        if (offset < 0 || length < 4 || offset > bytes.size || length > bytes.size - offset) {
            return null
        }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return when (bb.getInt(offset)) {
            0x3153414c -> parseLegacy(bytes, bb, offset, length) // LAS1
            0x3256414c -> parseV2(bytes, bb, offset, length) // LAV2
            else -> null
        }
    }

    private fun parseLegacy(bytes: ByteArray, bb: ByteBuffer, offset: Int, length: Int): LasPacket? {
        if (length < 27) {
            return null
        }

        val protocolVersion = bb.get(offset + 4).toInt() and 0xFF
        val flags = bb.get(offset + 5).toInt() and 0xFF
        val sequence = bb.getInt(offset + 6)
        val timestampMs = bb.getLong(offset + 10)
        val sampleRate = bb.getInt(offset + 18)
        val channels = bb.get(offset + 22).toInt() and 0xFF
        val framesPerPacket = bb.getShort(offset + 23).toInt() and 0xFFFF
        val payloadLen = bb.getShort(offset + 25).toInt() and 0xFFFF
        if (length != 27 + payloadLen) {
            return null
        }

        val payload = bytes.copyOfRange(offset + 27, offset + length)
        return LasPacket(
            protocolVersion = protocolVersion,
            headerSize = 27,
            flags = flags,
            sequence = sequence,
            timestampMs = timestampMs,
            codec = LasPacket.CODEC_PCM16,
            sampleRate = sampleRate,
            channels = channels,
            framesPerPacket = framesPerPacket,
            payload = payload,
        )
    }

    private fun parseV2(bytes: ByteArray, bb: ByteBuffer, offset: Int, length: Int): LasPacket? {
        if (length < 33) {
            return null
        }
        val protocolVersion = bb.get(offset + 4).toInt() and 0xFF
        if (protocolVersion != 2) {
            return null
        }
        val headerSize = bb.getShort(offset + 5).toInt() and 0xFFFF
        if (headerSize < 33 || length < headerSize) {
            return null
        }
        val flags = bb.getShort(offset + 7).toInt() and 0xFFFF
        val sequence = bb.getInt(offset + 9)
        val timestampMs = bb.getLong(offset + 13)
        val codec = bb.get(offset + 21).toInt() and 0xFF
        if (codec != LasPacket.CODEC_PCM16 && codec != LasPacket.CODEC_OPUS_EXPERIMENTAL) {
            return null
        }
        val channels = bb.get(offset + 22).toInt() and 0xFF
        val sampleRate = bb.getInt(offset + 23)
        val frameDurationMs = bb.getShort(offset + 27).toInt() and 0xFFFF
        val payloadLen = bb.getShort(offset + 29).toInt() and 0xFFFF
        if (length != headerSize + payloadLen) {
            return null
        }
        val framesPerPacket =
            if (sampleRate <= 0 || frameDurationMs <= 0) 0 else sampleRate * frameDurationMs / 1000
        val payload = bytes.copyOfRange(offset + headerSize, offset + length)
        return LasPacket(
            protocolVersion = protocolVersion,
            headerSize = headerSize,
            flags = flags,
            sequence = sequence,
            timestampMs = timestampMs,
            codec = codec,
            sampleRate = sampleRate,
            channels = channels,
            framesPerPacket = framesPerPacket,
            payload = payload,
        )
    }
}
