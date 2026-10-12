import 'package:flutter/material.dart' hide Text;

import '../theme/jan_tokens.dart';
import 'jan_motion.dart';
import '../l10n/tr_text.dart';

/// UI phase 1: a compact step indicator with an animated progress bar.
/// [done] says which steps are complete; the bar animates to the share
/// completed (instantly under reduced motion). Fixed height, so it never
/// shifts the layout.
class JanStepProgress extends StatelessWidget {
  final List<String> labels;
  final List<bool> done;

  const JanStepProgress({super.key, required this.labels, required this.done});

  @override
  Widget build(BuildContext context) {
    final completed = done.where((d) => d).length;
    final value = labels.isEmpty ? 0.0 : completed / labels.length;
    final reduce = janReduceMotion(context);
    return Semantics(
      container: true,
      label: trs('Progress: $completed of ${labels.length} steps complete'),
      child: ExcludeSemantics(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(4),
              child: TweenAnimationBuilder<double>(
                tween: Tween(end: value),
                duration: reduce ? Duration.zero : const Duration(milliseconds: 380),
                curve: Curves.easeOutCubic,
                builder: (context, v, _) => LinearProgressIndicator(
                  value: v,
                  minHeight: 6,
                  backgroundColor: JanColors.skeleton,
                  valueColor: const AlwaysStoppedAnimation(JanColors.primary),
                ),
              ),
            ),
            const SizedBox(height: JanSpace.xs),
            Row(
              children: [
                for (var i = 0; i < labels.length; i++)
                  Expanded(
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        AnimatedSwitcher(
                          duration: reduce ? Duration.zero : const Duration(milliseconds: 220),
                          child: Icon(
                            done[i] ? Icons.check_circle_rounded : Icons.radio_button_unchecked_rounded,
                            key: ValueKey(done[i]),
                            size: 16,
                            color: done[i] ? JanColors.teal : JanColors.muted,
                          ),
                        ),
                        const SizedBox(width: 4),
                        Flexible(
                          child: Text(
                            labels[i],
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: 12.5,
                              fontWeight: done[i] ? FontWeight.w700 : FontWeight.w500,
                              color: done[i] ? JanColors.navy : JanColors.muted,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
