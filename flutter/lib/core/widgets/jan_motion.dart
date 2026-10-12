import 'dart:math' as math;
import 'dart:ui' show ImageFilter;

import 'package:flutter/material.dart';

import '../theme/jan_tokens.dart';
import '../l10n/tr_text.dart';

/// UI phase 1: a small, dependency-free motion kit. Every piece
///  * honours the OS "reduce motion" setting (it then shows the final state at once),
///  * animates only opacity / transform / paint, so nothing shifts the layout,
///  * stays out of the way of essential actions (nothing blocks taps).

/// True when the user asked the system for less motion.
bool janReduceMotion(BuildContext context) => MediaQuery.maybeDisableAnimationsOf(context) ?? false;

/// Fades and slides [child] up once when it first appears. Give a growing
/// [delay] to successive items for a gentle staggered entrance. Uses a single
/// controller (no timers), so widget tests settle normally.
class JanReveal extends StatefulWidget {
  final Widget child;
  final Duration delay;
  final double dy;

  const JanReveal({super.key, required this.child, this.delay = Duration.zero, this.dy = 16});

  /// Stagger helper: item [index] enters [step] after the previous one (capped so long lists do not wait).
  static Duration stagger(int index, {Duration step = const Duration(milliseconds: 70), int cap = 8}) =>
      step * math.min(index, cap);

  @override
  State<JanReveal> createState() => _JanRevealState();
}

class _JanRevealState extends State<JanReveal> with SingleTickerProviderStateMixin {
  static const _anim = Duration(milliseconds: 460);
  late final AnimationController _controller =
      AnimationController(vsync: this, duration: _anim + widget.delay);
  late final Animation<double> _t = CurvedAnimation(
    parent: _controller,
    curve: Interval(
      widget.delay.inMilliseconds / (_anim + widget.delay).inMilliseconds,
      1,
      curve: Curves.easeOutCubic,
    ),
  );
  bool _started = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_started) return;
    _started = true;
    if (janReduceMotion(context)) {
      _controller.value = 1;
    } else {
      _controller.forward();
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _t,
      child: widget.child,
      builder: (context, child) => Opacity(
        opacity: _t.value.clamp(0.0, 1.0),
        child: Transform.translate(offset: Offset(0, (1 - _t.value) * widget.dy), child: child),
      ),
    );
  }
}

/// Gives any tappable child a soft press (slight shrink) and hover (slight grow) response.
class JanPressable extends StatefulWidget {
  final Widget child;
  final VoidCallback? onTap;
  final String? semanticLabel;
  final BorderRadius borderRadius;

  const JanPressable({
    super.key,
    required this.child,
    this.onTap,
    this.semanticLabel,
    this.borderRadius = JanRadius.mdAll,
  });

  @override
  State<JanPressable> createState() => _JanPressableState();
}

class _JanPressableState extends State<JanPressable> {
  bool _pressed = false;
  bool _hover = false;

  @override
  Widget build(BuildContext context) {
    final reduce = janReduceMotion(context);
    final scale = reduce ? 1.0 : (_pressed ? 0.97 : (_hover ? 1.015 : 1.0));
    return Semantics(
      button: widget.onTap != null,
      label: trsN(widget.semanticLabel),
      child: MouseRegion(
        cursor: widget.onTap != null ? SystemMouseCursors.click : MouseCursor.defer,
        onEnter: (_) => setState(() => _hover = true),
        onExit: (_) => setState(() => _hover = false),
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTapDown: (_) => setState(() => _pressed = true),
          onTapCancel: () => setState(() => _pressed = false),
          onTapUp: (_) => setState(() => _pressed = false),
          onTap: widget.onTap,
          child: AnimatedScale(
            scale: scale,
            duration: const Duration(milliseconds: 120),
            curve: Curves.easeOut,
            child: widget.child,
          ),
        ),
      ),
    );
  }
}

/// A card that lifts a few pixels, gets an accent border and a soft electric
/// shadow on hover or keyboard focus, and presses in on tap.
class JanLiftCard extends StatefulWidget {
  final Widget child;
  final VoidCallback? onTap;
  final String? semanticLabel;
  final EdgeInsetsGeometry padding;
  final BorderRadius borderRadius;
  final Color accent;

  const JanLiftCard({
    super.key,
    required this.child,
    this.onTap,
    this.semanticLabel,
    this.padding = const EdgeInsets.all(JanSpace.md),
    this.borderRadius = JanRadius.lgAll,
    this.accent = JanColors.primary,
  });

