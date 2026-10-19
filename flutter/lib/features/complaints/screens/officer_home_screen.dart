import 'package:flutter/material.dart';

import '../../../core/widgets/jan_liquid.dart';
import '../../../core/widgets/jan_shell.dart';
import '../../assistant/assistant_screen.dart';
import '../../auth/auth_api.dart';
import '../../auth/screens/login_screen.dart';
import '../../dashboard/screens/officer_dashboard_screen.dart';
import '../../notifications/screens/notifications_screen.dart';
import '../../settings/screens/personal_settings_screen.dart';
import 'officer_queue_screen.dart';
import '../../users/widgets/signed_in_identity.dart';
import '../../../core/l10n/tr_text.dart';

/// Officer home shell (GOVERNMENT_OFFICER; MAINTENANCE_TEAM was folded into it in V31, Sep
/// 2026 strict recheck). Was a single "My Queue" view; Gap-backlog Patch 09
/// adds the Dashboard tab (workload, SLA status, today's tasks, performance).
class OfficerHomeScreen extends StatefulWidget {
  const OfficerHomeScreen({super.key});

  @override
  State<OfficerHomeScreen> createState() => _OfficerHomeScreenState();
}

class _OfficerHomeScreenState extends State<OfficerHomeScreen> {
  int _tab = 0;

  Future<void> _logout() async {
    await AuthApi.instance.logout();
    if (!mounted) return;
    Navigator.of(context).pushReplacement(
      MaterialPageRoute(builder: (_) => const LoginScreen()),
    );
  }

  /// JanNet Sahayak v2: the chat window for the officer's own work. "Open
  /// queue" in the chat comes back here and shows the Queue tab.
  Future<void> _openAssistant() async {
    final exit = await AssistantScreen.open(context, audience: AssistantAudience.officer);
    if (!mounted || exit != AssistantExit.myComplaints) return;
    setState(() => _tab = 1);
  }

  // UI redesign: shared adaptive JanShell (bottom navigation on phones,
  // navy side rail on web). Same two tabs, actions and sign-out.
  @override
  Widget build(BuildContext context) {
    return JanShell(
      roleLabel: 'Government Officer',
      identity: const SignedInIdentity(fallbackRole: 'Government Officer'),
      currentIndex: _tab,
      onDestinationSelected: (i) => setState(() => _tab = i),
      onLogout: _logout,
      floatingActionButton: JanLiquidButton(
        icon: Icons.support_agent_rounded,
        label: 'Ask Sahayak',
        tooltip: 'Ask JanNet Sahayak',
        onPressed: _openAssistant,
      ),
      actions: [
        JanShellAction(
          icon: Icons.notifications_outlined,
          tooltip: trs('Notifications'),
          onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const NotificationsScreen())),
        ),
        JanShellAction(
          icon: Icons.settings_outlined,
          tooltip: trs('Settings'),
          onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const PersonalSettingsScreen())),
        ),
      ],
      destinations: [
        JanDestination(
          label: 'Dashboard',
          icon: Icons.space_dashboard_outlined,
          selectedIcon: Icons.space_dashboard_rounded,
          heading: 'My Dashboard',
          builder: (_) => const OfficerDashboardScreen(),
        ),
        JanDestination(
          label: 'Queue',
          icon: Icons.inbox_outlined,
          selectedIcon: Icons.inbox_rounded,
          heading: 'My Queue',
          subheading: 'Complaints assigned to you',
          builder: (_) => const OfficerQueueScreen(),
        ),
      ],
    );
  }
}
