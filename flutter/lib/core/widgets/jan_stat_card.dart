import 'package:flutter/material.dart' hide Text;

import '../theme/jan_tokens.dart';
import 'jan_motion.dart';
import '../l10n/tr_text.dart';

/// Colour tones for statistic cards (reference: navy "Total", amber
/// "Pending", blue "In Progress", teal "Resolved"). Every tone pairs its
/// background with a text colour at >= 4.5:1 contrast.
enum JanTone { navy, amber, blue, teal, slate, light }

extension JanToneColors on JanTone {
  Color get background => switch (this) {
        JanTone.navy => JanColors.navy,
        JanTone.amber => JanColors.amber,
        JanTone.blue => JanColors.primary,
        JanTone.teal => JanColors.teal,
        JanTone.slate => JanColors.slate,
        JanTone.light => JanColors.white,
      };

  Color get foreground => switch (this) {
        JanTone.amber => JanColors.navy,
        JanTone.light => JanColors.navy,
        _ => JanColors.white,
      };
}

/// Filled statistic card: icon, large value, label. Read by screen readers
/// as one phrase ("Pending: 8").
///
/// "Liquid" look: a soft gradient of the tone with a glossy highlight and a
/// floating colour drop. A card with [onTap] (it opens the complaints or the
/// explanation behind the number) lifts on hover, sinks when pressed and
/// shows an arrow. The gradient only darkens dark tones (and lightens amber /
/// light), so the text keeps at least its original contrast.
class JanStatCard extends StatefulWidget {
  final String label;
  final String value;
  final IconData icon;
  final JanTone tone;
  final String? caption;
  final VoidCallback? onTap;

  const JanStatCard({
    super.key,
    required this.label,
    required this.value,
    required this.icon,
    this.tone = JanTone.navy,
    this.caption,
    this.onTap,
  });

  @override
  State<JanStatCard> createState() => _JanStatCardState();
}

class _JanStatCardState extends State<JanStatCard> {
  bool _hover = false;
  bool _pressed = false;

  List<Color> get _gradient {
    final bg = widget.tone.background;
    return switch (widget.tone) {
      JanTone.light => [JanColors.white, const Color(0xFFEEF4FB)],
      JanTone.amber => [Color.lerp(bg, JanColors.white, 0.18)!, bg],
      _ => [bg, Color.lerp(bg, const Color(0xFF000000), 0.18)!],
    };
  }

