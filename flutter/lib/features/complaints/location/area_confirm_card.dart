import 'package:flutter/material.dart' hide Text;

import '../../../core/api/geo_api.dart';
import '../../../core/l10n/app_strings.dart';
import '../../../core/l10n/bi_text.dart';
import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_states.dart';
import 'geo_models.dart';
import 'incident_location.dart';
import '../../../core/l10n/tr_text.dart';

/// "Is this the place of the problem?" - shown under the map once the pin
/// stops (incident_location_picker_screen.dart). [address] comes from
/// GET /api/v1/geo/reverse (core/api/geo_api.dart); the backend's
/// AreaResolver worked out the village / Gram Panchayat / Nagar Nigam / ward
/// (and, V37, the development block) from the published boundaries containing the pin.
///
///  * Backend sure (EXACT / LIKELY): big "Village, Gram Panchayat" + Yes / No.
///  * Not sure: one-tap list of the likely villages / towns.
///  * Ward: automatic ONLY when a ward map contains the pin; otherwise one tap
///    from the body's wards (the office then checks it - spec 2026-10-11),
///    with the backend's suggested ward marked.
///  * Nothing fits, or "No": [onManual] - the PIN -> village -> ward panel.
///
/// Reports the accepted area through [onChanged]; null = not accepted yet.
class AreaConfirmCard extends StatefulWidget {
  final GeoAddress? address;
  final bool loading;
  final IncidentArea? value;
  final ValueChanged<IncidentArea?> onChanged;
  final VoidCallback onManual;

  const AreaConfirmCard({
    super.key,
    required this.address,
    required this.loading,
    required this.value,
    required this.onChanged,
    required this.onManual,
  });

  @override
  State<AreaConfirmCard> createState() => _AreaConfirmCardState();
}

class _AreaConfirmCardState extends State<AreaConfirmCard> {
  /// Wards of the accepted body (the backend's list, or loaded for a body picked from the list).
  List<WardRef>? _wards;
  bool _loadingWards = false;

  @override
  void didUpdateWidget(AreaConfirmCard old) {
    super.didUpdateWidget(old);
    if (old.address != widget.address) {
      // a new pin position: forget the previous ward list
      _wards = null;
    }
  }

  AreaMatch get _match => widget.address?.area ?? AreaMatch.empty;

  /// Citizen pressed Yes on the backend's proposal.
  void _acceptProposal() {
    final m = _match;
    final body = m.localBody ?? (m.village != null && m.village!.localBodies.length == 1 ? m.village!.localBodies.first : null);
    _choose(village: m.village, body: body, wards: m.wardChoices, ward: m.ward);
  }

  /// A village / town picked from the list (or accepted). Loads that body's wards
  /// when the backend's list was for another body.
  Future<void> _choose({VillageRef? village, LocalBodyRef? body, List<WardRef>? wards, WardRef? ward}) async {
    body ??= village != null && village.localBodies.length == 1 ? village.localBodies.first : null;
    setState(() => _wards = wards);
    widget.onChanged(areaFrom(address: widget.address, village: village, body: body, ward: ward));
    if (body != null && (wards == null || wards.isEmpty || wards.first.localBodyCode != body.code)) {
      setState(() => _loadingWards = true);
      try {
        final list = await GeoApi.instance.wards(body.code);
        if (mounted) setState(() => _wards = list);
      } catch (_) {
        if (mounted) setState(() => _wards = const []);
      } finally {
        if (mounted) setState(() => _loadingWards = false);
      }
    }
  }

  void _chooseWard(WardRef? ward) {
    final current = widget.value;
    if (current == null) return;
    widget.onChanged(current.withWard(ward?.wardId, ward?.name));
  }

