import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lan_audio_android/main.dart';
import 'package:lan_audio_android/ui/pages/connect_page.dart';
import 'package:lan_audio_android/ui/pages/play_page.dart';
import 'package:lan_audio_android/ui/pages/settings_page.dart';

Map<String, dynamic> snapshot(int buffer, {String mode = 'balanced'}) => {
      'state': 'streaming',
      'mode': mode,
      'data_plane': 'v2_header',
      'effective_codec': 'opus',
      'metrics': {
        'sample_rate': 48000,
        'channels': 2,
        'buffered_ms': buffer,
        'udp_packets': buffer * 100,
        'rtt_ms': 5,
        'loss_estimate': 0,
      },
    };

void main() {
  testWidgets('telemetry updates text without rebuilding pages; controls still update',
      (tester) async {
    const service = MethodChannel('lan_audio/playback_service');
    const events = MethodChannel('lan_audio/playback_events');
    final messenger = tester.binding.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(service, (call) async =>
        call.method == 'getSnapshot' ? snapshot(60) : null);
    messenger.setMockMethodCallHandler(events, (_) async => null);
    addTearDown(() {
      messenger.setMockMethodCallHandler(service, null);
      messenger.setMockMethodCallHandler(events, null);
    });
    await tester.binding.setSurfaceSize(const Size(480, 1200));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(const LanAudioApp());
    await tester.pump();
    await tester.pump();
    final play = tester.widget<PlayPage>(find.byType(PlayPage));
    final connect = tester.widget<ConnectPage>(find.byType(ConnectPage, skipOffstage: false));
    final settings = tester.widget<SettingsPage>(find.byType(SettingsPage, skipOffstage: false));
    Future<void> emit(Map<String, dynamic> value) async {
      await messenger.handlePlatformMessage(events.name,
          const StandardMethodCodec().encodeSuccessEnvelope(value), (_) {});
      await tester.pump();
    }
    for (var buffer = 61; buffer <= 80; buffer++) {
      await emit(snapshot(buffer));
    }
    expect(identical(play, tester.widget<PlayPage>(find.byType(PlayPage))), isTrue);
    expect(identical(connect, tester.widget<ConnectPage>(find.byType(ConnectPage, skipOffstage: false))), isTrue);
    expect(identical(settings, tester.widget<SettingsPage>(find.byType(SettingsPage, skipOffstage: false))), isTrue);
    final line = tester.widget<Text>(find.byKey(const ValueKey('playback-metrics')));
    expect(line.data, contains('Buf: 80ms'));
    await emit(snapshot(80, mode: 'low_latency'));
    expect(tester.widget<PlayPage>(find.byType(PlayPage)).currentModeId, 'low_latency');
    expect(identical(play, tester.widget<PlayPage>(find.byType(PlayPage))), isFalse);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();
  });
}
