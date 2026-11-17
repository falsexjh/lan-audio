package com.example.lan_audio_android_mvp

import org.junit.Assert.*
import org.junit.Test

class PlaybackPresentationTest {
    @Test fun perPacketMetricsAndDiagnosticLogsDoNotForceImmediateDispatch() {
        val initial = PlaybackSnapshot(connectionState = "connected", playbackState = "playing")
        val next = initial.copy(
            metrics = initial.metrics.copy(udpPackets = 1000, totalBufferedMs = 30),
            recentLog = "latency_guard_hold:30",
        )
        assertFalse(playbackPresentationChanged(initial, next))
    }

    @Test fun playbackConnectionAndErrorsRemainImmediate() {
        val initial = PlaybackSnapshot()
        for (next in listOf(
            initial.copy(playbackState = "playing"), initial.copy(connectionState = "reconnecting"),
            initial.copy(serviceState = "error"), initial.copy(error = mapOf("code" to "decode")),
        )) assertTrue(playbackPresentationChanged(initial, next))
    }

    @Test fun userAudioControlsRemainImmediate() {
        val initial = PlaybackSnapshot()
        assertTrue(playbackPresentationChanged(initial, initial.copy(currentAudioMode = "low_latency")))
        assertTrue(playbackPresentationChanged(initial, initial.copy(loudnessNormalizationEnabled = true)))
        assertTrue(playbackPresentationChanged(initial, initial.copy(eqSettings = PlaybackEqSettings(enabled = true))))
    }
}