  @override
  Widget build(BuildContext context) {
    if (widget.loading) {
      return Row(children: [
        const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)),
        const SizedBox(width: JanSpace.sm),
        Text(AppStrings.of('loc_finding_address'), style: const TextStyle(color: JanColors.muted)),
      ]);
    }
    final a = widget.address;
    if (a == null) return const SizedBox.shrink();
    final m = a.area;
    final children = <Widget>[];

    final headline = [a.landmark, a.formattedAddress].whereType<String>().where((s) => s.isNotEmpty).toList();
    if (headline.isNotEmpty) {
      children.add(Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const Icon(Icons.place_rounded, color: JanColors.error, size: 20),
        const SizedBox(width: JanSpace.xs),
        Expanded(
          child: Text(headline.toSet().join(' - '),
              maxLines: 3, overflow: TextOverflow.ellipsis, style: const TextStyle(color: JanColors.slate)),
        ),
      ]));
      children.add(const SizedBox(height: JanSpace.xs));
    }
    if (m.district != null && !m.district!.served) {
      children.add(JanBanner(tone: JanBannerTone.warning, icon: Icons.info_outline, message: AppStrings.of('loc_not_served')));
      children.add(const SizedBox(height: JanSpace.xs));
    }

    final accepted = widget.value;
    if (accepted != null && (accepted.villageCode != null || accepted.localBodyCode != null)) {
      // ---- accepted: summary + ward
      children.add(Row(children: [
        const Icon(Icons.check_circle_rounded, color: JanColors.teal),
        const SizedBox(width: JanSpace.xs),
        Expanded(
          child: Text(accepted.summary,
              style: const TextStyle(fontWeight: FontWeight.w700, color: JanColors.ink, fontSize: 15.5)),
        ),
        TextButton(
          onPressed: () => widget.onChanged(null),
          child: const Text('Change'),
        ),
      ]));
      children.add(const SizedBox(height: JanSpace.xs));
      children.add(_wardChooser(accepted));
    } else if (m.isProposal) {
      // ---- backend is sure: Yes / No
      final body = m.localBody ?? (m.village?.localBodies.length == 1 ? m.village!.localBodies.first : null);
      children.addAll([
        const BiText('loc_is_this_place'),
        const SizedBox(height: JanSpace.xs),
        Text(
          [
            if (m.village != null) m.village!.name else if (a.locality != null) a.locality!,
            if (body != null) body.label,
            if (m.block != null && !(body?.isUrban ?? false)) '${m.block!.name} block',
            if (m.district != null) m.district!.name,
          ].join(', '),
          style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w800, color: JanColors.navy),
        ),
        const SizedBox(height: JanSpace.sm),
        Row(children: [
          Expanded(
            child: FilledButton.icon(
              style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(52)),
              onPressed: _acceptProposal,
              icon: const Icon(Icons.check_rounded),
              label: Text(AppStrings.of('loc_yes')),
            ),
          ),
          const SizedBox(width: JanSpace.sm),
          Expanded(
            child: OutlinedButton.icon(
              style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(52)),
              onPressed: widget.onManual,
              icon: const Icon(Icons.close_rounded),
              label: Text(AppStrings.of('loc_no')),
            ),
          ),
        ]),
      ]);
    } else {
      // ---- not sure: one-tap choices, else the PIN flow
      final villages = m.villageChoices;
      final towns = m.localBodyChoices;
      if (villages.isNotEmpty || towns.isNotEmpty) {
        children.add(const BiText('loc_choose_village'));
        children.add(const SizedBox(height: JanSpace.xs));
        for (final t in towns.take(4)) {
          children.add(_choiceTile(Icons.location_city_rounded, t.label, null, () => _choose(body: t)));
        }
        for (final v in villages.take(8)) {
          children.add(_choiceTile(Icons.holiday_village_outlined, v.name, v.detail, () => _choose(village: v)));
        }
      }
      children.add(Align(
        alignment: Alignment.centerLeft,
        child: TextButton.icon(
          onPressed: widget.onManual,
          icon: const Icon(Icons.pin_drop_outlined),
          label: Text(AppStrings.of('loc_enter_pin')),
        ),
      ));
    }
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: children);
  }

  Widget _choiceTile(IconData icon, String title, String? subtitle, VoidCallback onTap) => Card(
        margin: const EdgeInsets.only(bottom: JanSpace.xs),
        child: ListTile(
          leading: Icon(icon, color: JanColors.navy),
          title: Text(title, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
          subtitle: subtitle == null || subtitle.isEmpty ? null : Text(subtitle),
          trailing: const Icon(Icons.chevron_right_rounded),
          onTap: onTap,
        ),
      );

  /// Ward: automatic when a ward map proves it, else a one-tap list (optional;
  /// the office checks a ward the citizen picked).
  Widget _wardChooser(IncidentArea accepted) {
    final m = _match;
    if (accepted.wardId != null && m.ward != null && m.ward!.wardId == accepted.wardId && m.wardMatch == 'BOUNDARY') {
      return Row(children: [
        const Icon(Icons.verified_rounded, color: JanColors.teal, size: 20),
        const SizedBox(width: JanSpace.xs),
        Expanded(child: Text('${accepted.wardName} - from the ward map', style: const TextStyle(color: JanColors.teal, fontWeight: FontWeight.w600))),
      ]);
    }
    if (_loadingWards) return const LinearProgressIndicator();
    final wards = _wards ?? const <WardRef>[];
    if (wards.isEmpty) {
      return const Text('No ward list is loaded for this place yet - the office will assign the ward.',
          style: TextStyle(color: JanColors.muted));
    }
    final suggested = m.suggestedWard;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      const BiText('loc_choose_ward', style: TextStyle(fontSize: 15.5, fontWeight: FontWeight.w700)),
      const SizedBox(height: JanSpace.xs),
      Text(
        suggested != null
            ? 'There is no ward map for this spot. ${suggested.shortLabel} is nearby - choose your ward if you know it. The office will check it.'
            : 'There is no ward map for this spot. Choose your ward if you know it - the office will check it.',
        style: const TextStyle(color: JanColors.muted),
      ),
      const SizedBox(height: JanSpace.xs),
      Wrap(spacing: JanSpace.xs, runSpacing: JanSpace.xs, children: [
        for (final w in wards)
          ChoiceChip(
            label: Text(w.shortLabel),
            avatar: suggested?.wardId == w.wardId ? const Icon(Icons.near_me_outlined, size: 16) : null,
            tooltip: trs(w.localities.isEmpty ? w.name : '${w.name}: ${w.localities.join(', ')}'),
            selected: accepted.wardId == w.wardId,
            onSelected: (_) => _chooseWard(w),
          ),
        ChoiceChip(
          label: Text(AppStrings.of('loc_ward_unknown')),
          selected: accepted.wardId == null,
          onSelected: (_) => _chooseWard(null),
        ),
      ]),
    ]);
  }
}
