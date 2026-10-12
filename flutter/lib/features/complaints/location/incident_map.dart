import 'package:flutter/material.dart';

import '../../../core/maps/jan_map.dart';
import '../../../core/theme/jan_tokens.dart';
import 'incident_location.dart';
import '../../../core/l10n/tr_text.dart';

/// The red "problem location" pin. Its tip is the bottom-centre of the icon.
/// Drawn ON TOP of the map (not as a map marker) so it stays fixed while the
/// citizen moves the map underneath - incident_location_picker_screen.dart and
/// [IncidentMapPreview].
class ProblemPin extends StatelessWidget {
  final double size;
  const ProblemPin({super.key, this.size = 44});

  @override
  Widget build(BuildContext context) =>
      Icon(Icons.location_on, size: size, color: JanColors.error, semanticLabel: trs('Problem location'));
}

/// Read-only map snippet of an incident location (submission summary,
/// complaint detail, location check card) - the same MapLibre map as the
/// picker (core/maps/jan_map.dart), with gestures off. [detected] adds the
/// photo-location marker when it is a different point; [outline] the ward
/// boundary when known.
class IncidentMapPreview extends StatelessWidget {
  final GeoPoint point;
  final GeoPoint? detected;
  final double height;
  final double zoom;
  final String semanticLabel;
  final List<List<GeoPoint>> outline;

  const IncidentMapPreview({
    super.key,
    required this.point,
    this.detected,
    this.height = 170,
    this.zoom = 16,
    this.semanticLabel = 'Map showing the problem location',
    this.outline = const [],
  });

  @override
  Widget build(BuildContext context) {
    final showDetected = detected != null && distanceMeters(detected!, point) > 5;
    return Semantics(
      label: trs(semanticLabel),
      container: true,
      child: ClipRRect(
        borderRadius: JanRadius.mdAll,
        child: SizedBox(
          height: height,
          child: Stack(
            children: [
              Positioned.fill(
                child: JanMap(
                  // a new point = a new preview (the map widget keeps its own camera)
                  key: ValueKey('${point.latitude},${point.longitude},${detected?.latitude},${detected?.longitude}'),
                  center: point,
                  zoom: zoom,
                  interactive: false,
                  markers: [if (showDetected) JanMarker(detected!, JanMarkerKind.photo)],
                  outline: outline,
                ),
              ),
              // pin tip on the centre point: icon bottom at the vertical centre
              const IgnorePointer(
                child: Center(
                  child: Padding(padding: EdgeInsets.only(bottom: 40), child: ProblemPin(size: 40)),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
