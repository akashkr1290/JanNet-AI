import 'package:flutter/material.dart' hide Text;

import '../../core/theme/jan_tokens.dart';
import '../../core/widgets/jan_language_menu.dart';
import '../../core/widgets/jan_logo.dart';
import '../../core/widgets/jan_motion.dart';
import '../auth/screens/login_screen.dart';
import '../auth/screens/register_screen.dart';
import '../complaints/widgets/category_visuals.dart';
import '../../core/l10n/tr_text.dart';

/// UI phase 1: public landing page shown to signed-out visitors.
/// Honest by design: no statistics, testimonials or partner claims, and no
/// stock photography - the visuals are drawn with the app's own design
/// system. Reporting needs an account, so both calls to action lead to the
/// existing sign-in / registration screens.
class LandingScreen extends StatelessWidget {
  const LandingScreen({super.key});

  void _report(BuildContext context) =>
      Navigator.of(context).push(MaterialPageRoute(builder: (_) => const RegisterScreen()));

  void _track(BuildContext context) =>
      Navigator.of(context).push(MaterialPageRoute(builder: (_) => const LoginScreen()));

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: JanColors.background,
      body: SafeArea(
        child: CustomScrollView(
          slivers: [
            SliverToBoxAdapter(child: _Header(onSignIn: () => _track(context))),
            SliverToBoxAdapter(child: _Hero(onReport: () => _report(context), onTrack: () => _track(context))),
            const SliverToBoxAdapter(child: _Categories()),
            const SliverToBoxAdapter(child: _Steps()),
            const SliverToBoxAdapter(child: _LocationSection()),
            const SliverToBoxAdapter(child: _Footer()),
          ],
        ),
      ),
    );
  }
}

class _Section extends StatelessWidget {
  final Widget child;
  final Color? color;
  const _Section({required this.child, this.color});

  @override
  Widget build(BuildContext context) {
    return Container(
      color: color,
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: JanSpace.md, vertical: JanSpace.xxl),
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: JanBreakpoints.contentMaxWidth),
          child: child,
        ),
      ),
    );
  }
}

class _Header extends StatelessWidget {
  final VoidCallback onSignIn;
  const _Header({required this.onSignIn});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: JanSpace.md, vertical: JanSpace.sm),
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: JanBreakpoints.contentMaxWidth),
          child: Row(
            children: [
              const JanLogo(),
              const Spacer(),
              const JanLanguageMenu(),
              TextButton(onPressed: onSignIn, child: const Text('Sign in')),
            ],
          ),
        ),
      ),
    );
  }
}

class _Hero extends StatelessWidget {
  final VoidCallback onReport;
  final VoidCallback onTrack;
  const _Hero({required this.onReport, required this.onTrack});

  @override
  Widget build(BuildContext context) {
    final wide = MediaQuery.sizeOf(context).width >= JanBreakpoints.expanded;
    final text = JanReveal(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'Report a civic problem with a photo.\nFollow it until it is fixed.',
            style: Theme.of(context).textTheme.displaySmall?.copyWith(
                  color: JanColors.navy,
                  fontWeight: FontWeight.w800,
                  height: 1.1,
                  letterSpacing: -0.8,
                ),
          ),
          const SizedBox(height: JanSpace.md),
          const Text(
            'Photograph a pothole, garbage overflow, water leak, broken street light, open manhole or '
            'illegal construction. Confirm the place on a map and the complaint goes to the responsible '
            'department with a reference number you can track.',
            style: TextStyle(color: JanColors.slate, fontSize: 17, height: 1.5),
          ),
          const SizedBox(height: JanSpace.xl),
          Wrap(
            spacing: JanSpace.sm,
            runSpacing: JanSpace.sm,
            children: [
              FilledButton.icon(
                onPressed: onReport,
                icon: const Icon(Icons.add_a_photo_outlined),
                label: const Text('Report an Issue'),
              ),
              OutlinedButton.icon(
                onPressed: onTrack,
                icon: const Icon(Icons.search_rounded),
                label: const Text('Track Complaint'),
              ),
            ],
          ),
        ],
      ),
    );
    final visual = JanReveal(delay: const Duration(milliseconds: 150), child: const _ReportingVisual());
    return Container(
      decoration: const BoxDecoration(gradient: JanGradients.heroWash),
      child: Stack(
        children: [
          _Section(
            child: wide
                ? Row(
                    crossAxisAlignment: CrossAxisAlignment.center,
                    children: [
                      Expanded(flex: 5, child: text),
                      const SizedBox(width: JanSpace.xxl),
                      Expanded(flex: 5, child: visual),
                    ],
                  )
                : Column(children: [text, const SizedBox(height: JanSpace.xxl), visual]),
          ),
        ],
      ),
    );
  }
}

