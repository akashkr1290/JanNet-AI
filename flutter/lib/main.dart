import 'package:flutter/material.dart' hide Text;

import 'core/app_preferences.dart';
import 'core/l10n/app_strings.dart';
import 'core/l10n/language_store.dart';
import 'core/theme/jan_theme.dart';
import 'core/theme/jan_tokens.dart';
import 'core/widgets/jan_logo.dart';
import 'core/widgets/jan_surfaces.dart';
import 'features/auth/auth_api.dart';
import 'features/home_router.dart';
import 'features/landing/landing_screen.dart';
import 'features/onboarding/onboarding_screen.dart';
import 'features/onboarding/onboarding_store.dart';
import 'features/settings/screens/privacy_policy_screen.dart';
import 'core/l10n/tr_text.dart';

void main() {
  runApp(const JannetApp());
}

class JannetApp extends StatefulWidget {
  const JannetApp({super.key});

  @override
  State<JannetApp> createState() => _JannetAppState();
}

class _JannetAppState extends State<JannetApp> {
  @override
  void initState() {
    super.initState();
    AppStrings.currentLanguage.addListener(_languageChanged);
  }

  @override
  void dispose() {
    AppStrings.currentLanguage.removeListener(_languageChanged);
    super.dispose();
  }

  /// A language change redraws every screen on the stack (also the ones built
  /// with const widgets), so labels, hints and tooltips switch at once and no
  /// screen loses its state or place.
  void _languageChanged() {
    void rebuild(Element element) {
      element.markNeedsBuild();
      element.visitChildren(rebuild);
    }

    WidgetsBinding.instance.rootElement?.visitChildren(rebuild);
  }

  @override
  Widget build(BuildContext context) {
    // Gap-backlog Patch 46: the saved high-contrast setting is applied app-wide.
    // Text size follows the OS accessibility setting (no textScaler clamp).
    return LangScope(
      language: AppStrings.currentLanguage,
      child: _buildApp(),
    );
  }

  Widget _buildApp() {
    return ValueListenableBuilder<bool>(
      valueListenable: AppPreferences.highContrast,
      builder: (context, highContrast, _) => MaterialApp(
        title: 'JanNet AI',
        debugShowCheckedModeBanner: false,
        // UI redesign: the JanNet AI design system (lib/core/theme). The
        // high-contrast setting keeps the same components with deeper
        // text, borders and interactive colours.
        theme: JanTheme.light(highContrast: highContrast),
        home: const _StartupGate(),
        // Real addresses for the legal pages (/#/privacy and /#/terms on the
        // web), so they can be linked from outside the app.
        routes: {
          '/privacy': (_) => const PrivacyPolicyScreen(),
          '/terms': (_) => const TermsOfUseScreen(),
        },
      ),
    );
  }
}

/// Minimal splash/routing gate - if a token is already stored, skip
/// straight to the role-appropriate home shell (Phase 12: Citizen vs
/// Officer/Department Head, via resolveHomeScreen); otherwise show the
/// login screen. No token-expiry check here - ApiClient's refresh-on-401
/// handles an expired access token transparently on the first real API
/// call; a fully expired refresh token still routes back to LoginScreen at
/// that point via AuthApi's logout-on-refresh-failure path in
/// ApiClient._tryRefresh.
///
/// Post-UI gap fix - first-launch onboarding:
///  * signed in                        -> home (onboarding never shown)
///  * signed out, onboarding not done  -> OnboardingScreen -> LandingScreen
///  * signed out, onboarding done      -> LandingScreen (-> Login / Register)
/// The startup checks now run once (initState) instead of on every rebuild,
/// so a theme change (high contrast) no longer re-runs them.
class _StartupGate extends StatefulWidget {
  const _StartupGate();

  @override
  State<_StartupGate> createState() => _StartupGateState();
}

class _StartupGateState extends State<_StartupGate> {
  late final Future<Widget> _start = _decide();

  Future<Widget> _decide() async {
    await LanguageStore.instance.load();
    if (await AuthApi.instance.isLoggedIn) {
      return resolveHomeScreen();
    }
    if (!await OnboardingStore.instance.isCompleted) {
      return OnboardingScreen(onFinished: _finishOnboarding);
    }
    return const LandingScreen();
  }

  Future<void> _finishOnboarding() async {
    await OnboardingStore.instance.markCompleted();
    if (!mounted) return;
    Navigator.of(context).pushReplacement(MaterialPageRoute(builder: (_) => const LandingScreen()));
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<Widget>(
      future: _start,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const _SplashScreen();
        }
        return snapshot.data ?? const LandingScreen();
      },
    );
  }
}

/// UI redesign: reference splash - JanNet AI mark, wordmark and tagline on
/// the city backdrop while the session is checked. Shown only for as long
/// as the startup checks above take.
class _SplashScreen extends StatelessWidget {
  const _SplashScreen();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: JanBackdrop(
        child: Center(
          child: Semantics(
            label: trs('JanNet AI is starting'),
            liveRegion: true,
            child: const Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                JanLogoMark(size: 96),
                SizedBox(height: JanSpace.lg),
                JanLogo(showMark: false, fontSize: 34),
                SizedBox(height: JanSpace.xs),
                Text(
                  'Report civic issues. Track the fix.',
                  style: TextStyle(color: JanColors.muted, fontSize: 17, letterSpacing: 0.4),
                ),
                SizedBox(height: JanSpace.xxl),
                SizedBox(width: 28, height: 28, child: CircularProgressIndicator(strokeWidth: 3)),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