  @override
  State<JanLiftCard> createState() => _JanLiftCardState();
}

class _JanLiftCardState extends State<JanLiftCard> {
  bool _hover = false;
  bool _focus = false;

  @override
  Widget build(BuildContext context) {
    final reduce = janReduceMotion(context);
    final active = _hover || _focus;
    return Semantics(
      button: widget.onTap != null,
      label: trsN(widget.semanticLabel),
      child: MouseRegion(
        cursor: widget.onTap != null ? SystemMouseCursors.click : MouseCursor.defer,
        onEnter: (_) => setState(() => _hover = true),
        onExit: (_) => setState(() => _hover = false),
        child: AnimatedContainer(
          duration: reduce ? Duration.zero : const Duration(milliseconds: 200),
          curve: Curves.easeOutCubic,
          transform: Matrix4.translationValues(0, active && !reduce ? -5 : 0, 0),
          decoration: BoxDecoration(
            color: JanColors.white,
            borderRadius: widget.borderRadius,
            border: Border.all(color: active ? widget.accent : JanColors.divider, width: active ? 1.6 : 1),
            boxShadow: active ? JanShadows.lifted : JanShadows.card,
          ),
          child: Material(
            type: MaterialType.transparency,
            borderRadius: widget.borderRadius,
            clipBehavior: Clip.antiAlias,
            child: InkWell(
              onTap: widget.onTap,
              onFocusChange: (v) => setState(() => _focus = v),
              borderRadius: widget.borderRadius,
              child: Padding(padding: widget.padding, child: widget.child),
            ),
          ),
        ),
      ),
    );
  }
}

/// A frosted-glass panel. Use over a coloured or illustrated backdrop only,
/// and on a few surfaces (hero, floating map labels) - never behind long text.
class JanGlass extends StatelessWidget {
  final Widget child;
  final EdgeInsetsGeometry padding;
  final BorderRadius borderRadius;
  final double blur;
  final double opacity;

  const JanGlass({
    super.key,
    required this.child,
    this.padding = const EdgeInsets.all(JanSpace.md),
    this.borderRadius = JanRadius.lgAll,
    this.blur = 14,
    this.opacity = 0.62,
  });

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: borderRadius,
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: blur, sigmaY: blur),
        child: Container(
          padding: padding,
          decoration: BoxDecoration(
            color: JanColors.white.withValues(alpha: opacity),
            borderRadius: borderRadius,
            border: Border.all(color: JanColors.white.withValues(alpha: 0.75)),
          ),
          child: child,
        ),
      ),
    );
  }
}

/// A few translucent bubbles drifting slowly behind the hero. Decorative only:
/// ignored by taps and screen readers, static under "reduce motion".
class JanBubbles extends StatefulWidget {
  const JanBubbles({super.key});

  @override
  State<JanBubbles> createState() => _JanBubblesState();
}

class _JanBubblesState extends State<JanBubbles> with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(vsync: this, duration: const Duration(seconds: 14));
  bool _started = false;

  // alignment x, alignment y, diameter, drift phase
  static const _bubbles = <List<double>>[
    [-0.92, -0.70, 120, 0.0],
    [0.88, -0.50, 70, 1.7],
    [0.62, 0.78, 150, 3.1],
    [-0.55, 0.62, 56, 4.4],
    [0.05, -0.92, 36, 5.6],
  ];

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_started) return;
    _started = true;
    if (!janReduceMotion(context)) {
      _controller.repeat();
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ExcludeSemantics(
      child: IgnorePointer(
        child: RepaintBoundary(
          child: AnimatedBuilder(
            animation: _controller,
            builder: (context, _) {
              final t = _controller.value * 2 * math.pi;
              return Stack(
                children: [
                  for (final b in _bubbles)
                    Align(
                      alignment: Alignment(b[0], b[1]),
                      child: Transform.translate(
                        offset: Offset(math.sin(t + b[3]) * 8, math.cos(t + b[3]) * 10),
                        child: Container(
                          width: b[2],
                          height: b[2],
                          decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            gradient: RadialGradient(
                              center: const Alignment(-0.4, -0.4),
                              colors: [
                                JanColors.white.withValues(alpha: 0.85),
                                JanColors.electricGlow.withValues(alpha: 0.22),
                                JanColors.electricGlow.withValues(alpha: 0.06),
                              ],
                              stops: const [0.0, 0.55, 1.0],
                            ),
                            border: Border.all(color: JanColors.white.withValues(alpha: 0.6)),
                          ),
                        ),
                      ),
                    ),
                ],
              );
            },
          ),
        ),
      ),
    );
  }
}