/// A phone-style card showing the two things the app does: read the photo and detect the place.
class _ReportingVisual extends StatelessWidget {
  const _ReportingVisual();

  @override
  Widget build(BuildContext context) {
    return Semantics(
      label: trs('How reporting works: a photo of the problem is analysed and the location is detected for you to confirm.'),
      child: ExcludeSemantics(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 460),
            child: Stack(
              clipBehavior: Clip.none,
              children: [
                Container(
                  decoration: BoxDecoration(
                    gradient: JanGradients.navy,
                    borderRadius: JanRadius.xlAll,
                    boxShadow: JanShadows.lifted,
                  ),
                  padding: const EdgeInsets.all(JanSpace.lg),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      AspectRatio(
                        aspectRatio: 16 / 10,
                        child: ClipRRect(
                          borderRadius: JanRadius.lgAll,
                          child: CustomPaint(painter: _RoadPhotoPainter()),
                        ),
                      ),
                      const SizedBox(height: JanSpace.md),
                      Row(
                        children: const [
                          Icon(Icons.check_circle_outline, color: JanColors.electricGlow, size: 18),
                          SizedBox(width: JanSpace.xs),
                          Expanded(
                            child: Text(
                              'The AI model checks the photo and suggests the category',
                              style: TextStyle(color: JanColors.white, fontWeight: FontWeight.w600),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: JanSpace.sm),
                      Row(
                        children: const [
                          Icon(Icons.my_location_rounded, color: Color(0xFF7FD1CB), size: 18),
                          SizedBox(width: JanSpace.xs),
                          Expanded(
                            child: Text(
                              'Your phone detects the location and you confirm it',
                              style: TextStyle(color: JanColors.white, fontWeight: FontWeight.w600),
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
                Positioned(
                  right: -6,
                  top: -14,
                  child: JanGlass(
                    padding: const EdgeInsets.symmetric(horizontal: JanSpace.sm, vertical: JanSpace.xs),
                    borderRadius: JanRadius.mdAll,
                    child: const Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(Icons.place_rounded, size: 16, color: JanColors.primary),
                        SizedBox(width: 4),
                        Text('Confirm location',
                            style: TextStyle(color: JanColors.navy, fontWeight: FontWeight.w700, fontSize: 13)),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Simple drawn road scene with a pothole - an illustration, not a photograph.
class _RoadPhotoPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size s) {
    canvas.drawRect(Offset.zero & s, Paint()..color = const Color(0xFF2A3B57));
    final road = Path()
      ..moveTo(s.width * 0.30, 0)
      ..lineTo(s.width * 0.70, 0)
      ..lineTo(s.width * 1.05, s.height)
      ..lineTo(-s.width * 0.05, s.height)
      ..close();
    canvas.drawPath(road, Paint()..color = const Color(0xFF3B4C69));
    final dash = Paint()..color = const Color(0xFFE6EDF8).withValues(alpha: 0.75);
    for (var i = 0; i < 4; i++) {
      final y = s.height * (0.08 + i * 0.24);
      canvas.drawRRect(
        RRect.fromRectAndRadius(
          Rect.fromCenter(center: Offset(s.width * 0.5, y), width: 5 + i * 2.0, height: s.height * 0.12),
          const Radius.circular(3),
        ),
        dash,
      );
    }
    final hole = Rect.fromCenter(center: Offset(s.width * 0.56, s.height * 0.68), width: s.width * 0.2, height: s.height * 0.13);
    canvas.drawOval(hole, Paint()..color = const Color(0xFF111B2E));
    canvas.drawOval(hole.deflate(2), Paint()..color = const Color(0xFF0B1424));
    final focus = Paint()
      ..color = JanColors.electricGlow
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2;
    final box = hole.inflate(12);
    const c = 12.0;
    for (final p in [
      [box.topLeft, const Offset(c, 0), const Offset(0, c)],
      [box.topRight, const Offset(-c, 0), const Offset(0, c)],
      [box.bottomLeft, const Offset(c, 0), const Offset(0, -c)],
      [box.bottomRight, const Offset(-c, 0), const Offset(0, -c)],
    ]) {
      canvas.drawLine(p[0], p[0] + p[1], focus);
      canvas.drawLine(p[0], p[0] + p[2], focus);
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

class _Heading extends StatelessWidget {
  final String title;
  final String subtitle;
  const _Heading(this.title, this.subtitle);

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Semantics(
          header: true,
          child: Text(title,
              style: Theme.of(context)
                  .textTheme
                  .headlineMedium
                  ?.copyWith(color: JanColors.navy, fontWeight: FontWeight.w800, letterSpacing: -0.4)),
        ),
        const SizedBox(height: JanSpace.xs),
        Text(subtitle, style: const TextStyle(color: JanColors.muted, fontSize: 16, height: 1.5)),
        const SizedBox(height: JanSpace.xl),
      ],
    );
  }
}

class _IssueInfo {
  final String code;
  final String description;
  final String tip;
  const _IssueInfo(this.code, this.description, this.tip);
}

const _issues = <_IssueInfo>[
  _IssueInfo('POTHOLE', 'Damaged road surfaces that put riders and vehicles at risk.',
      'Photograph the hole with some road around it so its size is clear.'),
  _IssueInfo('GARBAGE_OVERFLOW', 'Overflowing bins and unattended waste on the street.',
      'Show the pile and the nearest landmark or bin.'),
  _IssueInfo('WATER_LEAKAGE', 'Leaking pipes and water running onto the road.',
      'Capture where the water is coming from, if you can see it.'),
  _IssueInfo('BROKEN_STREET_LIGHT', 'Street lights that are dark, flickering or damaged.',
      'Take the photo of the pole and lamp; a daytime photo works.'),
  _IssueInfo('OPEN_MANHOLE', 'Uncovered or broken manhole covers, a safety hazard for people and vehicles.',
      'Stay at a safe distance and never stand in the road.'),
  _IssueInfo('ILLEGAL_CONSTRUCTION', 'Unauthorised building work or encroachment.',
      'Photograph the structure from the public road side.'),
];

class _Categories extends StatelessWidget {
  const _Categories();

  @override
  Widget build(BuildContext context) {
    return _Section(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const _Heading('What you can report',
              'Take a photo and the app suggests the category. Tap a card for photo tips.'),
          LayoutBuilder(builder: (context, c) {
            final cols = c.maxWidth >= 900 ? 3 : (c.maxWidth >= 560 ? 2 : 1);
            const gap = JanSpace.md;
            final w = ((c.maxWidth - gap * (cols - 1)) / cols).floorToDouble();
            return Wrap(
              spacing: gap,
              runSpacing: gap,
              children: [
                for (var i = 0; i < _issues.length; i++)
                  SizedBox(
                    width: w,
                    child: JanReveal(
                      delay: JanReveal.stagger(i),
                      child: _IssueCard(info: _issues[i]),
                    ),
                  ),
              ],
            );
          }),
        ],
      ),
    );
  }
}

class _IssueCard extends StatelessWidget {
  final _IssueInfo info;
  const _IssueCard({required this.info});

  @override
  Widget build(BuildContext context) {
    final v = CategoryVisual.of(info.code);
    return JanLiftCard(
      accent: v.color,
      semanticLabel: trs('${v.label}. ${info.description} Tap for photo tips.'),
      padding: EdgeInsets.zero,
      onTap: () => showModalBottomSheet<void>(
        context: context,
        showDragHandle: true,
        builder: (ctx) => Padding(
          padding: const EdgeInsets.fromLTRB(JanSpace.xl, 0, JanSpace.xl, JanSpace.xxl),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(v.label,
                  style: Theme.of(ctx).textTheme.titleLarge?.copyWith(color: JanColors.navy, fontWeight: FontWeight.w800)),
              const SizedBox(height: JanSpace.xs),
              Text(info.description, style: const TextStyle(color: JanColors.slate, height: 1.45)),
              const SizedBox(height: JanSpace.md),
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(Icons.lightbulb_outline, color: v.color, size: 20),
                  const SizedBox(width: JanSpace.xs),
                  Expanded(child: Text(info.tip, style: const TextStyle(color: JanColors.ink, height: 1.45))),
                ],
              ),
            ],
          ),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ClipRRect(
            borderRadius: const BorderRadius.vertical(top: Radius.circular(JanRadius.lg)),
            child: Container(
              height: 110,
              width: double.infinity,
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [v.background, JanColors.white],
                ),
              ),
              child: Center(
                child: Container(
                  width: 64,
                  height: 64,
                  decoration: BoxDecoration(
                    color: JanColors.white,
                    shape: BoxShape.circle,
                    border: Border.all(color: v.color.withValues(alpha: 0.3)),
                  ),
                  child: Icon(v.icon, color: v.color, size: 32),
                ),
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.all(JanSpace.md),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(v.label,
                    style: const TextStyle(color: JanColors.navy, fontWeight: FontWeight.w800, fontSize: 18)),
                const SizedBox(height: JanSpace.xxs),
                Text(info.description, style: const TextStyle(color: JanColors.muted, height: 1.45)),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _Steps extends StatelessWidget {
  const _Steps();

  @override
  Widget build(BuildContext context) {
    const steps = [
      (Icons.add_a_photo_outlined, 'Take a photo', 'Capture the problem with your camera or pick a photo from your gallery.'),
      (Icons.place_outlined, 'Confirm the place',
          'Check the detected location, or search for an area and move the pin to the exact spot.'),
      (Icons.check_circle_outline, 'Track it',
          'The complaint goes to the responsible department. You get a reference number and see its current status.'),
    ];
    return _Section(
      color: JanColors.white,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const _Heading('How it works', 'Three steps from problem to a tracked complaint.'),
          LayoutBuilder(builder: (context, c) {
            final row = c.maxWidth >= 760;
            final cards = [
              for (var i = 0; i < steps.length; i++)
                JanReveal(
                  delay: JanReveal.stagger(i),
                  child: Container(
                    padding: const EdgeInsets.all(JanSpace.lg),
                    decoration: BoxDecoration(
                      color: JanColors.surfaceAlt,
                      borderRadius: JanRadius.lgAll,
                      border: Border.all(color: JanColors.divider),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Container(
                              width: 40,
                              height: 40,
                              alignment: Alignment.center,
                              decoration: const BoxDecoration(gradient: JanGradients.electric, shape: BoxShape.circle),
                              child: Text('${i + 1}',
                                  style: const TextStyle(color: JanColors.white, fontWeight: FontWeight.w800)),
                            ),
                            const SizedBox(width: JanSpace.sm),
                            Icon(steps[i].$1, color: JanColors.primary),
                          ],
                        ),
                        const SizedBox(height: JanSpace.md),
                        Text(steps[i].$2,
                            style: const TextStyle(color: JanColors.navy, fontWeight: FontWeight.w800, fontSize: 18)),
                        const SizedBox(height: JanSpace.xxs),
                        Text(steps[i].$3, style: const TextStyle(color: JanColors.muted, height: 1.45)),
                      ],
                    ),
                  ),
                ),
            ];
            if (row) {
              return Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  for (var i = 0; i < cards.length; i++) ...[
                    Expanded(child: cards[i]),
                    if (i < cards.length - 1) const SizedBox(width: JanSpace.md),
                  ],
                ],
              );
            }
            return Column(
              children: [
                for (var i = 0; i < cards.length; i++) ...[
                  cards[i],
                  if (i < cards.length - 1) const SizedBox(height: JanSpace.md),
                ],
              ],
            );
          }),
        ],
      ),
    );
  }
}

class _LocationSection extends StatelessWidget {
  const _LocationSection();

  @override
  Widget build(BuildContext context) {
    final wide = MediaQuery.sizeOf(context).width >= JanBreakpoints.medium;
    final copy = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: const [
        _Heading('You confirm the location',
            'The app does not silently decide where the problem is.'),
        _Point(Icons.my_location_rounded, 'The detected position is shown to you first.'),
        _Point(Icons.search_rounded, 'Wrong place? Search any area by name and move the pin.'),
        _Point(Icons.check_circle_outline,
            'A ward is shown as confirmed only where a published ward map exists. Otherwise it is marked as waiting for verification by the office.'),
      ],
    );
    const map = _MapVisual();
    return _Section(
      child: wide
          ? Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                Expanded(child: copy),
                const SizedBox(width: JanSpace.xxl),
                const Expanded(child: map),
              ],
            )
          : Column(children: [copy, const SizedBox(height: JanSpace.xl), map]),
    );
  }
}

class _Point extends StatelessWidget {
  final IconData icon;
  final String text;
  const _Point(this.icon, this.text);

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: JanSpace.sm),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, color: JanColors.teal, size: 22),
          const SizedBox(width: JanSpace.sm),
          Expanded(child: Text(text, style: const TextStyle(color: JanColors.slate, height: 1.45, fontSize: 16))),
        ],
      ),
    );
  }
}

