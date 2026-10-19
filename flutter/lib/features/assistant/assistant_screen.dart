import 'package:flutter/material.dart' hide Text;
import 'package:flutter/material.dart' as m show Text;

import '../../core/api/api_exception.dart';
import '../../core/l10n/app_strings.dart';
import '../../core/l10n/tr_text.dart';
import '../../core/theme/jan_tokens.dart';
import '../../core/widgets/error_text.dart';
import '../../core/widgets/jan_liquid.dart';
import '../complaints/complaint_prefill.dart';
import '../complaints/screens/complaint_detail_screen.dart';
import '../complaints/widgets/category_visuals.dart';
import '../complaints/widgets/status_badge.dart';
import 'assistant_api.dart';

/// Where the citizen wants to go after the chat (returned by [AssistantScreen]).
enum AssistantExit { report, myComplaints }

/// JanNet Sahayak v1 - the citizen's in-app assistant (opened from the
/// floating button on the citizen home, complaint_home_screen.dart).
///
/// Shows the backend's replies with their cards (complaint, help article,
/// report draft) and suggested-action chips. Replies are written by the
/// server in the app's language and shown exactly as received; the status on
/// a complaint card is the same badge as on the complaint screens. Nothing is
/// ever submitted from here: "Start report" only fills the Submit form.
class AssistantScreen extends StatefulWidget {
  /// Replaces the network call in tests.
  final AssistantSender? sender;

  /// True inside the small chat window ([open]): a compact header with a
  /// close button instead of a full-page app bar.
  final bool embedded;

  const AssistantScreen({super.key, this.sender, this.embedded = false});

  /// The drifting "liquid" background. Tests switch it off so frames settle.
  static bool liquidMotion = true;

