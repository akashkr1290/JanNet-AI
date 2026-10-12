import 'dart:math' show Point;

import 'package:flutter/foundation.dart' show listEquals;
import 'package:flutter/material.dart';
import 'package:maplibre_gl/maplibre_gl.dart' as ml;

import '../../features/complaints/location/incident_location.dart';
import '../theme/jan_tokens.dart';
import 'maps_config.dart';

/// Extra dots on the map (the fixed red "problem" pin is drawn by the screen
/// on top of the map, not here, so it stays in the centre while the map moves).
enum JanMarkerKind { photo, you }

class JanMarker {
  final GeoPoint point;
  final JanMarkerKind kind;
  const JanMarker(this.point, this.kind);

  @override
  bool operator ==(Object other) => other is JanMarker && other.point == point && other.kind == kind;

  @override
  int get hashCode => Object.hash(point, kind);
}

/// Lets a screen move the map (search result picked, "You are here",
/// PIN / village / ward chosen in the manual flow).
class JanMapController {
  void Function(GeoPoint point, double zoom)? _move;

  void moveTo(GeoPoint point, double zoom) => _move?.call(point, zoom);
}

/// One map widget for the whole app, so screens never depend on the map
/// provider (V36: MapLibre GL + OpenFreeMap vector tiles, no API key -
/// core/maps/maps_config.dart). Used by:
///  * location/incident_location_picker_screen.dart - interactive, fixed pin;
///  * location/incident_map.dart (IncidentMapPreview) - small read-only preview.
///
/// [outline] draws the ward boundary (rings of points, from
/// GET /geo/wards/{id}/boundary) so the citizen sees which ward the pin is in.
/// [onCameraMove] reports the centre/zoom while moving; [onCameraIdle] fires
/// once the map stops (the picker then asks the backend for the area).
class JanMap extends StatefulWidget {
  final GeoPoint center;
  final double zoom;
  final JanMapController? controller;
  final List<JanMarker> markers;
  final List<List<GeoPoint>> outline;
  final bool interactive;
  final void Function(GeoPoint center, double zoom)? onCameraMove;
  final VoidCallback? onCameraIdle;
  final void Function(GeoPoint point)? onTap;

  const JanMap({
    super.key,
    required this.center,
    required this.zoom,
    this.controller,
    this.markers = const [],
    this.outline = const [],
    this.interactive = true,
    this.onCameraMove,
    this.onCameraIdle,
    this.onTap,
  });

  @override
  State<JanMap> createState() => _JanMapState();
}

class _JanMapState extends State<JanMap> {
  ml.MapLibreMapController? _controller;
  bool _styleReady = false;

  static ml.LatLng _l(GeoPoint p) => ml.LatLng(p.latitude, p.longitude);

  /// '#RRGGBB' - MapLibre annotation colours are CSS strings.
  static String _hex(Color c) => '#${(c.toARGB32() & 0xFFFFFF).toRadixString(16).padLeft(6, '0')}';

  @override
  void initState() {
    super.initState();
    _bindController();
  }

  void _bindController() {
    widget.controller?._move = (p, z) =>
        _controller?.animateCamera(ml.CameraUpdate.newLatLngZoom(_l(p), z), duration: const Duration(milliseconds: 600));
  }

  @override
  void didUpdateWidget(JanMap old) {
    super.didUpdateWidget(old);
    if (old.controller != widget.controller) _bindController();
    if (!listEquals(old.markers, widget.markers) || !_sameOutline(old.outline, widget.outline)) _redraw();
  }

  static bool _sameOutline(List<List<GeoPoint>> a, List<List<GeoPoint>> b) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (!listEquals(a[i], b[i])) return false;
    }
    return true;
  }

  /// Markers and the ward outline are annotations: cleared and drawn again on change.
  Future<void> _redraw() async {
    final c = _controller;
    if (c == null || !_styleReady) return;
    try {
      await c.clearFills();
      await c.clearLines();
      await c.clearCircles();
      for (final ring in widget.outline) {
        if (ring.length < 3) continue;
        final pts = ring.map(_l).toList();
        await c.addFill(ml.FillOptions(geometry: [pts], fillColor: _hex(JanColors.primary), fillOpacity: 0.10));
        await c.addLine(ml.LineOptions(geometry: pts, lineColor: _hex(JanColors.navy), lineWidth: 2.5, lineOpacity: 0.9));
      }
      for (final m in widget.markers) {
        final photo = m.kind == JanMarkerKind.photo;
        await c.addCircle(ml.CircleOptions(
          geometry: _l(m.point),
          circleRadius: photo ? 7 : 8,
          circleColor: _hex(photo ? JanColors.white : JanColors.primary),
          circleStrokeColor: _hex(photo ? JanColors.navy : JanColors.white),
          circleStrokeWidth: 3,
        ));
      }
    } catch (_) {
      // the map was closed while drawing - nothing to do
    }
  }

  @override
  Widget build(BuildContext context) {
    final interactive = widget.interactive;
    return ml.MapLibreMap(
      styleString: MapsConfig.styleUrl,
      initialCameraPosition: ml.CameraPosition(target: _l(widget.center), zoom: widget.zoom),
      trackCameraPosition: true,
      compassEnabled: false,
      rotateGesturesEnabled: false,
      tiltGesturesEnabled: false,
      scrollGesturesEnabled: interactive,
      zoomGesturesEnabled: interactive,
      dragEnabled: interactive,
      doubleClickZoomEnabled: interactive,
      attributionButtonPosition: ml.AttributionButtonPosition.bottomRight,
      annotationOrder: const [ml.AnnotationType.fill, ml.AnnotationType.line, ml.AnnotationType.circle],
      annotationConsumeTapEvents: const [ml.AnnotationType.circle],
      onMapCreated: (c) => _controller = c,
      onStyleLoadedCallback: () {
        _styleReady = true;
        _redraw();
      },
      onCameraMove: widget.onCameraMove == null
          ? null
          : (pos) => widget.onCameraMove!(GeoPoint(pos.target.latitude, pos.target.longitude), pos.zoom),
      onCameraIdle: widget.onCameraIdle,
      onMapClick: widget.onTap == null || !interactive
          ? null
          : (Point<double> _, ml.LatLng p) => widget.onTap!(GeoPoint(p.latitude, p.longitude)),
    );
  }
}
