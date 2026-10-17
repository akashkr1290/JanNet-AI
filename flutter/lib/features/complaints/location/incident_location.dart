import 'dart:math' as math;

import 'location_policy.dart';

/// Where the app's automatic location for a photo came from.
enum DetectedSource {
  /// Phone GPS read right after the photo was taken with the in-app camera.
  captureGps('CAPTURE_GPS'),

  /// The photo's own EXIF GPS (gallery / file).
  exif('EXIF');

  final String apiValue;
  const DetectedSource(this.apiValue);

  static DetectedSource? fromApi(String? v) =>
      DetectedSource.values.where((s) => s.apiValue == v).firstOrNull;
}

/// A point on the map.
class GeoPoint {
  final double latitude;
  final double longitude;
  const GeoPoint(this.latitude, this.longitude);

  Map<String, dynamic> toJson() => {'lat': latitude, 'lng': longitude};

  static GeoPoint? fromJson(Object? json) {
    if (json is! Map) return null;
    final lat = (json['lat'] as num?)?.toDouble();
    final lng = (json['lng'] as num?)?.toDouble();
    if (lat == null || lng == null) return null;
    return GeoPoint(lat, lng);
  }

  @override
  bool operator ==(Object other) => other is GeoPoint && other.latitude == latitude && other.longitude == longitude;

  @override
  int get hashCode => Object.hash(latitude, longitude);

  @override
  String toString() => 'GeoPoint($latitude, $longitude)';
}

/// The location the app found automatically for the photo (proposal only -
/// the citizen always confirms or corrects it on the map).
class DetectedLocation {
  final GeoPoint point;
  final DetectedSource source;

  /// GPS accuracy radius in metres (capture GPS only).
  final double? accuracyMeters;

  const DetectedLocation({required this.point, required this.source, this.accuracyMeters});

  bool get isLowAccuracy => accuracyMeters != null && accuracyMeters! > LocationPolicy.lowAccuracyMeters;

  Map<String, dynamic> toJson() => {
        'point': point.toJson(),
        'source': source.apiValue,
        'accuracy': accuracyMeters,
      };

  static DetectedLocation? fromJson(Object? json) {
    if (json is! Map) return null;
    final point = GeoPoint.fromJson(json['point']);
    final source = DetectedSource.fromApi(json['source'] as String?);
    if (point == null || source == null) return null;
    return DetectedLocation(point: point, source: source, accuracyMeters: (json['accuracy'] as num?)?.toDouble());
  }
}

/// V34: the address and government area of the incident point - PIN,
/// landmark, village / Gram Panchayat or Nagar Nigam, district, ward - as the
/// citizen confirmed it ("Is this place X?" -> Yes, or chosen from the
/// PIN / village / ward lists). Sent with the complaint as the backend's
/// IncidentArea fields (LocationService#applyArea checks the codes).
/// Built by area_confirm_card.dart / manual_area_panel.dart from the
/// backend's GeoAddress / AreaMatch (geo_models.dart).
class IncidentArea {
  final String? pincode;
  final String? landmark;
  final String? locality;
  final String? formattedAddress;
  final int? villageCode;
  final String? villageName;
  final int? localBodyCode;
  final String? localBodyName;
  final bool localBodyUrban;
  final String? districtName;

  /// V37 development block, display only (the backend works it out itself
  /// from the village / Gram Panchayat - it is not sent with the complaint).
  final String? blockName;

  /// false = the district is not served by this deployment (the app warns; the
  /// complaint is still accepted and flagged for Admin review).
  final bool districtServed;
  final int? wardId;
  final String? wardName;

  const IncidentArea({
    this.pincode,
    this.landmark,
    this.locality,
    this.formattedAddress,
    this.villageCode,
    this.villageName,
    this.localBodyCode,
    this.localBodyName,
    this.localBodyUrban = false,
    this.districtName,
    this.blockName,
    this.districtServed = true,
    this.wardId,
    this.wardName,
  });

  /// One line for the summary: "Jolah Bigha · Pakri GP · Gurua block · Ward 4 · Gaya".
  String get summary => [
        if (landmark != null && landmark!.isNotEmpty) landmark!,
        if (villageName != null) villageName! else if (locality != null && locality!.isNotEmpty) locality!,
        if (localBodyName != null) localBodyUrban ? localBodyName! : '${localBodyName!} GP',
        if (blockName != null && !localBodyUrban) '${blockName!} block',
        if (wardName != null) wardName!,
        if (districtName != null) districtName!,
        if (pincode != null) pincode!,
      ].join(' · ');

  IncidentArea withWard(int? id, String? name) => IncidentArea(
        pincode: pincode,
        landmark: landmark,
        locality: locality,
        formattedAddress: formattedAddress,
        villageCode: villageCode,
        villageName: villageName,
        localBodyCode: localBodyCode,
        localBodyName: localBodyName,
        localBodyUrban: localBodyUrban,
        districtName: districtName,
        blockName: blockName,
        districtServed: districtServed,
        wardId: id,
        wardName: name,
      );

