import 'package:flutter/material.dart' hide Text;

import '../../../core/api/geo_api.dart';
import '../../../core/theme/jan_tokens.dart';
import '../../complaints/location/geo_models.dart';
import '../../../core/l10n/tr_text.dart';
import '../../../core/widgets/error_text.dart';

/// The area chosen in an Admin form (officer / department head account,
/// department, ward). Codes are LGD codes; sent to the backend as
/// districtCode / localBodyCode / wardId and checked there
/// (AdminUserService#staffArea, DepartmentAdminService#area, WardAdminService).
class StaffArea {
  final DistrictRef? district;
  final LocalBodyRef? body;
  final WardRef? ward;

  /// V37: a development block (department form only). A department serves a
  /// block OR a Gram Panchayat / town, so choosing one clears the other.
  final BlockRef? block;
  const StaffArea({this.district, this.body, this.ward, this.block});

  static const none = StaffArea();

  String get summary => [
        if (ward != null) ward!.name,
        if (body != null) body!.label,
        if (block != null) '${block!.name} block',
        if (district != null) '${district!.name}, ${district!.stateName}',
      ].join(' · ');
}

/// Admin location picker - the same LGD lists the citizen uses, so a place is
/// CHOSEN, never typed: PIN code (quick fill) or district -> Gram Panchayat /
/// Nagar Nigam -> ward. Example: a new officer for Jolah Bigha -> PIN 824236
/// -> district Gaya -> "Pakri (Gram Panchayat)" -> ward.
///
/// Used in screens/admin_user_management_screen.dart (Add User) and
/// screens/admin_configuration_screen.dart (department, ward dialogs).
/// [showWard] false hides the ward step (departments serve a whole body).
/// [showBlock] true adds the development block step (V37, departments only).
/// [showState] true starts with a state list and then that state's districts
/// (State -> District -> Block), instead of only the served districts.
class StaffAreaPicker extends StatefulWidget {
  final StaffArea value;
  final ValueChanged<StaffArea> onChanged;
  final bool showWard;
  final bool showBlock;
  final bool showState;

  const StaffAreaPicker({
    super.key,
    required this.value,
    required this.onChanged,
    this.showWard = true,
    this.showBlock = false,
    this.showState = false,
  });

  @override
  State<StaffAreaPicker> createState() => _StaffAreaPickerState();
}

