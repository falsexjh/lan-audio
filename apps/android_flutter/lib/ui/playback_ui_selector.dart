import 'package:flutter/foundation.dart';

import '../audio/background_playback_service.dart';

/// Keeps packet counters and telemetry out of the application control tree.
class PlaybackUiSelector {
  PlaybackServiceSnapshot? _previous;

  bool accept(PlaybackServiceSnapshot next) {
    final previous = _previous;
    _previous = next;
    if (previous == null) return true;
    return previous.state != next.state ||
        previous.mode != next.mode ||
        previous.transport != next.transport ||
        previous.transportMode != next.transportMode ||
        previous.dataPlane != next.dataPlane ||
        previous.activeDataPlane != next.activeDataPlane ||
        previous.rollbackAvailable != next.rollbackAvailable ||
        previous.rollbackState != next.rollbackState ||
        previous.codec != next.codec ||
        previous.effectiveCodec != next.effectiveCodec ||
        previous.protocolVersion != next.protocolVersion ||
        previous.playbackBackend != next.playbackBackend ||
        previous.connectedClientCount != next.connectedClientCount ||
        previous.serverPlatform != next.serverPlatform ||
        previous.serverAppVersion != next.serverAppVersion ||
        previous.eqEnabled != next.eqEnabled ||
        previous.loudnessNormalizationEnabled !=
            next.loudnessNormalizationEnabled ||
        previous.reconnectAttempts != next.reconnectAttempts ||
        previous.reconnectDelayMs != next.reconnectDelayMs ||
        previous.metrics['sample_rate'] != next.metrics['sample_rate'] ||
        previous.metrics['channels'] != next.metrics['channels'] ||
        !mapEquals(previous.modeProfile, next.modeProfile) ||
        !mapEquals(
          previous.negotiatedCapabilities,
          next.negotiatedCapabilities,
        ) ||
        !mapEquals(previous.eqSettings, next.eqSettings);
  }
}
