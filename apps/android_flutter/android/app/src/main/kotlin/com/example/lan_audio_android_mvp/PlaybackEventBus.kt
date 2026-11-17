package com.example.lan_audio_android_mvp

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.flutter.plugin.common.EventChannel

object PlaybackEventBus {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val updates = LatestValueDispatcher(
        initial = PlaybackSnapshot(), intervalMs = 250L,
        clockMs = SystemClock::elapsedRealtime,
        post = { task, delay -> mainHandler.postDelayed(task, delay); Unit },
        cancel = { task -> mainHandler.removeCallbacks(task) },
        urgent = ::playbackPresentationChanged,
    )

    fun attachSink(eventSink: EventChannel.EventSink) {
        updates.attach { snapshot ->
            try {
                eventSink.success(snapshot.toStableServiceSnapshot().toMap())
            } catch (_: Throwable) {
            }
        }
    }

    fun detachSink() {
        updates.detach()
    }

    fun publish(snapshot: PlaybackSnapshot) {
        updates.publish(snapshot)
    }

    fun snapshotMap(): Map<String, Any?> {
        return updates.current().toStableServiceSnapshot().toMap()
    }
}

// Metrics can wait; connection, playback and user controls are immediate.
internal fun playbackPresentationChanged(previous: PlaybackSnapshot, next: PlaybackSnapshot): Boolean =
    previous.serviceState != next.serviceState || previous.connectionState != next.connectionState ||
        previous.playbackState != next.playbackState || previous.targetHost != next.targetHost ||
        previous.targetName != next.targetName || previous.currentAudioMode != next.currentAudioMode ||
        previous.modeProfile != next.modeProfile || previous.effectiveCodec != next.effectiveCodec ||
        previous.protocolPath != next.protocolPath || previous.playbackBackend != next.playbackBackend ||
        previous.eqSettings != next.eqSettings ||
        previous.loudnessNormalizationEnabled != next.loudnessNormalizationEnabled || previous.error != next.error
