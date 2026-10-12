import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/core/widgets/jan_motion.dart';
import 'package:jannet_ai/core/widgets/jan_states.dart';
import 'package:jannet_ai/core/widgets/jan_step_progress.dart';
import 'package:jannet_ai/features/landing/landing_screen.dart';

/// UI phase 1 tests. Bubbles and the map pulse repeat forever, so the landing
/// tests use a fixed pump instead of pumpAndSettle.
void _setReduceMotion(WidgetTester tester, bool on) {
  tester.platformDispatcher.accessibilityFeaturesTestValue = FakeAccessibilityFeatures(disableAnimations: on);
  addTearDown(tester.platformDispatcher.clearAccessibilityFeaturesTestValue);
}

void main() {
  Future<void> pumpLanding(WidgetTester tester, {bool reduceMotion = false}) async {
    tester.view.physicalSize = const Size(1200, 4000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    _setReduceMotion(tester, reduceMotion);
    await tester.pumpWidget(const MaterialApp(home: LandingScreen()));
    await tester.pump(const Duration(seconds: 1));
  }

  testWidgets('landing shows both calls to action and the six issue types', (tester) async {
    await pumpLanding(tester);
    expect(find.text('Report an Issue'), findsOneWidget);
    expect(find.text('Track Complaint'), findsOneWidget);
    for (final t in ['Pothole', 'Garbage', 'Water Leakage', 'Street Light', 'Open Manhole', 'Illegal Construction']) {
      expect(find.text(t), findsOneWidget, reason: t);
    }
    expect(find.text('Privacy Policy'), findsOneWidget);
    expect(find.text('Terms and Conditions'), findsOneWidget);
  });

  testWidgets('landing renders under reduced motion', (tester) async {
    await pumpLanding(tester, reduceMotion: true);
    expect(find.text('Report an Issue'), findsOneWidget);
  });

  testWidgets('JanReveal shows its child, also under reduced motion', (tester) async {
    for (final reduce in [false, true]) {
      _setReduceMotion(tester, reduce);
      await tester.pumpWidget(const MaterialApp(home: JanReveal(child: Text('hello'))));
      await tester.pumpAndSettle();
      expect(find.text('hello'), findsOneWidget);
    }
  });

  testWidgets('JanStepProgress reports completed steps to accessibility', (tester) async {
    await tester.pumpWidget(const MaterialApp(
      home: Scaffold(body: JanStepProgress(labels: ['Photo', 'Details', 'Location', 'Submit'], done: [true, false, true, false])),
    ));
    await tester.pumpAndSettle();
    expect(find.bySemanticsLabel('Progress: 2 of 4 steps complete'), findsOneWidget);
  });

  testWidgets('success dialog shows title, message and runs the primary action', (tester) async {
    var done = 0;
    await tester.pumpWidget(MaterialApp(
      home: Builder(
        builder: (context) => TextButton(
          onPressed: () => showJanSuccessDialog(context,
              title: 'Report Submitted', message: 'Reference JN-1', primaryLabel: 'View', onPrimary: () => done++),
          child: const Text('open'),
        ),
      ),
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    expect(find.text('Report Submitted'), findsOneWidget);
    await tester.tap(find.text('View'));
    await tester.pumpAndSettle();
    expect(done, 1);
  });
}
