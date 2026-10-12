import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart' hide Text;

import '../../../core/api/geo_api.dart';
import '../../../core/l10n/app_strings.dart';
import '../../../core/l10n/bi_text.dart';
import '../../../core/maps/jan_map.dart';
import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_states.dart';
import 'area_confirm_card.dart';
import 'device_position.dart';
import 'geo_models.dart';
import 'incident_location.dart';
import 'incident_map.dart';
import 'location_policy.dart';
import 'manual_area_panel.dart';
import '../../../core/l10n/tr_text.dart';

/// What the picker returns: the confirmed point and, when the citizen
/// accepted or chose it, its address / government area.
class IncidentPlace {
  final GeoPoint point;
  final IncidentArea? area;
  const IncidentPlace(this.point, this.area);
}

/// "Where is the problem?" - the incident-location step (V33, V34, V36).
///
/// Opened from complaint_submission_screen.dart (_openMap / manual flow).
/// The red pin is fixed in the centre; the citizen moves the map under it.
/// No map API key anywhere (V36): MapLibre + OpenFreeMap draw the map, our
/// backend answers every area question from its own dataset.
///  * Search box while typing (GeoApi.search): PIN, wards, post offices and
///    villages from our data, OpenStreetMap landmarks after that.
///  * When the map stops, the backend gives the pin's address and area from
///    the published boundaries (GeoApi.reverse); [AreaConfirmCard] asks
///    "Is this the place?". The ward's outline is drawn on the map.
///  * [startManual] (photo without location, citizen not at the place):
///    [ManualAreaPanel] - PIN -> village -> ward, the map following each answer.
/// "You are here" only moves the map; the citizen still decides where the pin
/// goes, because where they are now is not necessarily where the problem is.
///
/// Pops with an [IncidentPlace], or null when cancelled.
class IncidentLocationPickerScreen extends StatefulWidget {
  /// Where the map opens (automatic proposal or the previous confirmation).
  final GeoPoint? initial;

  /// The automatic (photo) location, shown as a small marker for reference.
  final GeoPoint? detected;

  /// Optional note shown above the map (e.g. imprecise GPS).
  final String? note;

  /// Open in the PIN -> village -> ward mode.
  final bool startManual;

  /// Photo without location and the citizen is not at the place: open with
  /// the place-name search ready ("Republic Crossing, Ghaziabad"). Most people
  /// do not know a PIN code; the PIN is still offered under the search box.
  final bool searchFirst;

  /// Area already accepted for [initial] (kept if the pin is not moved).
  final IncidentArea? initialArea;

  const IncidentLocationPickerScreen({
    super.key,
    this.initial,
    this.detected,
    this.note,
    this.startManual = false,
    this.searchFirst = false,
    this.initialArea,
  });

  @override
  State<IncidentLocationPickerScreen> createState() => _IncidentLocationPickerScreenState();
}

class _IncidentLocationPickerScreenState extends State<IncidentLocationPickerScreen> {
  // India overview, used only when nothing better is known.
  static const _country = GeoPoint(22.5, 79.0);
  static const _countryZoom = 4.5;

  final _map = JanMapController();
  final _searchController = TextEditingController();
  Timer? _typing;

  late GeoPoint _center;
  late double _zoom;
  bool _manual = false;

  List<PlaceResult>? _results; // null = no search shown
  bool _searching = false;

  // ward outline on the map: the ward the boundaries found, or the one chosen
  int? _outlineWardId;
  List<List<GeoPoint>> _outline = const [];
  String? _outlineLabel;

  GeoAddress? _address;
  GeoPoint? _addressFor; // the point _address describes
  bool _lookingUp = false;
  int _lookupId = 0;
  IncidentArea? _area;

  GeoPoint? _youAreHere;
  bool _locating = false;
  String? _message;

  @override
  void initState() {
    super.initState();
    _center = widget.initial ?? _country;
    _zoom = widget.initial != null ? 17 : _countryZoom;
    _manual = widget.startManual;
    _area = widget.initialArea;
    if (widget.initial != null && !_manual) {
      _addressFor = widget.initial;
      if (_area == null) _lookUpAddress();
    }
  }

  @override
  void dispose() {
    _searchController.dispose();
    _typing?.cancel();
    super.dispose();
  }

  void _moveTo(GeoPoint point, double zoom) {
    setState(() {
      _center = point;
      _zoom = zoom;
    });
    _map.moveTo(point, zoom);
  }

  // ---------------------------------------------------------------- search