class _StaffAreaPickerState extends State<StaffAreaPicker> {
  final _pin = TextEditingController();
  List<StateRef> _states = const [];
  int? _stateCode;
  List<DistrictRef> _districts = const [];
  List<LocalBodyRef> _bodies = const [];
  List<WardRef> _wards = const [];
  List<BlockRef> _blocks = const [];
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _loadDistricts();
  }

  @override
  void dispose() {
    _pin.dispose();
    super.dispose();
  }

  Future<void> _run(Future<void> Function() task) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await task();
    } catch (_) {
      if (mounted) setState(() => _error = 'Could not load the list. Check the connection.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// Served districts first (GET /geo/districts); the PIN box covers any other district.
  /// With [StaffAreaPicker.showState]: the states, then the chosen state's districts.
  Future<void> _loadDistricts() => _run(() async {
        final d = widget.value.district;
        if (widget.showState) {
          final states = await GeoApi.instance.states();
          if (mounted) setState(() => _states = states);
          if (d == null) return;
          _stateCode = d.stateCode;
        }
        final list = await GeoApi.instance.districts(stateCode: widget.showState ? d!.stateCode : null);
        if (mounted) setState(() => _districts = list);
        if (d != null) await _loadBodies(d, keep: true);
      });

  /// State chosen: its districts, and the area starts again.
  Future<void> _chooseState(int? code) => _run(() async {
        setState(() {
          _stateCode = code;
          _districts = const [];
          _blocks = const [];
          _bodies = const [];
          _wards = const [];
        });
        widget.onChanged(StaffArea.none);
        if (code == null) return;
        final list = await GeoApi.instance.districts(stateCode: code);
        if (mounted) setState(() => _districts = list);
      });

  Future<void> _byPin() => _run(() async {
        final pin = _pin.text.trim();
        if (!RegExp(r'^[1-9][0-9]{5}$').hasMatch(pin)) {
          setState(() => _error = 'Enter a 6-digit PIN code.');
          return;
        }
        final area = await GeoApi.instance.pincode(pin);
        final d = area.district;
        if (d == null) {
          setState(() => _error = 'PIN code not found.');
          return;
        }
        if (widget.showState && _stateCode != d.stateCode) {
          final list = await GeoApi.instance.districts(stateCode: d.stateCode);
          if (mounted) {
            setState(() {
              _stateCode = d.stateCode;
              _districts = list;
            });
          }
        }
        if (!_districts.any((x) => x.code == d.code)) setState(() => _districts = [..._districts, d]);
        widget.onChanged(StaffArea(district: d));
        await _loadBodies(d);
      });

  Future<void> _loadBodies(DistrictRef d, {bool keep = false}) async {
    final bodies = await GeoApi.instance.localBodies(d.code);
    final blocks = widget.showBlock ? await GeoApi.instance.blocks(d.code) : const <BlockRef>[];
    if (!mounted) return;
    setState(() {
      _bodies = bodies;
      _blocks = blocks;
      if (!keep) _wards = const [];
    });
    final b = widget.value.body;
    if (keep && b != null && widget.showWard) {
      final wards = await GeoApi.instance.wards(b.code);
      if (mounted) setState(() => _wards = wards);
    }
  }

  @override
  Widget build(BuildContext context) {
    final v = widget.value;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, mainAxisSize: MainAxisSize.min, children: [
      const Padding(
        padding: EdgeInsets.only(top: 8, bottom: 4),
        child: Text('Work area', style: TextStyle(fontWeight: FontWeight.w800, color: JanColors.navy)),
      ),
      Row(children: [
        Expanded(
          child: TextField(
            controller: _pin,
            keyboardType: TextInputType.number,
            maxLength: 6,
            decoration: InputDecoration(labelText: trs('PIN code (quick fill)'), counterText: ''),
            onSubmitted: (_) => _byPin(),
          ),
        ),
        IconButton(tooltip: trs('Find'), onPressed: _busy ? null : _byPin, icon: const Icon(Icons.search)),
      ]),
      if (widget.showState)
        DropdownButtonFormField<int>(
          // re-created when the PIN quick fill picks another state
          key: ValueKey('state-$_stateCode-${_states.length}'),
          initialValue: _states.any((s) => s.code == _stateCode) ? _stateCode : null,
          isExpanded: true,
          decoration: InputDecoration(labelText: trs('State')),
          items: [for (final s in _states) DropdownMenuItem(value: s.code, child: Text(s.name))],
          onChanged: _chooseState,
        ),
      if (!widget.showState || _stateCode != null)
        DropdownButtonFormField<int>(
          // re-created when the district changes elsewhere (PIN quick fill)
          key: ValueKey('district-${v.district?.code}-${_districts.length}'),
          initialValue: _districts.any((d) => d.code == v.district?.code) ? v.district!.code : null,
          isExpanded: true,
          decoration: InputDecoration(labelText: trs('District')),
          items: [
            for (final d in _districts)
              DropdownMenuItem(value: d.code, child: Text(widget.showState ? d.name : '${d.name}, ${d.stateName}')),
          ],
          onChanged: (code) {
            final d = _districts.firstWhere((x) => x.code == code);
            widget.onChanged(StaffArea(district: d));
            _run(() => _loadBodies(d));
          },
        ),
      if (widget.showBlock && v.district != null && _blocks.isNotEmpty)
        DropdownButtonFormField<int?>(
          // re-created when the body is chosen (that clears the block)
          key: ValueKey('block-${v.district!.code}-${_blocks.length}-${v.block?.code}-${v.body?.code}'),
          initialValue: v.block?.code,
          isExpanded: true,
          decoration: InputDecoration(
            labelText: trs('Block (optional)'),
            helperText: trs('Choose a block, or a Gram Panchayat / town below - not both'),
          ),
          items: [
            const DropdownMenuItem<int?>(value: null, child: Text('No block')),
            for (final b in _blocks) DropdownMenuItem<int?>(value: b.code, child: Text(b.name)),
          ],
          onChanged: (code) => widget.onChanged(StaffArea(
              district: v.district, block: code == null ? null : _blocks.firstWhere((b) => b.code == code))),
        ),
      if (v.district != null)
        Autocomplete<LocalBodyRef>(
          // re-created when a block is chosen (that clears the body)
          key: ValueKey('bodies-${v.district!.code}-${_bodies.length}-${v.block?.code}'),
          initialValue: TextEditingValue(text: v.body?.label ?? ''),
          displayStringForOption: (b) => b.label,
          optionsBuilder: (text) {
            final q = text.text.toLowerCase().trim();
            return q.isEmpty ? _bodies.take(50) : _bodies.where((b) => b.label.toLowerCase().contains(q)).take(50);
          },
          fieldViewBuilder: (context, controller, focus, onSubmit) => TextField(
            controller: controller,
            focusNode: focus,
            decoration: InputDecoration(
              labelText: trs('Gram Panchayat / Nagar Nigam'),
              helperText: trs('Type a few letters, then choose'),
            ),
          ),
          onSelected: (b) => _run(() async {
            widget.onChanged(StaffArea(district: v.district, body: b));
            if (widget.showWard) {
              final wards = await GeoApi.instance.wards(b.code);
              if (mounted) setState(() => _wards = wards);
            }
          }),
        ),
      if (widget.showWard && v.body != null)
        _wards.isEmpty
            ? const Padding(
                padding: EdgeInsets.only(top: 6),
                child: Text('No wards loaded for this body yet (LGD ward list not imported).',
                    style: TextStyle(color: JanColors.muted, fontSize: 12.5)),
              )
            : DropdownButtonFormField<int?>(
                key: ValueKey('ward-${v.body?.code}'),
                initialValue: v.ward?.wardId,
                isExpanded: true,
                decoration: InputDecoration(labelText: trs('Ward (optional)')),
                items: [
                  const DropdownMenuItem<int?>(value: null, child: Text('Whole body')),
                  for (final w in _wards) DropdownMenuItem<int?>(value: w.wardId, child: Text(w.name)),
                ],
                onChanged: (id) => widget.onChanged(StaffArea(
                    district: v.district, body: v.body, ward: id == null ? null : _wards.firstWhere((w) => w.wardId == id))),
              ),
      if (_busy) const Padding(padding: EdgeInsets.only(top: 6), child: LinearProgressIndicator()),
      if (_error != null) Padding(padding: const EdgeInsets.only(top: 6), child: ErrorText(_error!)),
      if (v.summary.isNotEmpty)
        Padding(
          padding: const EdgeInsets.only(top: 6),
          child: Text(v.summary, style: const TextStyle(color: JanColors.teal, fontWeight: FontWeight.w700)),
        ),
    ]);
  }
}
