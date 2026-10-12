/// Which map picture the app shows (core/maps/jan_map.dart).
///
/// V36: MapLibre GL draws a vector map on the device from OpenFreeMap's free
/// tiles - no API key, no account, no request limit (openfreemap.org). The map
/// only DRAWS; every area answer (PIN, village / town, ward) comes from our
/// backend's own dataset (GET /api/v1/geo/...).
///
/// To use another style (e.g. our own self-hosted India tiles later) pass it at
/// build time - no code change:
///   flutter build web --dart-define=MAP_STYLE_URL=https://.../style.json
class MapsConfig {
  MapsConfig._();

  static const defaultStyleUrl = 'https://tiles.openfreemap.org/styles/liberty';

  static const styleUrl = String.fromEnvironment('MAP_STYLE_URL', defaultValue: defaultStyleUrl);
}
