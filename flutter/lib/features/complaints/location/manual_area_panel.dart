import 'dart:async';

import 'package:flutter/material.dart' hide Text;
import 'package:flutter/services.dart';

import '../../../core/api/geo_api.dart';
import '../../../core/l10n/app_strings.dart';
import '../../../core/l10n/bi_text.dart';
import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_states.dart';
import 'geo_models.dart';
import 'incident_location.dart';
import '../../../core/l10n/tr_text.dart';

/// The manual way to say where the problem is - used only when the photo has
/// no location and the citizen is NOT at the place now (or when the
/// automatic answer was wrong). Lives under the map in
/// incident_location_picker_screen.dart, so the red pin is visible at every
/// step and moves with each answer:
///
///   1. PIN code      -> suggestions while typing (GET /geo/pincodes?prefix=),
///                       then GET /geo/pincodes/{pin}: map jumps to the PIN area
///   2. village/town  -> one tap (search box for long lists, GET /geo/villages);
///                       map jumps to the village when its centre is known
///   3. ward          -> one tap from the body's wards (GET /geo/local-bodies/{code}/wards)
///   4. the citizen drags the map so the pin is on the exact spot, then confirms
///
/// [onMoveMap] moves the map (and with it the pin); [onChanged] reports the
/// area chosen so far. The phone's own position is never used here - the
/// citizen is somewhere else.
class ManualAreaPanel extends StatefulWidget {
  final void Function(GeoPoint point, double zoom) onMoveMap;
  final ValueChanged<IncidentArea?> onChanged;

  const ManualAreaPanel({super.key, required this.onMoveMap, required this.onChanged});

  @override
  State<ManualAreaPanel> createState() => _ManualAreaPanelState();
}

enum _Step { pin, place, body, ward, done }

class _ManualAreaPanelState extends State<ManualAreaPanel> {
  final _pinController = TextEditingController();
  final _searchController = TextEditingController();
  Timer? _searchDebounce;

  _Step _step = _Step.pin;
  bool _busy = false;
  String? _error;
  PincodeArea? _pinArea;
  List<VillageRef> _villages = const [];
  VillageRef? _village;
  LocalBodyRef? _body;
  List<WardRef> _wards = const [];
  WardRef? _ward;
  // PIN not in the loaded India Post list: choose the district instead
  List<DistrictRef>? _districtChoices;
  // PIN autocomplete while typing (3-5 digits)
  List<PincodeSuggestion> _pinSuggestions = const [];
  Timer? _pinDebounce;

  @override
  void dispose() {
    _pinController.dispose();
    _searchController.dispose();
    _searchDebounce?.cancel();
    _pinDebounce?.cancel();
    super.dispose();
  }

  void _onPinTyping(String text) {
    _pinDebounce?.cancel();
    final digits = text.trim();
    if (digits.length < 3 || digits.length > 5) {
      if (_pinSuggestions.isNotEmpty) setState(() => _pinSuggestions = const []);
      if (digits.length == 6) _lookUpPin();
      return;
    }
    _pinDebounce = Timer(const Duration(milliseconds: 300), () async {
      try {
        final list = await GeoApi.instance.pincodeSuggestions(digits);
        if (mounted && _pinController.text.trim() == digits) setState(() => _pinSuggestions = list.take(6).toList());
      } catch (_) {
        // offline: the citizen can still type all 6 digits
      }
    });
  }

  void _pickPinSuggestion(PincodeSuggestion p) {
    _pinController.text = p.pincode;
    setState(() => _pinSuggestions = const []);
    _lookUpPin();
  }

