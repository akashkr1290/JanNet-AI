import 'dart:convert';

import '../../features/complaints/location/geo_models.dart';
import '../../features/complaints/location/incident_location.dart';
import 'api_client.dart';

/// Calls backend controller/GeoController.java (GET /api/v1/geo/...).
/// V36: everything works without any map API key - the backend answers from
/// our own dataset (LGD areas, India Post PINs, published ward / village / PIN
/// boundaries) and asks OpenStreetMap only for road and landmark names.
///
/// Used by: location/incident_location_picker_screen.dart (search, area of
/// the pin, ward outline), location/manual_area_panel.dart (PIN -> village ->
/// ward), location/area_confirm_card.dart (ward lists) and
/// admin/widgets/staff_area_picker.dart (Admin forms).
class GeoApi {
  GeoApi._();
  static final GeoApi instance = GeoApi._();

  final _client = ApiClient.instance;

  /// GET /geo/search - PIN, our wards / post offices / villages, then
  /// OpenStreetMap landmarks. [near] (the map centre) prefers nearby landmarks.
  Future<List<PlaceResult>> search(String text, {GeoPoint? near}) async {
    final json = await _client.get('/geo/search', query: {
      'q': text,
      if (near != null) 'lat': near.latitude.toStringAsFixed(5),
      if (near != null) 'lng': near.longitude.toStringAsFixed(5),
    }) as List<dynamic>;
    return json.map((e) => PlaceResult.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// GET /geo/reverse - address line + area (state ... ward, PIN) of the pin.
  Future<GeoAddress> reverse(GeoPoint p) async {
    final json = await _client.get('/geo/reverse', query: {
      'lat': p.latitude.toStringAsFixed(6),
      'lng': p.longitude.toStringAsFixed(6),
    }) as Map<String, dynamic>;
    return GeoAddress.fromJson(json);
  }

  /// GET /geo/pincodes?prefix= - PIN autocomplete (3 to 5 digits typed).
  Future<List<PincodeSuggestion>> pincodeSuggestions(String prefix) async {
    final json = await _client.get('/geo/pincodes', query: {'prefix': prefix}) as List<dynamic>;
    return json.map((e) => PincodeSuggestion.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// GET /geo/pincodes/{pin} - district, map centre, villages and bodies of a PIN.
  Future<PincodeArea> pincode(String pin) async {
    final json = await _client.get('/geo/pincodes/$pin') as Map<String, dynamic>;
    return PincodeArea.fromJson(json);
  }

  Future<List<StateRef>> states() async {
    final json = await _client.get('/geo/states') as List<dynamic>;
    return json.map((e) => StateRef.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// Districts of a state; [stateCode] null = the districts this deployment serves.
  Future<List<DistrictRef>> districts({int? stateCode}) async {
    final json = await _client.get('/geo/districts', query: {if (stateCode != null) 'stateCode': stateCode}) as List<dynamic>;
    return json.map((e) => DistrictRef.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// V37: development blocks of a district, by name (Admin department form).
  Future<List<BlockRef>> blocks(int districtCode) async {
    final json = await _client.get('/geo/blocks', query: {'districtCode': districtCode}) as List<dynamic>;
    return json.map((e) => BlockRef.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// [kind]: 'RURAL' (Gram Panchayats), 'URBAN' (Nagar Nigam / Parishad ...) or null for both.
  Future<List<LocalBodyRef>> localBodies(int districtCode, {String? kind, String? query}) async {
    final json = await _client.get('/geo/local-bodies', query: {
      'districtCode': districtCode,
      if (kind != null) 'kind': kind,
      if (query != null && query.isNotEmpty) 'q': query,
    }) as List<dynamic>;
    return json.map((e) => LocalBodyRef.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<List<WardRef>> wards(int localBodyCode) async {
    final json = await _client.get('/geo/local-bodies/$localBodyCode/wards') as List<dynamic>;
    return json.map((e) => WardRef.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// GET /geo/wards/{id}/boundary - the ward outline as rings of points
  /// (outer ring of each polygon). Empty when no boundary is on record.
  Future<List<List<GeoPoint>>> wardOutline(int wardId) async {
    try {
      final raw = await _client.getRaw('/geo/wards/$wardId/boundary');
      return outlineFromGeoJson(jsonDecode(raw));
    } catch (_) {
      return const [];
    }
  }

  Future<List<VillageRef>> villagesOfBody(int localBodyCode) async {
    final json = await _client.get('/geo/local-bodies/$localBodyCode/villages') as List<dynamic>;
    return json.map((e) => VillageRef.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// Village search inside a district ("jolhabigha" also finds "Jolah Bigha").
  Future<List<VillageRef>> villages(int districtCode, {String? query}) async {
    final json = await _client.get('/geo/villages', query: {
      'districtCode': districtCode,
      if (query != null && query.isNotEmpty) 'q': query,
    }) as List<dynamic>;
    return json.map((e) => VillageRef.fromJson(e as Map<String, dynamic>)).toList();
  }
}

/// Outer rings of a GeoJSON Polygon / MultiPolygon geometry as map points
/// (GeoJSON is [longitude, latitude]). Anything else gives an empty list.
List<List<GeoPoint>> outlineFromGeoJson(Object? geometry) {
  if (geometry is! Map) return const [];
  final type = geometry['type'];
  final coords = geometry['coordinates'];
  List<GeoPoint> ring(Object? r) => [
        for (final p in (r as List? ?? const []))
          if (p is List && p.length >= 2) GeoPoint((p[1] as num).toDouble(), (p[0] as num).toDouble()),
      ];
  if (type == 'Polygon' && coords is List && coords.isNotEmpty) return [ring(coords.first)];
  if (type == 'MultiPolygon' && coords is List) {
    return [
      for (final poly in coords)
        if (poly is List && poly.isNotEmpty) ring(poly.first),
    ];
  }
  return const [];
}
