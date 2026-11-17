import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lan_audio_android/main.dart';

void main() {
  testWidgets('background detaches telemetry while leaving audio service running',
      (tester) async {
    const events = MethodChannel('lan_audio/playback_events');
    const service = MethodChannel('lan_audio/playback_service');
    final messenger = tester.binding.defaultBinaryMessenger;
    final eventCalls = <String>[];
    final serviceCalls = <String>[];
    messenger.setMockMethodCallHandler(events, (call) async {
      eventCalls.add(call.method);
      return null;
    });
    messenger.setMockMethodCallHandler(service, (call) async {
      serviceCalls.add(call.method);
      return call.method == 'getSnapshot' ? <String, dynamic>{'state': 'disconnected'} : null;
    });
    addTearDown(() {
      messenger.setMockMethodCallHandler(events, null);
      messenger.setMockMethodCallHandler(service, null);
    });
    await tester.binding.setSurfaceSize(const Size(480, 1200));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(const LanAudioApp());
    await tester.pump();
    expect(eventCalls.where((call) => call == 'listen').length, 1);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    await tester.pump();
    expect(eventCalls.where((call) => call == 'cancel').length, 1);
    expect(serviceCalls, isNot(contains('stopPlayback')));
    expect(serviceCalls, isNot(contains('disconnect')));
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();
    expect(eventCalls.where((call) => call == 'listen').length, 2);
    expect(serviceCalls.where((call) => call == 'getSnapshot').length, 2);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();
  });
}
