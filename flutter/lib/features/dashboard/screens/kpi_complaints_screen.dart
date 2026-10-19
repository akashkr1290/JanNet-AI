import 'package:flutter/material.dart' hide Text;

import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_states.dart';
import '../../complaints/models/complaint.dart';
import '../../complaints/screens/complaint_detail_screen.dart';
import '../../complaints/screens/complaint_list_screen.dart';
import '../../complaints/screens/officer_complaint_detail_screen.dart';
import '../dashboard_api.dart';
import '../../../core/l10n/tr_text.dart';

/// Loads one page of a tile's complaints.
typedef KpiPageLoader = Future<KpiComplaintPage> Function(int page);

/// Dashboard drill-down: the complaints behind one tile, a row opens the
/// complaint.
///  * Government / department dashboards (Total, Open, Resolved, Escalated):
///    GET /api/v1/dashboard/complaints. Those tile counts are refreshed by the
///    server every few minutes, so right after a change the two can differ briefly.
///  * The citizen's and the officer's own dashboards: [KpiComplaintsScreen.mine]
///    (GET /api/v1/my/complaints), counted live with the same rules.
class KpiComplaintsScreen extends StatefulWidget {
  final String kpi; // TOTAL | OPEN | RESOLVED | ESCALATED (or a personal tile)
  final String title;
  final int? departmentId;

  /// Replaces the government-dashboard call (personal tiles, tests).
  final KpiPageLoader? loader;

  /// A citizen opens their own complaint screen; staff open the officer view.
  final bool citizenView;

  const KpiComplaintsScreen({
    super.key,
    required this.kpi,
    required this.title,
    this.departmentId,
    this.loader,
    this.citizenView = false,
  });

  /// A tile of the caller's own dashboard (citizen or officer / department head).
  factory KpiComplaintsScreen.mine({Key? key, required String tile, required String title, bool citizenView = false}) =>
      KpiComplaintsScreen(
        key: key,
        kpi: tile,
        title: title,
        citizenView: citizenView,
        loader: (page) => DashboardApi.instance.myTileComplaints(tile, page: page),
      );

  @override
  State<KpiComplaintsScreen> createState() => _KpiComplaintsScreenState();
}

class _KpiComplaintsScreenState extends State<KpiComplaintsScreen> {
  final List<ComplaintSummary> _items = [];
  int _page = 0;
  int? _total;
  bool _last = false;
  bool _loading = false;
  Object? _error;

  @override
  void initState() {
    super.initState();
    _loadMore();
  }

  Future<void> _loadMore() async {
    if (_loading || _last) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final loader = widget.loader;
      final page = loader != null
          ? await loader(_page)
          : await DashboardApi.instance.kpiComplaints(widget.kpi, departmentId: widget.departmentId, page: _page);
      if (!mounted) return;
      setState(() {
        _items.addAll(page.items);
        _total = page.total;
        _last = page.last;
        _page++;
      });
    } catch (e) {
      if (mounted) setState(() => _error = e);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _reload() async {
    setState(() {
      _items.clear();
      _page = 0;
      _last = false;
      _total = null;
    });
    await _loadMore();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(_total == null ? widget.title : '${trs(widget.title)} ($_total)')),
      body: _body(),
    );
  }

  Widget _body() {
    if (_items.isEmpty && _loading) return JanSkeletonList(semanticLabel: trs('Loading complaints'));
    if (_items.isEmpty && _error != null) {
      return JanErrorState.fromError(_error, fallback: 'Could not load the complaints.', onRetry: _reload);
    }
    if (_items.isEmpty) {
      return const JanEmptyState(
        icon: Icons.inbox_outlined,
        title: 'No complaints',
        message: 'There are no complaints in this group right now.',
      );
    }
    return RefreshIndicator(
      onRefresh: _reload,
      child: ListView.separated(
        padding: const EdgeInsets.all(JanSpace.md),
        itemCount: _items.length + 1,
        separatorBuilder: (context, index) => const SizedBox(height: JanSpace.xs),
        itemBuilder: (context, i) {
          if (i == _items.length) return _footer();
          final c = _items[i];
          return ComplaintSummaryCard(
            complaint: c,
            onTap: () => Navigator.of(context).push(
              MaterialPageRoute(
                builder: (_) => widget.citizenView
                    ? ComplaintDetailScreen(complaintId: c.complaintId)
                    : OfficerComplaintDetailScreen(complaintId: c.complaintId),
              ),
            ),
          );
        },
      ),
    );
  }

  Widget _footer() {
    if (_loading) {
      return const Padding(padding: EdgeInsets.all(JanSpace.md), child: Center(child: CircularProgressIndicator()));
    }
    if (_error != null) {
      return TextButton.icon(onPressed: _loadMore, icon: const Icon(Icons.refresh), label: const Text('Try again'));
    }
    if (_last) return const SizedBox(height: JanSpace.lg);
    return OutlinedButton.icon(onPressed: _loadMore, icon: const Icon(Icons.expand_more), label: const Text('Load more'));
  }
}