  @override
  Widget build(BuildContext context) {
    final fg = widget.tone.foreground;
    final light = widget.tone == JanTone.light;
    final tappable = widget.onTap != null;
    final card = AnimatedContainer(
      duration: const Duration(milliseconds: 180),
      constraints: const BoxConstraints(minHeight: 112),
      decoration: BoxDecoration(
        gradient: LinearGradient(begin: Alignment.topLeft, end: Alignment.bottomRight, colors: _gradient),
        borderRadius: JanRadius.lgAll,
        border: light ? Border.all(color: JanColors.divider) : null,
        boxShadow: [
          ...JanShadows.card,
          if (_hover && tappable)
            BoxShadow(
              color: (light ? JanColors.primary : widget.tone.background).withValues(alpha: 0.35),
              blurRadius: 22,
              offset: const Offset(0, 10),
            ),
        ],
      ),
      child: ClipRRect(
        borderRadius: JanRadius.lgAll,
        child: Stack(children: [
          // floating colour drop
          AnimatedPositioned(
            duration: const Duration(milliseconds: 400),
            curve: Curves.easeOutCubic,
            right: _hover ? -10 : -26,
            top: _hover ? -14 : -30,
            child: IgnorePointer(
              child: Container(
                width: 96,
                height: 96,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  gradient: RadialGradient(colors: [
                    (light ? JanColors.primary : JanColors.white).withValues(alpha: light ? 0.10 : 0.22),
                    (light ? JanColors.primary : JanColors.white).withValues(alpha: 0),
                  ]),
                ),
              ),
            ),
          ),
          // glossy highlight
          Positioned(
            left: 0,
            right: 0,
            top: 0,
            height: 40,
            child: IgnorePointer(
              child: DecoratedBox(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                    colors: [JanColors.white.withValues(alpha: light ? 0.0 : 0.14), JanColors.white.withValues(alpha: 0)],
                  ),
                ),
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.all(JanSpace.md),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Row(children: [
                  Icon(widget.icon, color: fg, size: 26),
                  const Spacer(),
                  if (tappable)
                    AnimatedSlide(
                      duration: const Duration(milliseconds: 180),
                      offset: Offset(_hover ? 0.15 : 0, 0),
                      child: Icon(Icons.arrow_forward_rounded, color: fg.withValues(alpha: 0.85), size: 18),
                    ),
                ]),
                const SizedBox(height: JanSpace.sm),
                FittedBox(
                  fit: BoxFit.scaleDown,
                  alignment: Alignment.centerLeft,
                  child: Text(
                    widget.value,
                    style: TextStyle(fontSize: 30, fontWeight: FontWeight.w800, color: fg, height: 1.05),
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  widget.label,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(fontSize: 13.5, fontWeight: FontWeight.w600, color: fg.withValues(alpha: 0.92)),
                ),
                if (widget.caption != null)
                  Text(widget.caption!, style: TextStyle(fontSize: 12, color: fg.withValues(alpha: 0.85))),
              ],
            ),
          ),
        ]),
      ),
    );
    return Semantics(
      label: '${trs(widget.label)}: ${widget.value}${widget.caption != null ? ', ${widget.caption}' : ''}',
      button: tappable,
      excludeSemantics: true,
      child: !tappable
          ? card
          : MouseRegion(
              cursor: SystemMouseCursors.click,
              onEnter: (_) => setState(() => _hover = true),
              onExit: (_) => setState(() => _hover = false),
              child: AnimatedScale(
                duration: const Duration(milliseconds: 140),
                scale: _pressed ? 0.97 : (_hover ? 1.02 : 1.0),
                child: Material(
                  type: MaterialType.transparency,
                  child: InkWell(
                    onTap: widget.onTap,
                    onHighlightChanged: (v) => setState(() => _pressed = v),
                    borderRadius: JanRadius.lgAll,
                    child: card,
                  ),
                ),
              ),
            ),
    );
  }
}

/// Lays stat cards out as a responsive grid: 2 columns on phones, up to
/// [maxColumns] on wider screens.
class JanStatGrid extends StatelessWidget {
  final List<Widget> children;
  final int maxColumns;

  const JanStatGrid({super.key, required this.children, this.maxColumns = 4});

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, constraints) {
      final width = constraints.maxWidth;
      final columns = (width >= 900 ? maxColumns : (width >= 560 ? 3 : 2)).clamp(1, maxColumns);
      const gap = JanSpace.sm;
      // Floored so rounding never pushes the last card onto a new row.
      final itemWidth = ((width - gap * (columns - 1)) / columns).floorToDouble();
      return Wrap(
        spacing: gap,
        runSpacing: gap,
        children: [
          for (var i = 0; i < children.length; i++)
            SizedBox(
              width: itemWidth,
              child: JanReveal(delay: JanReveal.stagger(i, step: const Duration(milliseconds: 60)), child: children[i]),
            ),
        ],
      );
    });
  }
}

/// A tile that is a rate or a measure (no list behind it) explains itself on
/// tap: what the number means and how it is worked out.
Future<void> showJanStatInfo(BuildContext context,
    {required String title, required String value, required String explanation, IconData icon = Icons.insights_rounded}) {
  return showModalBottomSheet<void>(
    context: context,
    showDragHandle: true,
    builder: (context) => SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(JanSpace.lg, 0, JanSpace.lg, JanSpace.lg),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Icon(icon, color: JanColors.primary),
            const SizedBox(width: JanSpace.xs),
            Expanded(child: Text(title, style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: JanColors.navy))),
          ]),
          const SizedBox(height: JanSpace.sm),
          Text(value, style: const TextStyle(fontSize: 34, fontWeight: FontWeight.w800, color: JanColors.ink)),
          const SizedBox(height: JanSpace.xs),
          Text(explanation, style: const TextStyle(color: JanColors.slate, height: 1.4)),
        ]),
      ),
    ),
  );
}