  Future<void> _lookUpPin() async {
    if (_busy) return;
    final pin = _pinController.text.trim();
    if (!RegExp(r'^[1-9][0-9]{5}$').hasMatch(pin)) {
      setState(() => _error = 'A PIN code has 6 digits, e.g. 824236.');
      return;
    }
    FocusScope.of(context).unfocus();
    setState(() {
      _busy = true;
      _error = null;
      _pinSuggestions = const [];
    });
    try {
      final area = await GeoApi.instance.pincode(pin);
      if (!mounted) return;
      if (area.district == null) {
        // The PIN list (pincode_offices) may not be loaded yet and Google may be
        // off: let the citizen pick the district, then search the village.
        final districts = await GeoApi.instance.districts();
        if (!mounted) return;
        setState(() {
          _districtChoices = districts;
          _error = districts.isEmpty
              ? 'This PIN code was not found. Please check it.'
              : 'This PIN code is not in our list yet - please choose the district.';
        });
        return;
      }
      if (area.centre != null) widget.onMoveMap(area.centre!, 13);
      setState(() {
        _pinArea = area;
        _villages = area.villages;
        _step = _Step.place;
      });
      widget.onChanged(areaFrom(district: area.district, pincode: pin));
    } catch (_) {
      if (mounted) setState(() => _error = 'Could not look up the PIN code. Check your internet and try again.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// District picked after an unknown PIN: same "place" step, villages searched in that district.
  Future<void> _pickDistrict(DistrictRef d) async {
    final pin = _pinController.text.trim();
    setState(() {
      _busy = true;
      _error = null;
      _districtChoices = null;
    });
    try {
      final villages = await GeoApi.instance.villages(d.code);
      final towns = await GeoApi.instance.localBodies(d.code, kind: 'URBAN');
      if (!mounted) return;
      setState(() {
        _pinArea = PincodeArea(pincode: pin, district: d, villages: villages, urbanBodies: towns);
        _villages = villages;
        _step = _Step.place;
      });
      widget.onChanged(areaFrom(district: d, pincode: pin));
    } catch (_) {
      if (mounted) setState(() => _error = 'Could not load the villages. Check your internet and try again.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _onSearchChanged(String text) {
    _searchDebounce?.cancel();
    _searchDebounce = Timer(const Duration(milliseconds: 350), () async {
      final district = _pinArea?.district;
      if (district == null) return;
      final q = text.trim();
      try {
        final list = q.isEmpty ? _pinArea!.villages : await GeoApi.instance.villages(district.code, query: q);
        if (mounted && _searchController.text.trim() == q) setState(() => _villages = list);
      } catch (_) {}
    });
  }

  Future<void> _pickVillage(VillageRef v) async {
    if (v.centre != null) widget.onMoveMap(v.centre!, 15);
    setState(() => _village = v);
    if (v.localBodies.length == 1) {
      await _pickBody(v.localBodies.first);
    } else if (v.localBodies.length > 1) {
      setState(() => _step = _Step.body);
    } else {
      // LGD has no Gram Panchayat for this village: no ward list either
      setState(() {
        _body = null;
        _step = _Step.done;
      });
      _report();
    }
  }

  Future<void> _pickBody(LocalBodyRef b) async {
    setState(() {
      _body = b;
      _busy = true;
    });
    _report();
    try {
      final wards = await GeoApi.instance.wards(b.code);
      if (!mounted) return;
      setState(() {
        _wards = wards;
        _step = wards.isEmpty ? _Step.done : _Step.ward;
      });
    } catch (_) {
      if (mounted) setState(() => _step = _Step.done);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _pickWard(WardRef? w) {
    if (w?.centre != null) widget.onMoveMap(w!.centre!, 16);
    setState(() {
      _ward = w;
      _step = _Step.done;
    });
    _report();
  }

  void _report() => widget.onChanged(areaFrom(
        district: _pinArea?.district,
        village: _village,
        body: _body,
        ward: _ward,
        pincode: _pinArea?.pincode,
      ));

  void _restart() {
    setState(() {
      _step = _Step.pin;
      _pinArea = null;
      _village = null;
      _body = null;
      _ward = null;
      _wards = const [];
      _districtChoices = null;
      _searchController.clear();
    });
    widget.onChanged(null);
  }

  @override
  Widget build(BuildContext context) {
    final children = <Widget>[];
    if (_error != null) {
      children.add(JanBanner(tone: JanBannerTone.warning, icon: Icons.error_outline, message: _error!));
      children.add(const SizedBox(height: JanSpace.xs));
    }
    switch (_step) {
      case _Step.pin:
        children.addAll([
          const BiText('loc_enter_pin'),
          const SizedBox(height: JanSpace.xs),
          Row(children: [
            Expanded(
              child: TextField(
                controller: _pinController,
                keyboardType: TextInputType.number,
                maxLength: 6,
                inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                style: const TextStyle(fontSize: 22, letterSpacing: 4, fontWeight: FontWeight.w700),
                decoration: InputDecoration(counterText: '', hintText: '824236', prefixIcon: Icon(Icons.pin_drop_outlined)),
                onChanged: _onPinTyping,
                onSubmitted: (_) => _lookUpPin(),
              ),
            ),
            const SizedBox(width: JanSpace.sm),
            FilledButton(
              style: FilledButton.styleFrom(minimumSize: const Size(72, 56)),
              onPressed: _busy ? null : _lookUpPin,
              child: _busy
                  ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.arrow_forward_rounded),
            ),
          ]),
          for (final p in _pinSuggestions)
            _Choice(
              icon: Icons.pin_drop_outlined,
              title: p.pincode,
              subtitle: [p.officeName, p.districtName, p.stateName].whereType<String>().join(' · '),
              onTap: () => _pickPinSuggestion(p),
            ),
          if (_districtChoices != null)
            for (final d in _districtChoices!)
              _Choice(icon: Icons.map_outlined, title: d.name, subtitle: d.stateName, onTap: () => _pickDistrict(d)),
        ]);
      case _Step.place:
        final area = _pinArea!;
        children.addAll([
          _Crumb('${area.pincode} · ${area.district!.name}', onEdit: _restart),
          if (!area.district!.served) ...[
            JanBanner(tone: JanBannerTone.warning, icon: Icons.info_outline, message: AppStrings.of('loc_not_served')),
            const SizedBox(height: JanSpace.xs),
          ],
          const BiText('loc_choose_village'),
          const SizedBox(height: JanSpace.xs),
          TextField(
            controller: _searchController,
            onChanged: _onSearchChanged,
            decoration: InputDecoration(prefixIcon: const Icon(Icons.search), hintText: trs(AppStrings.of('loc_search_village'))),
          ),
          const SizedBox(height: JanSpace.xs),
          for (final t in area.towns)
            _Choice(icon: Icons.location_city_rounded, title: t.label, onTap: () {
              _village = null;
              _pickBody(t);
            }),
          for (final v in _villages.take(30))
            _Choice(icon: Icons.holiday_village_outlined, title: v.name, subtitle: v.detail, onTap: () => _pickVillage(v)),
          if (_villages.isEmpty && area.towns.isEmpty)
            const Text('Type the village name above to find it.', style: TextStyle(color: JanColors.muted)),
        ]);
      case _Step.body:
        children.addAll([
          _Crumb('${_village!.name} · ${_pinArea?.district?.name ?? ''}', onEdit: () => setState(() => _step = _Step.place)),
          const Text('This village is split between these - choose the one for the problem spot:',
              style: TextStyle(fontWeight: FontWeight.w600)),
          const SizedBox(height: JanSpace.xs),
          for (final b in _village!.localBodies) _Choice(icon: Icons.account_balance_outlined, title: b.label, onTap: () => _pickBody(b)),
        ]);
      case _Step.ward:
        children.addAll([
          _Crumb([_village?.name, _body?.label].whereType<String>().join(' · '), onEdit: () => setState(() => _step = _Step.place)),
          const BiText('loc_choose_ward'),
          const SizedBox(height: JanSpace.xs),
          Wrap(spacing: JanSpace.xs, runSpacing: JanSpace.xs, children: [
            for (final w in _wards)
              ActionChip(
                label: Text(w.wardNumber != null ? 'Ward ${w.wardNumber}' : w.name),
                tooltip: trs(w.localities.isEmpty ? w.name : '${w.name}: ${w.localities.join(', ')}'),
                onPressed: () => _pickWard(w),
              ),
            ActionChip(label: Text(AppStrings.of('loc_ward_unknown')), onPressed: () => _pickWard(null)),
          ]),
        ]);
      case _Step.done:
        children.addAll([
          _Crumb(
            [_village?.name, _body?.label, _ward?.name, _pinArea?.district?.name].whereType<String>().join(' · '),
            onEdit: () => setState(() => _step = _Step.place),
          ),
          const BiText('loc_move_pin', style: TextStyle(fontSize: 15.5, fontWeight: FontWeight.w700, color: JanColors.navy)),
        ]);
    }
    if (_busy && _step != _Step.pin) children.add(const LinearProgressIndicator());
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: children);
  }
}

class _Crumb extends StatelessWidget {
  final String text;
  final VoidCallback onEdit;
  const _Crumb(this.text, {required this.onEdit});

  @override
  Widget build(BuildContext context) => Row(children: [
        const Icon(Icons.check_circle_rounded, color: JanColors.teal, size: 20),
        const SizedBox(width: JanSpace.xs),
        Expanded(child: Text(text, style: const TextStyle(fontWeight: FontWeight.w700))),
        TextButton(onPressed: onEdit, child: const Text('Change')),
      ]);
}

class _Choice extends StatelessWidget {
  final IconData icon;
  final String title;
  final String? subtitle;
  final VoidCallback onTap;
  const _Choice({required this.icon, required this.title, this.subtitle, required this.onTap});

  @override
  Widget build(BuildContext context) => Card(
        margin: const EdgeInsets.only(bottom: JanSpace.xs),
        child: ListTile(
          leading: Icon(icon, color: JanColors.navy),
          title: Text(title, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
          subtitle: subtitle == null || subtitle!.isEmpty ? null : Text(subtitle!),
          trailing: const Icon(Icons.chevron_right_rounded),
          onTap: onTap,
        ),
      );
}