/// Soft pulsing rings behind [child] - the "you are here" location pulse.
class JanPulse extends StatefulWidget {
  final Widget child;
  final Color color;
  final double size;

  const JanPulse({super.key, required this.child, this.color = JanColors.primary, this.size = 64});

  @override
  State<JanPulse> createState() => _JanPulseState();
}

class _JanPulseState extends State<JanPulse> with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(vsync: this, duration: const Duration(milliseconds: 2200));
  bool _started = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_started) return;
    _started = true;
    if (!janReduceMotion(context)) {
      _controller.repeat();
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: widget.size,
      height: widget.size,
      child: Stack(
        alignment: Alignment.center,
        children: [
          ExcludeSemantics(
            child: AnimatedBuilder(
              animation: _controller,
              builder: (context, _) {
                final p = _controller.value;
                return CustomPaint(
                  size: Size.square(widget.size),
                  painter: _PulsePainter(progress: p, color: widget.color, still: !_controller.isAnimating),
                );
              },
            ),
          ),
          widget.child,
        ],
      ),
    );
  }
}

class _PulsePainter extends CustomPainter {
  final double progress;
  final Color color;
  final bool still;

  _PulsePainter({required this.progress, required this.color, required this.still});

  @override
  void paint(Canvas canvas, Size size) {
    final c = size.center(Offset.zero);
    final maxR = size.shortestSide / 2;
    if (still) {
      canvas.drawCircle(c, maxR * 0.7, Paint()..color = color.withValues(alpha: 0.16));
      return;
    }
    for (final offset in const [0.0, 0.5]) {
      final p = (progress + offset) % 1.0;
      canvas.drawCircle(c, maxR * (0.35 + 0.65 * p), Paint()..color = color.withValues(alpha: 0.28 * (1 - p)));
    }
  }

  @override
  bool shouldRepaint(covariant _PulsePainter old) => old.progress != progress || old.color != color || old.still != still;
}

/// The "report submitted" confirmation: a teal disc pops in and a check mark
/// draws itself (about 0.7 s, once). Under "reduce motion" it shows the end state.
class JanSuccessMark extends StatelessWidget {
  final double size;

  const JanSuccessMark({super.key, this.size = 76});

  @override
  Widget build(BuildContext context) {
    final reduce = janReduceMotion(context);
    return Semantics(
      label: trs('Success'),
      excludeSemantics: true,
      child: TweenAnimationBuilder<double>(
        tween: Tween(begin: reduce ? 1.0 : 0.0, end: 1.0),
        duration: reduce ? Duration.zero : const Duration(milliseconds: 750),
        curve: Curves.easeOutCubic,
        builder: (context, t, _) => CustomPaint(size: Size.square(size), painter: _SuccessPainter(t)),
      ),
    );
  }
}

class _SuccessPainter extends CustomPainter {
  final double t;
  _SuccessPainter(this.t);

  @override
  void paint(Canvas canvas, Size size) {
    final c = size.center(Offset.zero);
    final r = size.shortestSide / 2;
    // ring that expands and fades while the disc settles
    final ringT = Curves.easeOut.transform(t.clamp(0.0, 1.0));
    canvas.drawCircle(c, r * (0.9 + 0.35 * ringT), Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 3
      ..color = JanColors.teal.withValues(alpha: 0.35 * (1 - ringT)));
    // disc: pops in during the first 45 %
    final discT = Curves.easeOutBack.transform((t / 0.45).clamp(0.0, 1.0));
    canvas.drawCircle(c, r * 0.9 * discT, Paint()..color = JanColors.teal);
    // check mark: draws between 40 % and 100 %
    final checkT = ((t - 0.4) / 0.6).clamp(0.0, 1.0);
    if (checkT > 0) {
      final path = Path()
        ..moveTo(size.width * 0.29, size.height * 0.52)
        ..lineTo(size.width * 0.44, size.height * 0.66)
        ..lineTo(size.width * 0.72, size.height * 0.36);
      final metric = path.computeMetrics().first;
      canvas.drawPath(
        metric.extractPath(0, metric.length * Curves.easeOut.transform(checkT)),
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = size.width * 0.075
          ..strokeCap = StrokeCap.round
          ..strokeJoin = StrokeJoin.round
          ..color = JanColors.white,
      );
    }
  }

  @override
  bool shouldRepaint(covariant _SuccessPainter old) => old.t != t;
}
