import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/material.dart' hide Text;

import '../l10n/tr_text.dart';
import '../theme/jan_tokens.dart';

/// "Liquid glass" building blocks: frosted surfaces over slowly drifting
/// colour blobs. Used by the JanNet Sahayak chat window and its button.
///
/// Motion follows the device setting: with "reduce motion" on (or [animate]
/// false) the blobs stand still.

/// Soft colour blobs that drift behind [child].
class JanLiquidBackdrop extends StatefulWidget {
  final Widget child;
  final bool animate;

  const JanLiquidBackdrop({super.key, required this.child, this.animate = true});

  @override
  State<JanLiquidBackdrop> createState() => _JanLiquidBackdropState();
}

class _JanLiquidBackdropState extends State<JanLiquidBackdrop> with SingleTickerProviderStateMixin {
  late final AnimationController _controller =
      AnimationController(vsync: this, duration: const Duration(seconds: 18));

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _sync();
  }

  @override
  void didUpdateWidget(JanLiquidBackdrop old) {
    super.didUpdateWidget(old);
    _sync();
  }

  void _sync() {
    final move = widget.animate && !MediaQuery.disableAnimationsOf(context);
    if (move && !_controller.isAnimating) {
      _controller.repeat();
    } else if (!move && _controller.isAnimating) {
      _controller.stop();
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Stack(fit: StackFit.passthrough, children: [
      Positioned.fill(
        child: IgnorePointer(
          child: RepaintBoundary(
            child: AnimatedBuilder(
              animation: _controller,
              builder: (context, _) => CustomPaint(painter: _BlobPainter(_controller.value)),
            ),
          ),
        ),
      ),
      widget.child,
    ]);
  }
}

class _BlobPainter extends CustomPainter {
  final double t;
  _BlobPainter(this.t);

  static const _blobs = [
    (Color(0xFF7AA7FF), 0.15, 0.20, 0.55, 0.0),
    (Color(0xFF38A9A2), 0.85, 0.35, 0.50, 2.1),
    (Color(0xFFF3A83F), 0.30, 0.85, 0.42, 4.2),
  ];

  @override
  void paint(Canvas canvas, Size size) {
    final a = t * 2 * math.pi;
    for (final (color, x, y, r, phase) in _blobs) {
      final center = Offset(
        size.width * (x + 0.08 * math.sin(a + phase)),
        size.height * (y + 0.06 * math.cos(a * 1.3 + phase)),
      );
      final radius = size.shortestSide * r * (1 + 0.08 * math.sin(a * 2 + phase));
      final paint = Paint()
        ..shader = ui.Gradient.radial(center, radius, [color.withValues(alpha: 0.28), color.withValues(alpha: 0.0)]);
      canvas.drawCircle(center, radius, paint);
    }
  }

  @override
  bool shouldRepaint(_BlobPainter old) => old.t != t;
}

/// Frosted glass: blurs what is behind it, with a light sheen and edge.
class JanGlass extends StatelessWidget {
  final Widget child;
  final BorderRadius borderRadius;
  final double blur;
  final double opacity;
  final EdgeInsetsGeometry? padding;

  const JanGlass({
    super.key,
    required this.child,
    this.borderRadius = const BorderRadius.all(Radius.circular(24)),
    this.blur = 22,
    this.opacity = 0.78,
    this.padding,
  });

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: borderRadius,
      child: BackdropFilter(
        filter: ui.ImageFilter.blur(sigmaX: blur, sigmaY: blur),
        child: DecoratedBox(
          decoration: BoxDecoration(
            borderRadius: borderRadius,
            gradient: LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: [
                JanColors.white.withValues(alpha: opacity),
                JanColors.white.withValues(alpha: opacity - 0.14),
              ],
            ),
            border: Border.all(color: JanColors.white.withValues(alpha: 0.7), width: 1.2),
          ),
          child: padding == null ? child : Padding(padding: padding!, child: child),
        ),
      ),
    );
  }
}

/// Three dots that rise and fall like drops while the assistant is answering.
class JanTypingDots extends StatefulWidget {
  final Color color;
  const JanTypingDots({super.key, this.color = JanColors.primary});

  @override
  State<JanTypingDots> createState() => _JanTypingDotsState();
}

class _JanTypingDotsState extends State<JanTypingDots> with SingleTickerProviderStateMixin {
  late final AnimationController _controller =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 1100))..repeat();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final still = MediaQuery.disableAnimationsOf(context);
    return AnimatedBuilder(
      animation: _controller,
      builder: (context, _) => Row(mainAxisSize: MainAxisSize.min, children: [
        for (var i = 0; i < 3; i++)
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 3),
            child: Transform.translate(
              offset: Offset(0, still ? 0 : -5 * math.max(0, math.sin((_controller.value * 2 * math.pi) - i * 0.9))),
              child: Container(
                width: 9,
                height: 9,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  gradient: LinearGradient(colors: [widget.color, widget.color.withValues(alpha: 0.55)]),
                ),
              ),
            ),
          ),
      ]),
    );
  }
}

/// Glossy gradient pill with a soft glow - the "Ask Sahayak" button.
class JanLiquidButton extends StatelessWidget {
  final IconData icon;
  final String label;
  final String tooltip;
  final VoidCallback onPressed;

  const JanLiquidButton({
    super.key,
    required this.icon,
    required this.label,
    required this.tooltip,
    required this.onPressed,
  });

  @override
  Widget build(BuildContext context) {
    const radius = BorderRadius.all(Radius.circular(30));
    return Tooltip(
      message: trs(tooltip),
      child: Semantics(
        button: true,
        label: trs(tooltip),
        excludeSemantics: true,
        child: DecoratedBox(
          decoration: BoxDecoration(
            borderRadius: radius,
            boxShadow: [
              BoxShadow(color: JanColors.primary.withValues(alpha: 0.45), blurRadius: 22, offset: const Offset(0, 8)),
              BoxShadow(color: JanColors.tealBrand.withValues(alpha: 0.25), blurRadius: 30, offset: const Offset(0, 2)),
            ],
          ),
          child: Material(
            type: MaterialType.transparency,
            child: Ink(
              decoration: const BoxDecoration(
                borderRadius: radius,
                gradient: LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [JanColors.primary, JanColors.navy, JanColors.teal],
                ),
              ),
              child: InkWell(
                borderRadius: radius,
                onTap: onPressed,
                child: Stack(children: [
                  // glossy highlight on the upper half
                  Positioned(
                    left: 10,
                    right: 10,
                    top: 3,
                    height: 18,
                    child: DecoratedBox(
                      decoration: BoxDecoration(
                        borderRadius: radius,
                        gradient: LinearGradient(
                          begin: Alignment.topCenter,
                          end: Alignment.bottomCenter,
                          colors: [JanColors.white.withValues(alpha: 0.35), JanColors.white.withValues(alpha: 0)],
                        ),
                      ),
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 15),
                    child: Row(mainAxisSize: MainAxisSize.min, children: [
                      Icon(icon, color: JanColors.white),
                      const SizedBox(width: 10),
                      Text(label,
                          style: const TextStyle(color: JanColors.white, fontWeight: FontWeight.w700, fontSize: 15)),
                    ]),
                  ),
                ]),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
