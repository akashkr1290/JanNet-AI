import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/core/widgets/jan_stat_card.dart';
import 'package:jannet_ai/features/complaints/models/complaint.dart';
import 'package:jannet_ai/features/dashboard/dashboard_api.dart';
import 'package:jannet_ai/features/dashboard/screens/kpi_complaints_screen.dart';

ComplaintSummary _summary(int id, String status) => ComplaintSummary.fromJson({
      'complaintId': id,
      'referenceNumber': 'JN-2026-${id.toString().padLeft(6, '0')}',
      'category': 'POTHOLE',
      'status': status,
    });

void main() {
  testWidgets('a personal tile lists its complaints with the count in the title', (tester) async {
    final pages = <int>[];
    await tester.pumpWidget(MaterialApp(
      home: KpiComplaintsScreen(
        kpi: 'PENDING',
        title: 'Pending',
        citizenView: true,
        loader: (page) async {
          pages.add(page);
          return KpiComplaintPage(items: [_summary(1, 'VERIFIED'), _summary(2, 'ASSIGNED')], total: 2, last: true);
        },
      ),
    ));
    await tester.pumpAndSettle();

    expect(pages, [0]);
    expect(find.text('Pending (2)'), findsOneWidget);
    expect(find.textContaining('JN-2026-000001'), findsOneWidget);
    expect(find.textContaining('JN-2026-000002'), findsOneWidget);
  });

  testWidgets('an empty tile says so', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: KpiComplaintsScreen(
        kpi: 'OVERDUE',
        title: 'Overdue',
        loader: (page) async => const KpiComplaintPage(items: [], total: 0, last: true),
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.text('No complaints'), findsOneWidget);
  });

  testWidgets('a tappable stat card shows an arrow and reports the tap', (tester) async {
    var taps = 0;
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: Column(children: [
          JanStatCard(label: 'Open', value: '5', icon: Icons.pending_actions_rounded, onTap: () => taps++),
          const JanStatCard(label: 'Avg', value: '3h', icon: Icons.timer_outlined),
        ]),
      ),
    ));
    expect(find.byIcon(Icons.arrow_forward_rounded), findsOneWidget); // only the tappable one
    await tester.tap(find.text('Open'));
    await tester.pumpAndSettle();
    expect(taps, 1);
  });

  testWidgets('a rate tile explains itself in a sheet', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (context) => JanStatCard(
            label: 'Duplicate Rate',
            value: '4.2%',
            icon: Icons.copy_all_rounded,
            onTap: () => showJanStatInfo(context,
                title: 'Duplicate Rate', value: '4.2%', explanation: 'Share of all complaints that were joined.'),
          ),
        ),
      ),
    ));
    await tester.tap(find.text('Duplicate Rate'));
    await tester.pumpAndSettle();
    expect(find.text('Share of all complaints that were joined.'), findsOneWidget);
    expect(find.text('4.2%'), findsNWidgets(2));
  });
}
