import 'package:flutter/material.dart' hide Text;

import '../../../core/api/geo_api.dart';
import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_states.dart';
import '../../../core/widgets/jan_surfaces.dart';
import '../complaints_api.dart';
import '../location/geo_models.dart';
import '../location/incident_location.dart';
import '../location/incident_map.dart';
import '../models/location_check.dart';
import '../widgets/category_visuals.dart';
import '../../../core/l10n/tr_text.dart';

/// V36 "Location check" - staff confirm the ward of complaints whose location
/// could not be proven by a published ward map (spec 2026-10-11: such
/// complaints go to manual verification instead of being guessed).
///
/// Data: GET /api/v1/complaints/location-verification (backend
/// LocationVerificationService - Department Heads see their department,
/// officers their own complaints, Verification Team / Admin all). Confirming
/// calls PATCH /complaints/{id}/location-verification.
///
/// Opened from verification_home_screen.dart and department_head_home_screen.dart.
class LocationCheckScreen extends StatefulWidget {
  final bool embedded;
  const LocationCheckScreen({super.key, this.embedded = false});

  @override
  State<LocationCheckScreen> createState() => _LocationCheckScreenState();
}

class _LocationCheckScreenState extends State<LocationCheckScreen> {
  late Future<List<LocationCheckItem>> _future;

  @override
  void initState() {
    super.initState();
    _future = ComplaintsApi.instance.locationChecks();
  }

  Future<void> _refresh() async {
    setState(() => _future = ComplaintsApi.instance.locationChecks());
    await _future;
  }

  Future<void> _open(LocationCheckItem item) async {
    final done = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => _CheckSheet(item: item),
    );
    if (done == true && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('${item.referenceNumber}: location confirmed')));
      _refresh();
    }
  }

  @override
  Widget build(BuildContext context) {
    final body = RefreshIndicator(
      onRefresh: _refresh,
      child: FutureBuilder<List<LocationCheckItem>>(
        future: _future,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return JanSkeletonList(semanticLabel: trs('Loading the location checks'));
          }
          if (snapshot.hasError) {
            return JanErrorState.fromError(snapshot.error, fallback: 'Could not load the location checks.', onRetry: _refresh);
          }
          final items = snapshot.data ?? const [];
          if (items.isEmpty) {
            return const JanEmptyState(
              icon: Icons.verified_outlined,
              title: 'All locations confirmed',
              message: 'No complaint is waiting for a ward check.',
            );
          }
          return ListView.separated(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.xs, JanSpace.md, JanSpace.xxl),
            itemCount: items.length,
            separatorBuilder: (_, __) => const SizedBox(height: JanSpace.sm),
            itemBuilder: (context, i) {
              final it = items[i];
              final cat = CategoryVisual.of(it.category);
              return JanCard(
                onTap: () => _open(it),
                child: Row(children: [
                  Icon(cat.icon, color: cat.color),
                  const SizedBox(width: JanSpace.sm),
                  Expanded(
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Text('${it.referenceNumber} · ${cat.label}', style: const TextStyle(fontWeight: FontWeight.w800)),
                      if (it.localBodyName != null) Text(it.localBodyName!),
                      Text(it.reasonText, style: const TextStyle(color: JanColors.muted)),
                      if (it.suggestedWardName != null)
                        Text('Suggested: ${it.suggestedWardName}', style: const TextStyle(color: JanColors.primary)),
                    ]),
                  ),
                  const Icon(Icons.chevron_right_rounded, color: JanColors.muted),
                ]),
              );
            },
          );
        },
      ),
    );
    if (widget.embedded) return body;
    return JanPage(title: 'Location check', maxWidth: 820, body: body);
  }
}

/// Map + the body's wards; staff pick the right ward (the suggested one is marked).
class _CheckSheet extends StatefulWidget {
  final LocationCheckItem item;
  const _CheckSheet({required this.item});

  @override
  State<_CheckSheet> createState() => _CheckSheetState();
}

class _CheckSheetState extends State<_CheckSheet> {
  List<WardRef>? _wards;
  int? _selected;
  List<List<GeoPoint>> _outline = const [];
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _selected = widget.item.wardId ?? widget.item.suggestedWardId;
    final body = widget.item.localBodyCode;
    if (body != null) {
      GeoApi.instance.wards(body).then((w) {
        if (mounted) setState(() => _wards = w);
      }).catchError((_) {
        if (mounted) setState(() => _wards = const []);
      });
    } else {
      _wards = const [];
    }
    _loadOutline();
  }

  Future<void> _loadOutline() async {
    final id = _selected;
    if (id == null) {
      setState(() => _outline = const []);
      return;
    }
    final rings = await GeoApi.instance.wardOutline(id);
    if (mounted && _selected == id) setState(() => _outline = rings);
  }

  Future<void> _confirm() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await ComplaintsApi.instance.verifyLocation(widget.item.complaintId, wardId: _selected);
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      if (mounted) setState(() => _error = 'Could not save: $e');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final it = widget.item;
    return SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(JanSpace.md, 0, JanSpace.md, JanSpace.md),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Text(it.referenceNumber, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w800)),
          Text(it.reasonText, style: const TextStyle(color: JanColors.muted)),
          const SizedBox(height: JanSpace.sm),
          if (it.point != null) IncidentMapPreview(point: it.point!, height: 200, zoom: 15, outline: _outline),
          const SizedBox(height: JanSpace.sm),
          const Text('Which ward is the problem in?', style: TextStyle(fontWeight: FontWeight.w700)),
          const SizedBox(height: JanSpace.xs),
          if (_wards == null)
            const LinearProgressIndicator()
          else if (_wards!.isEmpty)
            const Text('This body has no ward list - confirm the area as it is.', style: TextStyle(color: JanColors.muted))
          else
            Wrap(spacing: JanSpace.xs, runSpacing: JanSpace.xs, children: [
              for (final w in _wards!)
                ChoiceChip(
                  label: Text(w.shortLabel),
                  avatar: w.wardId == it.suggestedWardId ? const Icon(Icons.near_me_outlined, size: 16) : null,
                  tooltip: trs(w.name),
                  selected: _selected == w.wardId,
                  onSelected: (_) {
                    setState(() => _selected = w.wardId);
                    _loadOutline();
                  },
                ),
            ]),
          if (_error != null) ...[
            const SizedBox(height: JanSpace.xs),
            JanBanner(tone: JanBannerTone.error, message: _error!),
          ],
          const SizedBox(height: JanSpace.md),
          FilledButton.icon(
            style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(50)),
            onPressed: _saving ? null : _confirm,
            icon: const Icon(Icons.verified_rounded),
            label: Text(_selected == null ? 'Confirm area without a ward' : 'Confirm this ward'),
          ),
        ]),
      ),
    );
  }
}