  /// Multipart fields for POST /complaints (backend ComplaintController V34 params).
  Map<String, String> toApiFields() => {
        if (pincode != null) 'pincode': pincode!,
        if (landmark != null && landmark!.isNotEmpty) 'landmark': landmark!,
        if (locality != null && locality!.isNotEmpty) 'locality': locality!,
        if (formattedAddress != null && formattedAddress!.isNotEmpty) 'formattedAddress': formattedAddress!,
        if (villageCode != null) 'villageCode': villageCode.toString(),
        if (localBodyCode != null) 'localBodyCode': localBodyCode.toString(),
        if (wardId != null) 'wardId': wardId.toString(),
      };

  Map<String, dynamic> toJson() => {
        'pincode': pincode,
        'landmark': landmark,
        'locality': locality,
        'address': formattedAddress,
        'villageCode': villageCode,
        'villageName': villageName,
        'bodyCode': localBodyCode,
        'bodyName': localBodyName,
        'bodyUrban': localBodyUrban,
        'district': districtName,
        'block': blockName,
        'served': districtServed,
        'wardId': wardId,
        'wardName': wardName,
      };

  static IncidentArea? fromJson(Object? json) {
    if (json is! Map) return null;
    return IncidentArea(
      pincode: json['pincode'] as String?,
      landmark: json['landmark'] as String?,
      locality: json['locality'] as String?,
      formattedAddress: json['address'] as String?,
      villageCode: (json['villageCode'] as num?)?.toInt(),
      villageName: json['villageName'] as String?,
      localBodyCode: (json['bodyCode'] as num?)?.toInt(),
      localBodyName: json['bodyName'] as String?,
      localBodyUrban: json['bodyUrban'] as bool? ?? false,
      districtName: json['district'] as String?,
      blockName: json['block'] as String?,
      districtServed: json['served'] as bool? ?? true,
      wardId: (json['wardId'] as num?)?.toInt(),
      wardName: json['wardName'] as String?,
    );
  }
}

/// Everything the app knows about the incident location of one complaint.
///
/// INCIDENT LOCATION != SUBMISSION LOCATION: [confirmed] is where the problem
/// is. The citizen's position when submitting is never used for it.
class IncidentLocation {
  /// When the photo was taken (capture time, or EXIF), if known.
  final DateTime? photoTakenAt;

  /// The automatic proposal, or null (no photo location: pin by hand).
  final DetectedLocation? detected;

  /// The point the citizen confirmed on the map, or null until confirmed.
  final GeoPoint? confirmed;

  /// Ward chosen instead of a map point (map unavailable) - server stores an
  /// approximate ward location.
  final int? fallbackWardId;
  final String? fallbackWardName;

  /// V34: address + government area of [confirmed], once the citizen accepted
  /// it. Null = not confirmed yet (the server then works it out itself).
  final IncidentArea? area;

  const IncidentLocation({
    this.photoTakenAt,
    this.detected,
    this.confirmed,
    this.fallbackWardId,
    this.fallbackWardName,
    this.area,
  });

  static const none = IncidentLocation();

  bool get isConfirmed => confirmed != null || fallbackWardId != null;

  /// The pin the map should open on.
  GeoPoint? get proposedPoint => confirmed ?? detected?.point;

  /// The citizen confirmed [point]; [area] is its address/area when known
  /// (a new point without an area clears the old one - it described another place).
  IncidentLocation confirm(GeoPoint point, {IncidentArea? area}) => IncidentLocation(
        photoTakenAt: photoTakenAt,
        detected: detected,
        confirmed: point,
        area: area,
      );

  /// Same point, area accepted / corrected (area_confirm_card.dart).
  IncidentLocation withArea(IncidentArea? area) => IncidentLocation(
        photoTakenAt: photoTakenAt,
        detected: detected,
        confirmed: confirmed,
        fallbackWardId: fallbackWardId,
        fallbackWardName: fallbackWardName,
        area: area,
      );

  IncidentLocation useWard(int wardId, String wardName) => IncidentLocation(
        photoTakenAt: photoTakenAt,
        detected: detected,
        fallbackWardId: wardId,
        fallbackWardName: wardName,
      );

  IncidentLocation unconfirmed() => IncidentLocation(photoTakenAt: photoTakenAt, detected: detected);

  /// Whole days between the photo and [now] (null when unknown).
  int? photoAgeDays(DateTime now) {
    if (photoTakenAt == null) return null;
    final days = now.difference(photoTakenAt!).inDays;
    return days < 0 ? 0 : days;
  }