  void _onTyping(String text) {
    _typing?.cancel();
    final q = text.trim();
    if (q.length < 2) {
      setState(() => _results = null);
      return;
    }
    // 350 ms after the last key: one request per pause, not per letter.
    _typing = Timer(const Duration(milliseconds: 350), () => _runSearch(q));
  }

  Future<void> _runSearch(String q) async {
    setState(() => _searching = true);
    try {
      final list = await GeoApi.instance.search(q, near: _zoom > _countryZoom
            ? _center
            : const GeoPoint(LocationPolicy.pilotSearchLat, LocationPolicy.pilotSearchLng));
      if (!mounted || _searchController.text.trim() != q) return;
      setState(() => _results = list);
    } catch (_) {
      if (mounted) setState(() => _results = const []);
    } finally {
      if (mounted) setState(() => _searching = false);
    }
  }

  void _pickResult(PlaceResult r) {
    FocusScope.of(context).unfocus();
    setState(() {
      _results = null;
      _searchController.text = r.label;
      _manual = false;
      _area = null;
    });
    _moveTo(r.point, r.zoom);
    // the camera move ends in _onIdle, which looks up the area of the new spot
  }

  // ---------------------------------------------------------------- address of the pin

  /// Map stopped: ask the backend for the address + area of the pin, unless
  /// it is the same place (Google is asked at most once per ~30 m move).
  void _onIdle() {
    if (_manual || _zoom < LocationPolicy.minConfirmZoom) return;
    if (_addressFor != null && distanceMeters(_addressFor!, _center) < 30) return;
    _addressFor = _center;
    _area = null; // the pin moved to another place
    _lookUpAddress();
  }

  Future<void> _lookUpAddress() async {
    final id = ++_lookupId;
    final point = _addressFor ?? _center;
    setState(() => _lookingUp = true);
    try {
      final a = await GeoApi.instance.reverse(point);
      if (mounted && id == _lookupId) {
        setState(() => _address = a);
        _showWard(a.area.ward);
      }
    } catch (_) {
      if (mounted && id == _lookupId) setState(() => _address = null);
    } finally {
      if (mounted && id == _lookupId) setState(() => _lookingUp = false);
    }
  }

  /// Draws the outline of [ward] on the map (null = remove it). Only wards with
  /// a boundary on record have one.
  Future<void> _showWard(WardRef? ward) async {
    if (ward?.wardId == _outlineWardId) return;
    _outlineWardId = ward?.wardId;
    if (ward == null || !ward.hasBoundary) {
      setState(() {
        _outline = const [];
        _outlineLabel = ward?.name;
      });
      return;
    }
    final rings = await GeoApi.instance.wardOutline(ward.wardId);
    if (!mounted || _outlineWardId != ward.wardId) return;
    setState(() {
      _outline = rings;
      _outlineLabel = ward.name;
    });
  }

  /// The citizen's area changed (Yes / list / manual flow): outline its ward.
  void _onAreaChanged(IncidentArea? a) {
    setState(() => _area = a);
    final id = a?.wardId;
    if (id == null) {
      _showWard(_address?.area.ward);
      return;
    }
    final known = [
      ...?_address?.area.wardChoices,
      if (_address?.area.ward != null) _address!.area.ward!,
    ].where((w) => w.wardId == id);
    _showWard(known.isNotEmpty ? known.first : WardRef(wardId: id, name: a!.wardName ?? 'Ward', hasBoundary: true));
  }

  // ---------------------------------------------------------------- you are here / confirm

  Future<void> _showWhereIAm() async {
    if (_locating) return;
    setState(() {
      _locating = true;
      _message = null;
    });
    final result = await DevicePosition.read(timeout: const Duration(seconds: 15));
    if (!mounted) return;
    setState(() {
      _locating = false;
      if (result.ok) {
        _youAreHere = result.point;
        _message = 'The map now shows where you are. If the problem is somewhere else, '
            'move the map so the red pin is on the problem.';
      } else {
        _message = '${DevicePosition.explain(result.failure!)} You can search for the place instead.';
      }
    });
    if (result.ok) _moveTo(result.point!, math.max(_zoom, LocationPolicy.pinZoom));
  }

  void _confirm() {
    if (_zoom < LocationPolicy.minConfirmZoom) {
      _moveTo(_center, LocationPolicy.pinZoom);
      setState(() => _message = 'Zoomed in - check that the red pin is exactly on the problem, then confirm.');
      return;
    }
    final point = GeoPoint(
      double.parse(_center.latitude.toStringAsFixed(6)),
      double.parse(_center.longitude.toStringAsFixed(6)),
    );
    Navigator.of(context).pop(IncidentPlace(point, _area));
  }

