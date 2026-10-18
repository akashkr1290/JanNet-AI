import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/features/assistant/assistant_api.dart';
import 'package:jannet_ai/features/complaints/models/complaint_status.dart';

void main() {
  test('parses a status reply with its complaint card and actions', () {
    final r = AssistantReply.fromJson({
      'sessionId': '11111111-2222-3333-4444-555555555555',
      'reply': 'Complaint JN-2026-000101 (pothole) is now: In Progress.',
      'source': 'RULE',
      'intent': 'COMPLAINT_STATUS',
      'cards': [
        {
          'type': 'COMPLAINT',
          'complaintId': 101,
          'referenceNumber': 'JN-2026-000101',
          'category': 'POTHOLE',
          'status': 'IN_PROGRESS',
          'departmentName': 'Public Works Gaya',
          'isEscalated': false,
        }
      ],
      'suggestedActions': [
        {'type': 'OPEN_COMPLAINT', 'label': 'Open complaint', 'value': '101'}
      ],
    });
    expect(r.sessionId, '11111111-2222-3333-4444-555555555555');
    expect(r.wordedByAi, isFalse);
    expect(r.cards.single.status, ComplaintStatus.inProgress);
    expect(r.cards.single.complaintId, 101);
    expect(r.actions.single.type, AssistantAction.openComplaint);
    expect(r.actions.single.value, '101');
  });

  test('parses article and draft cards and tolerates missing fields', () {
    final r = AssistantReply.fromJson({
      'sessionId': 's',
      'reply': 'x',
      'source': 'AI',
      'intent': 'OPEN_QUESTION',
      'cards': [
        {'type': 'ARTICLE', 'slug': 'privacy', 'title': 'My personal data', 'body': 'In Settings...'},
        {'type': 'REPORT_DRAFT', 'category': 'GARBAGE_OVERFLOW', 'description': 'Garbage near the market.'},
      ],
      'suggestedActions': [
        {'type': 'START_REPORT', 'label': 'Start report', 'value': 'Garbage near the market.'},
        {'type': 'OPEN_MY_COMPLAINTS', 'label': 'My Complaints', 'value': null},
      ],
    });
    expect(r.wordedByAi, isTrue);
    expect(r.cards[0].slug, 'privacy');
    expect(r.cards[0].status, isNull);
    expect(r.cards[1].description, 'Garbage near the market.');
    expect(r.actions[1].value, isNull);

    final empty = AssistantReply.fromJson({});
    expect(empty.source, 'FALLBACK');
    expect(empty.cards, isEmpty);
    expect(empty.actions, isEmpty);
  });
}