  /// Same rule as the backend's STALE_PHOTO flag.
  bool isStale(DateTime now) =>
      photoTakenAt != null && now.difference(photoTakenAt!) > const Duration(days: LocationPolicy.stalePhotoDays);

  bool isVeryOld(DateTime now) =>
      photoTakenAt != null && now.difference(photoTakenAt!) > const Duration(days: LocationPolicy.veryOldPhotoDays);

  /// One-tap confirmation is offered only for a precise automatic location.
  bool get canConfirmInOneTap => detected != null && !detected!.isLowAccuracy;

  /// Distance between the proposal and the confirmed pin (metres), if both exist.
  double? get pinMovedMeters =>
      detected != null && confirmed != null ? distanceMeters(detected!.point, confirmed!) : null;

  /// Multipart fields for POST /complaints (backend IncidentLocationRequest).
  /// [submissionDistanceMeters]: optional distance to where the citizen is
  /// now - their own position is never sent.
  Map<String, String> toApiFields({double? submissionDistanceMeters}) {
    final fields = <String, String>{'locationConfirmed': 'true'};
    if (confirmed != null) {
      fields['latitude'] = confirmed!.latitude.toStringAsFixed(6);
      fields['longitude'] = confirmed!.longitude.toStringAsFixed(6);
      fields['locationSource'] = _sourceFor(detected, confirmed!);
      if (area != null) fields.addAll(area!.toApiFields());
    } else if (fallbackWardId != null) {
      fields['wardId'] = fallbackWardId.toString();
    }
    if (detected != null) {
      fields['detectedLatitude'] = detected!.point.latitude.toStringAsFixed(6);
      fields['detectedLongitude'] = detected!.point.longitude.toStringAsFixed(6);
      if (detected!.accuracyMeters != null) {
        fields['locationAccuracyMeters'] = detected!.accuracyMeters!.toStringAsFixed(1);
      }
    }
    if (photoTakenAt != null) fields['photoCapturedAt'] = isoWithOffset(photoTakenAt!);
    if (submissionDistanceMeters != null && confirmed != null) {
      fields['submissionDistanceMeters'] = submissionDistanceMeters.toStringAsFixed(1);
    }
    return fields;
  }

  /// CAPTURE_GPS / EXIF when the citizen kept the proposal (within a few
  /// metres - map rounding), otherwise MANUAL_PIN: the citizen placed it.
  static String _sourceFor(DetectedLocation? detected, GeoPoint confirmed) {
    if (detected != null && distanceMeters(detected.point, confirmed) <= 5) return detected.source.apiValue;
    return 'MANUAL_PIN';
  }

  Map<String, dynamic> toJson() => {
        'takenAt': photoTakenAt?.toUtc().toIso8601String(),
        'detected': detected?.toJson(),
        'confirmed': confirmed?.toJson(),
        'wardId': fallbackWardId,
        'wardName': fallbackWardName,
        'area': area?.toJson(),
      };

  static IncidentLocation fromJson(Object? json) {
    if (json is! Map) return none;
    return IncidentLocation(
      photoTakenAt: DateTime.tryParse(json['takenAt'] as String? ?? '')?.toLocal(),
      detected: DetectedLocation.fromJson(json['detected']),
      confirmed: GeoPoint.fromJson(json['confirmed']),
      fallbackWardId: (json['wardId'] as num?)?.toInt(),
      fallbackWardName: json['wardName'] as String?,
      area: IncidentArea.fromJson(json['area']),
    );
  }
}

/// Great-circle (haversine) distance in metres - same formula as the backend's
/// LocationFlags.distanceMeters.
double distanceMeters(GeoPoint a, GeoPoint b) {
  const earthRadius = 6371008.8;
  double rad(double d) => d * math.pi / 180;
  final dLat = rad(b.latitude - a.latitude);
  final dLng = rad(b.longitude - a.longitude);
  final h = math.sin(dLat / 2) * math.sin(dLat / 2) +
      math.cos(rad(a.latitude)) * math.cos(rad(b.latitude)) * math.sin(dLng / 2) * math.sin(dLng / 2);
  return 2 * earthRadius * math.asin(math.min(1, math.sqrt(h)));
}

/// ISO-8601 with the device's UTC offset, e.g. 2026-10-02T09:15:00+05:30
/// (the backend's photoCapturedAt format).
String isoWithOffset(DateTime time) {
  final local = time.toLocal();
  final offset = local.timeZoneOffset;
  final sign = offset.isNegative ? '-' : '+';
  final abs = offset.abs();
  String two(int n) => n.toString().padLeft(2, '0');
  final base = '${local.year.toString().padLeft(4, '0')}-${two(local.month)}-${two(local.day)}'
      'T${two(local.hour)}:${two(local.minute)}:${two(local.second)}';
  return '$base$sign${two(abs.inHours)}:${two(abs.inMinutes % 60)}';
}