  // ---------------------------------------------------------------- UI

  @override
  Widget build(BuildContext context) {
    final zoomedIn = _zoom >= LocationPolicy.minConfirmZoom;
    return Scaffold(
      appBar: AppBar(title: Text(AppStrings.of('loc_where_problem'))),
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (widget.note != null)
              Padding(
                padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.xs, JanSpace.md, 0),
                child: JanBanner(tone: JanBannerTone.warning, icon: Icons.place_outlined, message: widget.note!),
              ),
            if (!_manual) _searchBox(),
            Expanded(
              flex: 11,
              child: Stack(children: [
                Positioned.fill(
                  child: JanMap(
                    controller: _map,
                    center: _center,
                    zoom: _zoom,
                    outline: _outline,
                    markers: [
                      if (widget.detected != null) JanMarker(widget.detected!, JanMarkerKind.photo),
                      if (_youAreHere != null) JanMarker(_youAreHere!, JanMarkerKind.you),
                    ],
                    onCameraMove: (c, z) {
                      _center = c;
                      if (z != _zoom) setState(() => _zoom = z);
                    },
                    onCameraIdle: _onIdle,
                    onTap: (p) => _moveTo(p, math.max(_zoom, LocationPolicy.pinZoom)),
                  ),
                ),
                // The fixed pin: the point under its tip is the incident location.
                const IgnorePointer(
                  child: Center(
                    child: Padding(
                      padding: EdgeInsets.only(bottom: 72),
                      child: SizedBox(
                        height: 72,
                        child: Column(mainAxisSize: MainAxisSize.min, children: [_PinLabel(), ProblemPin(size: 44)]),
                      ),
                    ),
                  ),
                ),
                Positioned(
                  right: JanSpace.sm,
                  top: JanSpace.sm,
                  child: Column(children: [
                    _MapButton(icon: Icons.add, tooltip: trs('Zoom in'), onPressed: () => _moveTo(_center, math.min(20, _zoom + 1))),
                    const SizedBox(height: JanSpace.xs),
                    _MapButton(icon: Icons.remove, tooltip: trs('Zoom out'), onPressed: () => _moveTo(_center, math.max(3, _zoom - 1))),
                  ]),
                ),
                if (_outlineLabel != null)
                  Positioned(
                    left: JanSpace.sm,
                    top: JanSpace.sm,
                    right: 64,
                    child: Align(
                      alignment: Alignment.centerLeft,
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                        decoration: BoxDecoration(color: JanColors.white, borderRadius: JanRadius.smAll, boxShadow: JanShadows.card),
                        child: Text(_outlineLabel!,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(fontWeight: FontWeight.w700, color: JanColors.navy)),
                      ),
                    ),
                  ),
                Positioned(
                  left: JanSpace.sm,
                  bottom: JanSpace.sm,
                  child: FilledButton.tonalIcon(
                    onPressed: _locating ? null : _showWhereIAm,
                    icon: _locating
                        ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Icon(Icons.my_location_rounded, size: 18),
                    label: const Text('You are here'),
                  ),
                ),
              ]),
            ),
            Expanded(
              flex: 9,
              child: SingleChildScrollView(
                padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.sm, JanSpace.md, JanSpace.sm),
                child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                  if (_manual)
                    ManualAreaPanel(
                      onMoveMap: _moveTo,
                      onChanged: _onAreaChanged,
                    )
                  else if (!zoomedIn)
                    const BiText('loc_move_pin', style: TextStyle(fontSize: 15.5, fontWeight: FontWeight.w700))
                  else
                    AreaConfirmCard(
                      address: _address,
                      loading: _lookingUp,
                      value: _area,
                      onChanged: _onAreaChanged,
                      onManual: () => setState(() {
                        _manual = true;
                        _area = null;
                      }),
                    ),
                  if (_message != null) ...[
                    const SizedBox(height: JanSpace.xs),
                    Semantics(liveRegion: true, child: Text(_message!, style: const TextStyle(color: JanColors.muted))),
                  ],
                ]),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(JanSpace.md, 0, JanSpace.md, JanSpace.md),
              child: Row(children: [
                if (_manual)
                  Padding(
                    padding: const EdgeInsets.only(right: JanSpace.xs),
                    child: TextButton(
                      onPressed: () {
                        setState(() {
                          _manual = false;
                          _area = null;
                          _addressFor = null;
                        });
                        _onIdle(); // address of where the pin is now
                      },
                      child: const Text('Use search'),
                    ),
                  ),
                Expanded(
                  child: FilledButton.icon(
                    style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(52)),
                    onPressed: _confirm,
                    icon: Icon(zoomedIn ? Icons.check_rounded : Icons.zoom_in_rounded),
                    label: Text(zoomedIn ? AppStrings.of('loc_confirm') : 'Zoom in to place the pin'),
                  ),
                ),
              ]),
            ),
          ],
        ),
      ),
    );
  }

  Widget _searchBox() => Padding(
        padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.xs, JanSpace.md, JanSpace.xs),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          TextField(
            controller: _searchController,
            autofocus: widget.searchFirst,
            textInputAction: TextInputAction.search,
            onChanged: _onTyping,
            onSubmitted: (q) {
              _typing?.cancel();
              if (q.trim().length >= 2) _runSearch(q.trim());
            },
            decoration: InputDecoration(
              hintText: trs(AppStrings.of(widget.searchFirst ? 'loc_search_area_hint' : 'loc_search_hint')),
              prefixIcon: const Icon(Icons.search),
              suffixIcon: _searching
                  ? const Padding(
                      padding: EdgeInsets.all(14),
                      child: SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)),
                    )
                  : (_searchController.text.isEmpty
                      ? null
                      : IconButton(
                          tooltip: trs('Clear'),
                          icon: const Icon(Icons.close_rounded),
                          onPressed: () => setState(() {
                            _searchController.clear();
                            _results = null;
                          }),
                        )),
            ),
          ),
          if (widget.searchFirst && _results == null)
            Align(
              alignment: Alignment.centerLeft,
              child: TextButton.icon(
                onPressed: () => setState(() {
                  _manual = true;
                  _area = null;
                }),
                icon: const Icon(Icons.pin_drop_outlined, size: 18),
                label: Text(AppStrings.of('loc_know_pin')),
              ),
            ),
          if (_results != null)
            ConstrainedBox(
              constraints: const BoxConstraints(maxHeight: 260),
              child: Material(
                elevation: 2,
                borderRadius: JanRadius.smAll,
                child: ListView(shrinkWrap: true, padding: EdgeInsets.zero, children: [
                  if (_results!.isEmpty)
                    const ListTile(dense: true, title: Text('No places found. Try another name or the PIN code, or move the map by hand.')),
                  for (final r in _results!)
                    ListTile(
                      dense: true,
                      leading: Icon(_kindIcon(r.kind), color: JanColors.navy),
                      title: Text(r.label, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w700)),
                      subtitle: r.secondary == null ? null : Text(r.secondary!, maxLines: 1, overflow: TextOverflow.ellipsis),
                      onTap: () => _pickResult(r),
                    ),
                  if (_results!.any((r) => r.source == 'OSM'))
                    const Padding(
                      padding: EdgeInsets.fromLTRB(16, 2, 16, 8),
                      child: Text('Landmarks: © OpenStreetMap contributors', style: TextStyle(fontSize: 11, color: JanColors.muted)),
                    ),
                ]),
              ),
            ),
        ]),
      );

  static IconData _kindIcon(String kind) => switch (kind) {
        'PINCODE' => Icons.pin_drop_outlined,
        'POST_OFFICE' => Icons.local_post_office_outlined,
        'WARD' => Icons.grid_view_rounded,
        'LOCAL_BODY' => Icons.location_city_rounded,
        'VILLAGE' => Icons.holiday_village_outlined,
        _ => Icons.place_outlined,
      };
}

class _PinLabel extends StatelessWidget {
  const _PinLabel();

  @override
  Widget build(BuildContext context) => Container(
        height: 28,
        padding: const EdgeInsets.symmetric(horizontal: 10),
        alignment: Alignment.center,
        decoration: BoxDecoration(color: JanColors.navy, borderRadius: JanRadius.smAll, boxShadow: JanShadows.card),
        child: const Text('Problem location',
            style: TextStyle(color: JanColors.white, fontWeight: FontWeight.w700, fontSize: 12.5)),
      );
}

class _MapButton extends StatelessWidget {
  final IconData icon;
  final String tooltip;
  final VoidCallback onPressed;
  const _MapButton({required this.icon, required this.tooltip, required this.onPressed});

  @override
  Widget build(BuildContext context) => Material(
        color: JanColors.white,
        shape: const CircleBorder(),
        elevation: 2,
        child: IconButton(tooltip: trs(tooltip), icon: Icon(icon), onPressed: onPressed),
      );
}