class _MapVisual extends StatelessWidget {
  const _MapVisual();

  @override
  Widget build(BuildContext context) {
    return Semantics(
      label: trs('Illustration: a map with a translucent highlighted area and a pulsing pin showing the confirmed place.'),
      child: ExcludeSemantics(
        child: ClipRRect(
          borderRadius: JanRadius.xlAll,
          child: AspectRatio(
            aspectRatio: 4 / 3,
            child: Stack(
              fit: StackFit.expand,
              children: [
                CustomPaint(painter: _MapPainter()),
                const Align(
                  alignment: Alignment(0.1, 0.0),
                  child: JanPulse(
                    child: Icon(Icons.location_on_rounded, color: JanColors.primary, size: 40),
                  ),
                ),
                Positioned(
                  left: JanSpace.md,
                  bottom: JanSpace.md,
                  child: JanGlass(
                    padding: const EdgeInsets.symmetric(horizontal: JanSpace.sm, vertical: JanSpace.xs),
                    borderRadius: JanRadius.mdAll,
                    child: const Text('Move the pin to the exact spot',
                        style: TextStyle(color: JanColors.navy, fontWeight: FontWeight.w700, fontSize: 13)),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _MapPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size s) {
    canvas.drawRect(Offset.zero & s, Paint()..color = const Color(0xFFEAF1FA));
    final block = Paint()..color = const Color(0xFFDCE7F4);
    final road = Paint()
      ..color = JanColors.white
      ..strokeWidth = 12
      ..strokeCap = StrokeCap.round;
    for (var i = 1; i < 4; i++) {
      canvas.drawLine(Offset(0, s.height * i / 4), Offset(s.width, s.height * i / 4 + (i.isEven ? 14 : -10)), road);
      canvas.drawLine(Offset(s.width * i / 4, 0), Offset(s.width * i / 4 + (i.isOdd ? 16 : -12), s.height), road);
    }
    canvas.drawRRect(
        RRect.fromRectAndRadius(Rect.fromLTWH(s.width * 0.06, s.height * 0.08, s.width * 0.14, s.height * 0.14), const Radius.circular(8)),
        block);
    canvas.drawRRect(
        RRect.fromRectAndRadius(Rect.fromLTWH(s.width * 0.74, s.height * 0.62, s.width * 0.18, s.height * 0.2), const Radius.circular(8)),
        block);
    final area = Path()
      ..moveTo(s.width * 0.28, s.height * 0.22)
      ..lineTo(s.width * 0.70, s.height * 0.18)
      ..lineTo(s.width * 0.76, s.height * 0.70)
      ..lineTo(s.width * 0.34, s.height * 0.78)
      ..close();
    canvas.drawPath(area, Paint()..color = JanColors.primary.withValues(alpha: 0.14));
    canvas.drawPath(
      area,
      Paint()
        ..color = JanColors.primary.withValues(alpha: 0.55)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2,
    );
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

class _Footer extends StatelessWidget {
  const _Footer();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      decoration: const BoxDecoration(gradient: JanGradients.navy),
      padding: const EdgeInsets.symmetric(horizontal: JanSpace.md, vertical: JanSpace.xl),
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: JanBreakpoints.contentMaxWidth),
          child: Wrap(
            alignment: WrapAlignment.spaceBetween,
            crossAxisAlignment: WrapCrossAlignment.center,
            runSpacing: JanSpace.sm,
            spacing: JanSpace.md,
            children: [
              const JanLogo(onDark: true, fontSize: 20, markSize: 28),
              Wrap(
                spacing: JanSpace.xs,
                children: [
                  TextButton(
                    style: TextButton.styleFrom(foregroundColor: JanColors.white),
                    onPressed: () => Navigator.of(context).pushNamed('/privacy'),
                    child: const Text('Privacy Policy'),
                  ),
                  TextButton(
                    style: TextButton.styleFrom(foregroundColor: JanColors.white),
                    onPressed: () => Navigator.of(context).pushNamed('/terms'),
                    child: const Text('Terms and Conditions'),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
