import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/core/api/api_exception.dart';
import 'package:jannet_ai/features/assistant/assistant_api.dart';
import 'package:jannet_ai/features/assistant/assistant_screen.dart';
import 'package:jannet_ai/features/complaints/complaint_prefill.dart';
import 'package:jannet_ai/features/complaints/models/complaint_status.dart';
import 'package:jannet_ai/features/complaints/widgets/status_badge.dart';

AssistantReply _statusReply() => const AssistantReply(
      sessionId: 'session-1',
      reply: 'Complaint JN-2026-000101 (pothole) is now: In Progress. Work on it has started.',
      source: 'RULE',
      intent: 'COMPLAINT_STATUS',
      cards: [
        AssistantCard(
          type: AssistantCard.complaint,
          complaintId: 101,
          referenceNumber: 'JN-2026-000101',
          category: 'POTHOLE',
          status: ComplaintStatus.inProgress,
          departmentName: 'Public Works Gaya',
        ),
      ],
      actions: [AssistantAction(type: AssistantAction.openComplaint, label: 'Open complaint', value: '101')],
    );

Finder _selectable(String text) => find.byWidgetPredicate((w) => w is SelectableText && w.data == text);

void main() {
  tearDown(() => ComplaintPrefill.description.value = null);

  testWidgets('a starter question is sent and the reply shows the app status badge', (tester) async {
    final sent = <String>[];
    final sessions = <String?>[];
    Future<AssistantReply> sender(String text, {String? sessionId, required String language}) async {
      sent.add(text);
      sessions.add(sessionId);
      return _statusReply();
    }

    await tester.pumpWidget(MaterialApp(home: AssistantScreen(sender: sender)));
    expect(find.text('Where is my complaint?'), findsOneWidget);

    await tester.tap(find.text('Where is my complaint?'));
    await tester.pumpAndSettle();

    expect(sent, ['Where is my complaint?']);
    expect(_selectable('Complaint JN-2026-000101 (pothole) is now: In Progress. Work on it has started.'), findsOneWidget);
    expect(find.text('JN-2026-000101'), findsOneWidget);
    expect(find.byType(StatusBadge), findsOneWidget);
    expect(find.text('Open complaint'), findsOneWidget);

    // the next message continues the same conversation
    await tester.enterText(find.byType(TextField), 'thanks');
    await tester.tap(find.byTooltip('Send'));
    await tester.pumpAndSettle();
    expect(sent, ['Where is my complaint?', 'thanks']);
    expect(sessions, [null, 'session-1']);
  });

  testWidgets('Start report fills the form and returns to the Submit tab', (tester) async {
    Future<AssistantReply> sender(String text, {String? sessionId, required String language}) async =>
        const AssistantReply(
          sessionId: 's',
          reply: 'I have written the description from your words.',
          source: 'AI',
          intent: 'REPORT_DRAFT',
          cards: [
            AssistantCard(type: AssistantCard.reportDraft, category: 'POTHOLE', description: 'Large pothole near the school gate.'),
          ],
          actions: [
            AssistantAction(type: AssistantAction.startReport, label: 'Start report', value: 'Large pothole near the school gate.'),
          ],
        );

    AssistantExit? exit;
    await tester.pumpWidget(MaterialApp(
      home: Builder(
        builder: (context) => Scaffold(
          body: Center(
            child: ElevatedButton(
              onPressed: () async {
                exit = await Navigator.of(context)
                    .push<AssistantExit>(MaterialPageRoute(builder: (_) => AssistantScreen(sender: sender)));
              },
              child: const Text('open'),
            ),
          ),
        ),
      ),
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextField), 'big pothole near school gate');
    await tester.tap(find.byTooltip('Send'));
    await tester.pumpAndSettle();
    expect(find.text('Large pothole near the school gate.'), findsOneWidget); // the draft card
    expect(find.text('Worded by AI from the help articles below'), findsOneWidget);

    await tester.tap(find.text('Start report'));
    await tester.pumpAndSettle();

    expect(exit, AssistantExit.report);
    expect(ComplaintPrefill.take(), 'Large pothole near the school gate.');
  });

  testWidgets('too many messages shows a wait message and Try again resends', (tester) async {
    var calls = 0;
    Future<AssistantReply> sender(String text, {String? sessionId, required String language}) async {
      calls++;
      if (calls == 1) throw ApiException(status: 429, error: 'TOO_MANY_REQUESTS', message: 'slow down');
      return _statusReply();
    }

    await tester.pumpWidget(MaterialApp(home: AssistantScreen(sender: sender)));
    await tester.enterText(find.byType(TextField), 'where is my complaint');
    await tester.tap(find.byTooltip('Send'));
    await tester.pumpAndSettle();

    expect(find.text('You sent many messages. Please wait a minute and try again.'), findsOneWidget);
    await tester.tap(find.text('Try again'));
    await tester.pumpAndSettle();

    expect(calls, 2);
    expect(find.text('where is my complaint'), findsOneWidget); // the question is shown once
    expect(find.byType(StatusBadge), findsOneWidget);
  });

  test('ComplaintPrefill hands the draft over once', () {
    ComplaintPrefill.description.value = 'Garbage near the market.';
    expect(ComplaintPrefill.take(), 'Garbage near the market.');
    expect(ComplaintPrefill.take(), isNull);
  });
}
