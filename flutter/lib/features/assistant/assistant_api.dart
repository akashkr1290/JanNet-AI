import '../../core/api/api_client.dart';
import '../complaints/models/complaint_status.dart';

/// JanNet Sahayak v1 - client for POST /api/v1/assistant/messages
/// (backend AssistantController / AssistantService).
///
/// The backend decides everything: it answers status and "my complaints"
/// questions from the signed-in user's own complaints, FAQ questions from the
/// help articles, and only words open questions with Gemini. The app shows the
/// reply, its cards and its suggested actions.

/// A suggested next step shown as a chip under a reply.
class AssistantAction {
  static const ask = 'ASK';
  static const openComplaint = 'OPEN_COMPLAINT';
  static const openMyComplaints = 'OPEN_MY_COMPLAINTS';
  static const startReport = 'START_REPORT';

  /// Sahayak v2: open the list behind a tile of the user's own dashboard ([value] = the tile).
  static const openTile = 'OPEN_TILE';

  final String type;
  final String label;
  final String? value;

  const AssistantAction({required this.type, required this.label, this.value});

  factory AssistantAction.fromJson(Map<String, dynamic> json) => AssistantAction(
        type: json['type'] as String? ?? ask,
        label: json['label'] as String? ?? '',
        value: json['value'] as String?,
      );
}

/// Structured content under a reply: a complaint, a help article or a report draft.
class AssistantCard {
  static const complaint = 'COMPLAINT';
  static const article = 'ARTICLE';
  static const reportDraft = 'REPORT_DRAFT';

  final String type;
  final int? complaintId;
  final String? referenceNumber;
  final String? category;
  final ComplaintStatus? status;
  final String? departmentName;
  final bool isEscalated;
  final String? slug;
  final String? title;
  final String? body;
  final String? description;

  const AssistantCard({
    required this.type,
    this.complaintId,
    this.referenceNumber,
    this.category,
    this.status,
    this.departmentName,
    this.isEscalated = false,
    this.slug,
    this.title,
    this.body,
    this.description,
  });

  factory AssistantCard.fromJson(Map<String, dynamic> json) {
    final status = json['status'] as String?;
    return AssistantCard(
      type: json['type'] as String? ?? '',
      complaintId: (json['complaintId'] as num?)?.toInt(),
      referenceNumber: json['referenceNumber'] as String?,
      category: json['category'] as String?,
      status: status == null ? null : ComplaintStatus.fromJson(status),
      departmentName: json['departmentName'] as String?,
      isEscalated: json['isEscalated'] as bool? ?? false,
      slug: json['slug'] as String?,
      title: json['title'] as String?,
      body: json['body'] as String?,
      description: json['description'] as String?,
    );
  }
}

class AssistantReply {
  /// Send back with the next message to continue the same conversation.
  final String sessionId;
  final String reply;

  /// RULE, ARTICLE, AI (worded by Gemini from the help articles) or FALLBACK.
  final String source;
  final String intent;
  final List<AssistantCard> cards;
  final List<AssistantAction> actions;

  const AssistantReply({
    required this.sessionId,
    required this.reply,
    required this.source,
    required this.intent,
    this.cards = const [],
    this.actions = const [],
  });

  bool get wordedByAi => source == 'AI';

  factory AssistantReply.fromJson(Map<String, dynamic> json) => AssistantReply(
        sessionId: json['sessionId'] as String? ?? '',
        reply: json['reply'] as String? ?? '',
        source: json['source'] as String? ?? 'FALLBACK',
        intent: json['intent'] as String? ?? '',
        cards: ((json['cards'] as List?) ?? const [])
            .map((e) => AssistantCard.fromJson(e as Map<String, dynamic>))
            .toList(),
        actions: ((json['suggestedActions'] as List?) ?? const [])
            .map((e) => AssistantAction.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

/// Sends one message; injectable so widget tests need no network.
typedef AssistantSender = Future<AssistantReply> Function(String text, {String? sessionId, required String language});

class AssistantApi {
  AssistantApi._();
  static final AssistantApi instance = AssistantApi._();

  final _client = ApiClient.instance;

  /// POST /api/v1/assistant/messages. [language] is the app language (EN, HI, ...).
  Future<AssistantReply> send(String text, {String? sessionId, required String language}) async {
    final json = await _client.post('/assistant/messages', body: {
      if (sessionId != null && sessionId.isNotEmpty) 'sessionId': sessionId,
      'text': text,
      'language': language,
    }) as Map<String, dynamic>;
    return AssistantReply.fromJson(json);
  }
}
