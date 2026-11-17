import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lan_audio_android/ui/widgets/debug_info_card.dart';
import 'package:lan_audio_android/ui/widgets/latency_chart_widget.dart';

void main() {
  testWidgets('debug data is generated only on opening or explicit refresh', (tester) async {
    var calls = 0;
    Future<void> mount() => tester.pumpWidget(MaterialApp(home: Scaffold(
          body: DebugInfoCard(isZh: false, load: () => {'count': '${++calls}'}),
        )));
    await mount();
    await mount();
    expect(calls, 0);
    await tester.tap(find.text('Debug Info'));
    await tester.pumpAndSettle();
    expect(calls, 1);
    await mount();
    expect(calls, 1);
    await tester.tap(find.text('Refresh'));
    await tester.pump();
    expect(calls, 2);
    expect(find.text('2'), findsOneWidget);
  });

  testWidgets('chart stops sampling when hidden or backgrounded and resumes', (tester) async {
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    var samples = 0;
    Future<void> mount(bool active) => tester.pumpWidget(MaterialApp(home: Scaffold(
          body: LatencyChartWidget(
            active: active, isZh: false,
            currentLatencyMs: () { samples++; return 60; },
            baselineLatencyMs: () => 180,
          ),
        )));
    await mount(true);
    await tester.pump(const Duration(milliseconds: 500));
    expect(samples, 1);
    await mount(false);
    await tester.pump(const Duration(seconds: 2));
    expect(samples, 1);
    await mount(true);
    await tester.pump(const Duration(milliseconds: 500));
    expect(samples, 2);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    await tester.pump(const Duration(seconds: 2));
    expect(samples, 2);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump(const Duration(milliseconds: 500));
    expect(samples, 3);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('stable full chart window avoids rebuilding and parent rebuild avoids repaint', (tester) async {
    Future<void> mount() => tester.pumpWidget(MaterialApp(home: Scaffold(
          body: LatencyChartWidget(
            isZh: false, windowSeconds: 1,
            currentLatencyMs: () => 60,
            baselineLatencyMs: () => 180,
          ),
        )));
    await mount();
    await tester.pump(const Duration(milliseconds: 500));
    await tester.pump(const Duration(milliseconds: 500));
    final first = tester.widget<CustomPaint>(find.byKey(const ValueKey('latency-series')));
    await tester.pump(const Duration(milliseconds: 500));
    final stable = tester.widget<CustomPaint>(find.byKey(const ValueKey('latency-series')));
    expect(identical(first, stable), isTrue);
    await mount();
    final parentRebuilt = tester.widget<CustomPaint>(find.byKey(const ValueKey('latency-series')));
    expect(parentRebuilt.painter!.shouldRepaint(stable.painter!), isFalse);
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