  /// Opens the assistant as a small chat window in the bottom-right corner
  /// (on a phone: a window over the lower part of the screen). Tapping
  /// outside it, or its close button, closes it.
  static Future<AssistantExit?> open(BuildContext context, {AssistantSender? sender}) {
    return showGeneralDialog<AssistantExit>(
      context: context,
      barrierDismissible: true,
      barrierLabel: trs('Close'),
      barrierColor: Colors.black.withValues(alpha: 0.18),
      transitionDuration: const Duration(milliseconds: 200),
      pageBuilder: (context, _, __) {
        final size = MediaQuery.sizeOf(context);
        final phone = size.width < 600;
        return SafeArea(
          child: Align(
            alignment: Alignment.bottomRight,
            child: Padding(
              padding: EdgeInsets.all(phone ? JanSpace.xs : JanSpace.lg),
              child: SizedBox(
                width: phone ? size.width : 400,
                height: phone ? size.height * 0.82 : (size.height - 2 * JanSpace.lg).clamp(320.0, 620.0),
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    borderRadius: const BorderRadius.all(Radius.circular(28)),
                    boxShadow: [
                      BoxShadow(color: JanColors.navy.withValues(alpha: 0.28), blurRadius: 40, offset: const Offset(0, 18)),
                    ],
                  ),
                  child: JanGlass(
                    borderRadius: const BorderRadius.all(Radius.circular(28)),
                    opacity: 0.80,
                    child: Material(
                      type: MaterialType.transparency,
                      child: AssistantScreen(sender: sender, embedded: true),
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
      },
      transitionBuilder: (context, animation, _, child) {
        final curved = CurvedAnimation(parent: animation, curve: Curves.easeOutCubic);
        return FadeTransition(
          opacity: curved,
          child: ScaleTransition(scale: Tween(begin: 0.92, end: 1.0).animate(curved), alignment: Alignment.bottomRight, child: child),
        );
      },
    );
  }

  /// The starter questions. The same wording (and its translations) is what the
  /// backend recognises exactly (AssistantTexts CHIP_* in the backend).
  static const starterQuestions = [
    'Where is my complaint?',
    'How do I report a problem?',
    'Why was my complaint rejected?',
    'How long will it take?',
  ];

  @override
  State<AssistantScreen> createState() => _AssistantScreenState();
}

class _Entry {
  final String? userText;
  final AssistantReply? reply;
  const _Entry.user(this.userText) : reply = null;
  const _Entry.assistant(this.reply) : userText = null;
}

class _AssistantScreenState extends State<AssistantScreen> {
  final _input = TextEditingController();
  final _scroll = ScrollController();
  final List<_Entry> _entries = [];
  String? _sessionId;
  bool _busy = false;
  String? _error;
  String? _failedText;

  AssistantSender get _sender => widget.sender ?? AssistantApi.instance.send;

  @override
  void dispose() {
    _input.dispose();
    _scroll.dispose();
    super.dispose();
  }

  Future<void> _send(String text) async {
    final message = text.trim();
    if (message.isEmpty || _busy) return;
    setState(() {
      _entries.add(_Entry.user(message));
      _busy = true;
      _error = null;
      _failedText = null;
    });
    _input.clear();
    _scrollToEnd();
    try {
      final reply = await _sender(message, sessionId: _sessionId, language: AppStrings.currentLanguage.value);
      if (!mounted) return;
      setState(() {
        _sessionId = reply.sessionId.isEmpty ? _sessionId : reply.sessionId;
        _entries.add(_Entry.assistant(reply));
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.status == 429
            ? 'You sent many messages. Please wait a minute and try again.'
            : 'Could not reach the assistant. Check the connection and try again.';
        _failedText = message;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _error = 'Could not reach the assistant. Check the connection and try again.';
        _failedText = message;
      });
    } finally {
      if (mounted) setState(() => _busy = false);
      _scrollToEnd();
    }
  }

  void _retry() {
    final text = _failedText;
    if (text == null) return;
    setState(() {
      // the failed question is sent again, so drop its bubble first
      if (_entries.isNotEmpty && _entries.last.userText == text) _entries.removeLast();
      _error = null;
    });
    _send(text);
  }

  void _scrollToEnd() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scroll.hasClients) {
        _scroll.animateTo(_scroll.position.maxScrollExtent,
            duration: const Duration(milliseconds: 250), curve: Curves.easeOut);
      }
    });
  }

  void _onAction(AssistantAction a) {
    switch (a.type) {
      case AssistantAction.openComplaint:
        final id = int.tryParse(a.value ?? '');
        if (id != null) {
          Navigator.of(context).push(MaterialPageRoute(builder: (_) => ComplaintDetailScreen(complaintId: id)));
        }
      case AssistantAction.openMyComplaints:
        Navigator.of(context).pop(AssistantExit.myComplaints);
      case AssistantAction.startReport:
        final draft = a.value?.trim() ?? '';
        if (draft.isNotEmpty) ComplaintPrefill.description.value = draft;
        Navigator.of(context).pop(AssistantExit.report);
      default:
        _send(a.value ?? a.label);
    }
  }

  void _openComplaint(int id) =>
      Navigator.of(context).push(MaterialPageRoute(builder: (_) => ComplaintDetailScreen(complaintId: id)));

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: widget.embedded ? Colors.transparent : JanColors.background,
      appBar: widget.embedded
          ? AppBar(
              automaticallyImplyLeading: false,
              toolbarHeight: 60,
              backgroundColor: Colors.transparent,
              surfaceTintColor: Colors.transparent,
              foregroundColor: JanColors.white,
              titleSpacing: JanSpace.md,
              flexibleSpace: const DecoratedBox(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: [JanColors.navy, JanColors.primary, JanColors.teal],
                  ),
                ),
              ),
              title: Row(children: [
                Container(
                  width: 36,
                  height: 36,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: JanColors.white.withValues(alpha: 0.18),
                    border: Border.all(color: JanColors.white.withValues(alpha: 0.55)),
                    boxShadow: [BoxShadow(color: JanColors.electricGlow.withValues(alpha: 0.6), blurRadius: 14)],
                  ),
                  child: const Icon(Icons.support_agent_rounded, size: 20),
                ),
                const SizedBox(width: JanSpace.sm),
                const Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
                    Text('JanNet Sahayak', style: TextStyle(fontSize: 16.5, fontWeight: FontWeight.w800)),
                    Text('Ask about your complaints', style: TextStyle(fontSize: 12, color: Color(0xE6FFFFFF))),
                  ]),
                ),
              ]),
              actions: [
                IconButton(
                  tooltip: trs('Close'),
                  onPressed: () => Navigator.of(context).pop(),
                  icon: const Icon(Icons.close_rounded),
                ),
              ],
            )
          : AppBar(title: const Text('JanNet Sahayak')),
      body: JanLiquidBackdrop(
        animate: AssistantScreen.liquidMotion,
        child: SafeArea(
        child: Column(children: [
          Expanded(
            child: ListView(
              controller: _scroll,
              padding: const EdgeInsets.all(JanSpace.md),
              children: [
                _welcome(),
                for (final e in _entries) e.userText != null ? _userBubble(e.userText!) : _assistantBubble(e.reply!),
                if (_busy)
                  Semantics(
                    liveRegion: true,
                    label: trs('Sahayak is typing'),
                    excludeSemantics: true,
                    child: _bubble(alignEnd: false, child: const JanTypingDots()),
                  ),
                if (_error != null)
                  Padding(
                    padding: const EdgeInsets.only(top: JanSpace.sm),
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      ErrorText(_error!),
                      if (_failedText != null)
                        TextButton.icon(
                          onPressed: _busy ? null : _retry,
                          icon: const Icon(Icons.refresh_rounded),
                          label: const Text('Try again'),
                        ),
                    ]),
                  ),
              ],
            ),
          ),
          _composer(),
        ]),
        ),
      ),
    );
  }

  Widget _welcome() {
    return _bubble(
      alignEnd: false,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const Text(
          'Namaste! I am JanNet Sahayak. Ask me where your complaint is, how to report a problem, or anything about this app.',
          style: TextStyle(color: JanColors.ink),
        ),
        const SizedBox(height: JanSpace.sm),
        Wrap(spacing: JanSpace.xs, runSpacing: JanSpace.xs, children: [
          for (final q in AssistantScreen.starterQuestions)
            ActionChip(
              avatar: const Icon(Icons.chat_bubble_outline_rounded, size: 18, color: JanColors.primary),
              label: Text(q),
              backgroundColor: JanColors.white.withValues(alpha: 0.75),
              side: BorderSide(color: JanColors.primary.withValues(alpha: 0.25)),
              shape: const StadiumBorder(),
              onPressed: _busy ? null : () => _send(trs(q)),
            ),
        ]),
      ]),
    );
  }

  Widget _userBubble(String text) => _bubble(
        alignEnd: true,
        child: m.Text(text, style: const TextStyle(color: JanColors.white, height: 1.35)),
      );

  Widget _assistantBubble(AssistantReply r) {
    return _bubble(
      alignEnd: false,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        // Written by the server in the app's language - shown exactly as received.
        SelectableText(r.reply, style: const TextStyle(color: JanColors.ink, height: 1.35)),
        if (r.wordedByAi)
          const Padding(
            padding: EdgeInsets.only(top: JanSpace.xs),
            child: Text('Worded by AI from the help articles below',
                style: TextStyle(color: JanColors.muted, fontSize: 12.5)),
          ),
        for (final c in r.cards) _card(c),
        if (r.actions.isNotEmpty) ...[
          const SizedBox(height: JanSpace.sm),
          Wrap(spacing: JanSpace.xs, runSpacing: JanSpace.xs, children: [
            for (final a in r.actions)
              ActionChip(
                avatar: Icon(_actionIcon(a.type), size: 18,
                    color: a.type == AssistantAction.startReport ? JanColors.white : JanColors.primary),
                label: m.Text(a.label,
                    style: TextStyle(
                        color: a.type == AssistantAction.startReport ? JanColors.white : JanColors.navy,
                        fontWeight: FontWeight.w600)),
                backgroundColor:
                    a.type == AssistantAction.startReport ? JanColors.teal : JanColors.white.withValues(alpha: 0.8),
                side: BorderSide(color: JanColors.primary.withValues(alpha: 0.25)),
                shape: const StadiumBorder(),
                onPressed: _busy && a.type == AssistantAction.ask ? null : () => _onAction(a),
              ),
          ]),
        ],
      ]),
    );
  }

  IconData _actionIcon(String type) {
    switch (type) {
      case AssistantAction.openComplaint:
        return Icons.open_in_new_rounded;
      case AssistantAction.openMyComplaints:
        return Icons.list_alt_rounded;
      case AssistantAction.startReport:
        return Icons.add_a_photo_outlined;
      default:
        return Icons.chat_bubble_outline_rounded;
    }
  }

  Widget _card(AssistantCard c) {
    switch (c.type) {
      case AssistantCard.complaint:
        final complaintCategory = CategoryVisual.of(c.category);
        return Card(
          margin: const EdgeInsets.only(top: JanSpace.sm),
          child: ListTile(
            leading: Icon(complaintCategory.icon, color: JanColors.navy),
            title: m.Text(c.referenceNumber ?? '', style: const TextStyle(fontWeight: FontWeight.w700)),
            subtitle: m.Text([
              trs(complaintCategory.label),
              if (c.departmentName != null) c.departmentName!,
              if (c.isEscalated) trs('Escalated'),
            ].join(' · ')),
            trailing: c.status == null ? null : StatusBadge(status: c.status!),
            onTap: c.complaintId == null ? null : () => _openComplaint(c.complaintId!),
          ),
        );
      case AssistantCard.article:
        return Card(
          margin: const EdgeInsets.only(top: JanSpace.sm),
          child: ExpansionTile(
            leading: const Icon(Icons.menu_book_outlined, color: JanColors.teal),
            title: m.Text(c.title ?? '', style: const TextStyle(fontWeight: FontWeight.w600)),
            subtitle: const Text('Help article', style: TextStyle(color: JanColors.muted, fontSize: 12.5)),
            childrenPadding: const EdgeInsets.fromLTRB(JanSpace.md, 0, JanSpace.md, JanSpace.md),
            children: [m.Text(c.body ?? '')],
          ),
        );
      case AssistantCard.reportDraft:
        final draftCategory = CategoryVisual.of(c.category);
        return Card(
          margin: const EdgeInsets.only(top: JanSpace.sm),
          color: JanColors.tealLight,
          child: Padding(
            padding: const EdgeInsets.all(JanSpace.md),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                Icon(draftCategory.icon, color: JanColors.teal),
                const SizedBox(width: JanSpace.xs),
                Expanded(
                  child: m.Text('${trs('Report draft')}: ${trs(draftCategory.label)}',
                      style: const TextStyle(fontWeight: FontWeight.w700, color: JanColors.ink)),
                ),
              ]),
              const SizedBox(height: JanSpace.xs),
              m.Text(c.description ?? '', style: const TextStyle(color: JanColors.ink)),
              const SizedBox(height: JanSpace.xs),
              const Text('You still take the photo, confirm the place and press Submit.',
                  style: TextStyle(color: JanColors.muted, fontSize: 12.5)),
            ]),
          ),
        );
      default:
        return const SizedBox.shrink();
    }
  }

  /// Chat bubble: the user's in a glossy blue gradient, the assistant's as
  /// frosted glass, with a tail corner towards the speaker.
  Widget _bubble({required bool alignEnd, required Widget child}) {
    const r = Radius.circular(20);
    const tail = Radius.circular(6);
    final radius = BorderRadius.only(
      topLeft: r,
      topRight: r,
      bottomLeft: alignEnd ? r : tail,
      bottomRight: alignEnd ? tail : r,
    );
    return Align(
      alignment: alignEnd ? Alignment.centerRight : Alignment.centerLeft,
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 560),
        child: Container(
          margin: const EdgeInsets.symmetric(vertical: JanSpace.xs),
          padding: const EdgeInsets.all(JanSpace.sm + 2),
          decoration: BoxDecoration(
            borderRadius: radius,
            gradient: alignEnd
                ? const LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: [JanColors.primary, JanColors.navy],
                  )
                : LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: [JanColors.white.withValues(alpha: 0.92), JanColors.white.withValues(alpha: 0.74)],
                  ),
            border: Border.all(color: alignEnd ? Colors.transparent : JanColors.white.withValues(alpha: 0.9)),
            boxShadow: [
              BoxShadow(
                color: (alignEnd ? JanColors.primary : JanColors.navy).withValues(alpha: alignEnd ? 0.28 : 0.08),
                blurRadius: 18,
                offset: const Offset(0, 6),
              ),
            ],
          ),
          child: child,
        ),
      ),
    );
  }

  Widget _composer() {
    return JanGlass(
      borderRadius: BorderRadius.zero,
      blur: 16,
      opacity: 0.86,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.sm, JanSpace.sm, JanSpace.xs),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Expanded(
              child: TextField(
                controller: _input,
                maxLength: 500,
                minLines: 1,
                maxLines: 4,
                textInputAction: TextInputAction.send,
                onSubmitted: _send,
                decoration: InputDecoration(
                  hintText: trs('Type your question'),
                  counterText: '',
                  filled: true,
                  fillColor: JanColors.white.withValues(alpha: 0.9),
                  contentPadding: const EdgeInsets.symmetric(horizontal: 18, vertical: 12),
                  border: OutlineInputBorder(
                    borderRadius: const BorderRadius.all(Radius.circular(26)),
                    borderSide: BorderSide(color: JanColors.primary.withValues(alpha: 0.2)),
                  ),
                  enabledBorder: OutlineInputBorder(
                    borderRadius: const BorderRadius.all(Radius.circular(26)),
                    borderSide: BorderSide(color: JanColors.primary.withValues(alpha: 0.2)),
                  ),
                  focusedBorder: const OutlineInputBorder(
                    borderRadius: BorderRadius.all(Radius.circular(26)),
                    borderSide: BorderSide(color: JanColors.primary, width: 1.6),
                  ),
                ),
              ),
            ),
            const SizedBox(width: JanSpace.xs),
            DecoratedBox(
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                gradient: const LinearGradient(colors: [JanColors.primary, JanColors.teal]),
                boxShadow: [BoxShadow(color: JanColors.primary.withValues(alpha: 0.4), blurRadius: 14, offset: const Offset(0, 4))],
              ),
              child: IconButton(
                tooltip: trs('Send'),
                onPressed: _busy ? null : () => _send(_input.text),
                icon: const Icon(Icons.send_rounded, color: JanColors.white),
              ),
            ),
          ]),
          const Text('Do not type your phone number or home address here.',
              style: TextStyle(color: JanColors.muted, fontSize: 12)),
        ]),
      ),
    );
  }
}
