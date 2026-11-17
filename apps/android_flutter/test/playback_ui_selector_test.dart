import 'package:flutter_test/flutter_test.dart';
import 'package:lan_audio_android/audio/background_playback_service.dart';
import 'package:lan_audio_android/ui/playback_ui_selector.dart';

void main() {
  PlaybackServiceSnapshot make({String state = 'streaming', int rate = 48000, int packets = 0}) =>
      PlaybackServiceSnapshot.fromMap({
        'state': state,
        'mode_profile': {'start_buffer_ms': 60},
        'metrics': {'sample_rate': rate, 'channels': 2, 'udp_packets': packets},
      });
  test('telemetry counters do not rebuild controls; playback and format changes do', () {
    final selector = PlaybackUiSelector();
    expect(selector.accept(make()), isTrue);
    for (var i = 1; i <= 1000; i++) {
      expect(selector.accept(make(packets: i)), isFalse);
    }
    expect(selector.accept(make(state: 'recovering')), isTrue);
    expect(selector.accept(make(state: 'recovering', rate: 44100)), isTrue);
  });
}
