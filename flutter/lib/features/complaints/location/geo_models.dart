import 'incident_location.dart';

// Dart mirrors of the backend dto/geo records returned by
// GET /api/v1/geo/... (backend controller/GeoController.java). Field names
// match the JSON exactly; parsed in core/api/geo_api.dart.

double? _d(Object? v) => (v as num?)?.toDouble();
int? _i(Object? v) => (v as num?)?.toInt();

/// backend PlaceResult (GET /geo/search): one map search result. It only
/// moves the pin - the area is then worked out from the boundaries.
class PlaceResult {
  final String label; // "Ward 84 - Raj Nagar", "Republic Crossing"
  final String? secondary; // "Ghaziabad, Ghaziabad"
  final GeoPoint point;
  final String kind; // PINCODE | POST_OFFICE | WARD | LOCAL_BODY | VILLAGE | PLACE
  final String source; // DATASET | OSM

  const PlaceResult({required this.label, this.secondary, required this.point, required this.kind, required this.source});

  /// Zoom that shows the result well: a PIN area is large, a landmark small.
  double get zoom => switch (kind) { 'PINCODE' => 13, 'LOCAL_BODY' => 13, 'VILLAGE' => 15, 'WARD' => 15, _ => 17 };

  factory PlaceResult.fromJson(Map<String, dynamic> j) => PlaceResult(
        label: j['label'] as String? ?? '',
        secondary: j['secondary'] as String?,
        point: GeoPoint(_d(j['latitude'])!, _d(j['longitude'])!),
        kind: j['kind'] as String? ?? 'PLACE',
        source: j['source'] as String? ?? 'DATASET',
      );
}

/// backend PincodeSuggestion (GET /geo/pincodes?prefix=): PIN autocomplete.
class PincodeSuggestion {
  final String pincode;
  final String? officeName;
  final String? districtName;
  final String? stateName;
  final bool served;

  const PincodeSuggestion({required this.pincode, this.officeName, this.districtName, this.stateName, this.served = false});

  factory PincodeSuggestion.fromJson(Map<String, dynamic> j) => PincodeSuggestion(
        pincode: j['pincode'] as String,
        officeName: j['officeName'] as String?,
        districtName: j['districtName'] as String?,
        stateName: j['stateName'] as String?,
        served: j['served'] as bool? ?? false,
      );
}

/// backend PincodeBodyRef: a Gram Panchayat / town a PIN covers (a PIN often covers several).
class PincodeBody {
  final LocalBodyRef body;
  final String mappingSource; // e.g. "LGD_URBAN_PIN,LGD_VILLAGE_PIN"
  final String verificationStatus; // PUBLISHED | VERIFIED | UNVERIFIED
  final int villageCount;

  const PincodeBody({required this.body, required this.mappingSource, required this.verificationStatus, this.villageCount = 0});

  factory PincodeBody.fromJson(Map<String, dynamic> j) => PincodeBody(
        body: LocalBodyRef.fromJson(j['localBody'] as Map<String, dynamic>),
        mappingSource: j['mappingSource'] as String? ?? '',
        verificationStatus: j['verificationStatus'] as String? ?? 'UNVERIFIED',
        villageCount: _i(j['villageCount']) ?? 0,
      );
}

/// backend DistrictRef. [served] = this deployment accepts complaints from it.
class DistrictRef {
  final int code;
  final String name;
  final int stateCode;
  final String stateName;
  final bool served;

  const DistrictRef({required this.code, required this.name, required this.stateCode, required this.stateName, required this.served});

  factory DistrictRef.fromJson(Map<String, dynamic> j) => DistrictRef(
        code: _i(j['code'])!,
        name: j['name'] as String,
        stateCode: _i(j['stateCode']) ?? 0,
        stateName: j['stateName'] as String? ?? '',
        served: j['served'] as bool? ?? false,
      );
}

/// backend BlockRef (V37): an LGD development block (prakhand) of a district.
class BlockRef {
  final int code;
  final String name;
  final int? districtCode;

  const BlockRef({required this.code, required this.name, this.districtCode});

  factory BlockRef.fromJson(Map<String, dynamic> j) =>
      BlockRef(code: _i(j['code'])!, name: j['name'] as String, districtCode: _i(j['districtCode']));

  @override
  bool operator ==(Object other) => other is BlockRef && other.code == code;

  @override
  int get hashCode => code.hashCode;
}

/// backend AreaRef (a state).
class StateRef {
  final int code;
  final String name;
  const StateRef(this.code, this.name);

  factory StateRef.fromJson(Map<String, dynamic> j) => StateRef(_i(j['code'])!, j['name'] as String);
}

/// backend LocalBodyRef: a Gram Panchayat (RURAL) or Nagar Nigam / Parishad (URBAN).
class LocalBodyRef {
  final int code;
  final String name;
  final String kind; // RURAL | URBAN
  final String bodyType; // "Village Panchayat", "Municipal Corporation" ...
  final int? districtCode;

  const LocalBodyRef({required this.code, required this.name, required this.kind, required this.bodyType, this.districtCode});

  bool get isUrban => kind == 'URBAN';

  /// "Pakri (Gram Panchayat)" / "Ghaziabad (Municipal Corporation)"
  String get label => '$name (${isUrban ? bodyType : 'Gram Panchayat'})';

  factory LocalBodyRef.fromJson(Map<String, dynamic> j) => LocalBodyRef(
        code: _i(j['code'])!,
        name: j['name'] as String,
        kind: j['kind'] as String? ?? 'RURAL',
        bodyType: j['bodyType'] as String? ?? '',
        districtCode: _i(j['districtCode']),
      );

  @override
  bool operator ==(Object other) => other is LocalBodyRef && other.code == code;

  @override
  int get hashCode => code.hashCode;
}

/// backend VillageRef: an LGD revenue village with its tehsil, block and body.
class VillageRef {
  final int code;
  final String name;
  final int? districtCode;
  final String? subdistrictName;
  final String? blockName;
  final int? blockCode; // V37
  final List<LocalBodyRef> localBodies;
  final GeoPoint? centre;

  const VillageRef({
    required this.code,
    required this.name,
    this.districtCode,
    this.subdistrictName,
    this.blockName,
    this.blockCode,
    this.localBodies = const [],
    this.centre,
  });

  /// "Gurua tehsil · Gurua block · Pakri GP" - shown under the name so same-named villages can be told apart.
  String get detail => [
        if (subdistrictName != null) '$subdistrictName tehsil',
        if (blockName != null) '$blockName block',
        if (localBodies.isNotEmpty) localBodies.map((b) => b.isUrban ? b.name : '${b.name} GP').join(' / '),
      ].join(' · ');

  factory VillageRef.fromJson(Map<String, dynamic> j) {
    final lat = _d(j['centreLatitude']);
    final lng = _d(j['centreLongitude']);
    return VillageRef(
      code: _i(j['code'])!,
      name: j['name'] as String,
      districtCode: _i(j['districtCode']),
      subdistrictName: j['subdistrictName'] as String?,
      blockName: j['blockName'] as String?,
      blockCode: _i(j['blockCode']),
      localBodies: ((j['localBodies'] as List?) ?? const [])
          .map((e) => LocalBodyRef.fromJson(e as Map<String, dynamic>))
          .toList(),
      centre: lat != null && lng != null ? GeoPoint(lat, lng) : null,
    );
  }

  @override
  bool operator ==(Object other) => other is VillageRef && other.code == code;

  @override
  int get hashCode => code.hashCode;
}

/// backend WardRef: a ward of one local body.
class WardRef {
  final int wardId;
  final String name;
  final String? wardNumber;
  final int? localBodyCode;
  final bool hasBoundary;
  final GeoPoint? centre;
  final List<String> localities;
  final String? source; // ADMIN | LGD | SBM
  final String? verificationStatus; // PUBLISHED | VERIFIED | UNVERIFIED

  const WardRef({
    required this.wardId,
    required this.name,
    this.wardNumber,
    this.localBodyCode,
    this.hasBoundary = false,
    this.centre,
    this.localities = const [],
    this.source,
    this.verificationStatus,
  });

  /// "Ward 84" - short chip label.
  String get shortLabel => wardNumber != null ? 'Ward $wardNumber' : name;

  factory WardRef.fromJson(Map<String, dynamic> j) {
    final lat = _d(j['centreLatitude']);
    final lng = _d(j['centreLongitude']);
    return WardRef(
      wardId: _i(j['wardId'])!,
      name: j['name'] as String,
      wardNumber: j['wardNumber'] as String?,
      localBodyCode: _i(j['localBodyCode']),
      hasBoundary: j['hasBoundary'] as bool? ?? false,
      centre: lat != null && lng != null ? GeoPoint(lat, lng) : null,
      localities: ((j['localities'] as List?) ?? const []).cast<String>(),
      source: j['source'] as String?,
      verificationStatus: j['verificationStatus'] as String?,
    );
  }

  @override
  bool operator ==(Object other) => other is WardRef && other.wardId == wardId;

  @override
  int get hashCode => wardId.hashCode;
}

/// backend AreaMatchResponse: what AreaResolver worked out for a point.
class AreaMatch {
  final String? stateName;
  final DistrictRef? district;
  final LocalBodyRef? localBody;
  final VillageRef? village;
  final WardRef? ward; // set ONLY when a ward map contains the point (wardMatch BOUNDARY)
  final String? wardMatch; // BOUNDARY
  final List<WardRef> wardChoices;
  final List<VillageRef> villageChoices;
  final List<LocalBodyRef> localBodyChoices;
  final String confidence; // EXACT | LIKELY | CHOOSE | NONE
  final String? pincode; // PIN whose published boundary contains the point
  final String areaSource; // BOUNDARY | NAME_MATCH | POST_OFFICE | NONE
  final WardRef? suggestedWard; // a hint when no ward map proves the ward
  final String? wardVerification; // VERIFIED_BOUNDARY | REQUIRES_VERIFICATION | null (no ward list)
  final BlockRef? block; // V37: the village's / Gram Panchayat's block; null in a town

  const AreaMatch({
    this.stateName,
    this.district,
    this.localBody,
    this.village,
    this.ward,
    this.wardMatch,
    this.wardChoices = const [],
    this.villageChoices = const [],
    this.localBodyChoices = const [],
    this.confidence = 'NONE',
    this.pincode,
    this.areaSource = 'NONE',
    this.suggestedWard,
    this.wardVerification,
    this.block,
  });

  /// The area comes from published boundaries (not from names).
  bool get fromBoundaries => areaSource == 'BOUNDARY';

  static const empty = AreaMatch();

  /// The app asks "Is this place X?" only when the backend is fairly sure.
  bool get isProposal => (confidence == 'EXACT' || confidence == 'LIKELY') && (village != null || localBody != null);

  factory AreaMatch.fromJson(Map<String, dynamic>? j) {
    if (j == null) return empty;
    List<T> list<T>(String key, T Function(Map<String, dynamic>) f) =>
        ((j[key] as List?) ?? const []).map((e) => f(e as Map<String, dynamic>)).toList();
    return AreaMatch(
      stateName: (j['state'] as Map<String, dynamic>?)?['name'] as String?,
      district: j['district'] == null ? null : DistrictRef.fromJson(j['district'] as Map<String, dynamic>),
      localBody: j['localBody'] == null ? null : LocalBodyRef.fromJson(j['localBody'] as Map<String, dynamic>),
      village: j['village'] == null ? null : VillageRef.fromJson(j['village'] as Map<String, dynamic>),
      ward: j['ward'] == null ? null : WardRef.fromJson(j['ward'] as Map<String, dynamic>),
      wardMatch: j['wardMatch'] as String?,
      wardChoices: list('wardChoices', WardRef.fromJson),
      villageChoices: list('villageChoices', VillageRef.fromJson),
      localBodyChoices: list('localBodyChoices', LocalBodyRef.fromJson),
      confidence: j['confidence'] as String? ?? 'NONE',
      pincode: j['pincode'] as String?,
      areaSource: j['areaSource'] as String? ?? 'NONE',
      suggestedWard: j['suggestedWard'] == null ? null : WardRef.fromJson(j['suggestedWard'] as Map<String, dynamic>),
      wardVerification: j['wardVerification'] as String?,
      block: j['block'] == null ? null : BlockRef.fromJson(j['block'] as Map<String, dynamic>),
    );
  }
}

/// backend GeoAddressResponse: the address line of a point + its area.
class GeoAddress {
  final GeoPoint point;
  final String? formattedAddress;
  final String? pincode;
  final String? landmark;
  final String? locality;
  final bool googleAvailable;
  final AreaMatch area;
  final String addressSource; // GOOGLE | OSM | DATASET

  const GeoAddress({
    required this.point,
    this.formattedAddress,
    this.pincode,
    this.landmark,
    this.locality,
    this.googleAvailable = false,
    this.area = AreaMatch.empty,
    this.addressSource = 'DATASET',
  });

  factory GeoAddress.fromJson(Map<String, dynamic> j) => GeoAddress(
        point: GeoPoint(_d(j['latitude'])!, _d(j['longitude'])!),
        formattedAddress: j['formattedAddress'] as String?,
        pincode: j['pincode'] as String?,
        landmark: j['landmark'] as String?,
        locality: j['locality'] as String?,
        googleAvailable: j['googleAvailable'] as bool? ?? false,
        area: AreaMatch.fromJson(j['area'] as Map<String, dynamic>?),
        addressSource: j['addressSource'] as String? ?? 'DATASET',
      );
}

/// backend PincodeAreaResponse: the manual flow's "enter your PIN" answer.
class PincodeArea {
  final String pincode;
  final DistrictRef? district;
  final GeoPoint? centre;
  final bool villagesExact;
  final List<VillageRef> villages;
  final List<LocalBodyRef> urbanBodies;
  final List<String> postOffices;
  final List<PincodeBody> localBodies; // every body the PIN covers (towns first)

  const PincodeArea({
    required this.pincode,
    this.district,
    this.centre,
    this.villagesExact = false,
    this.villages = const [],
    this.urbanBodies = const [],
    this.postOffices = const [],
    this.localBodies = const [],
  });

  /// Towns of the PIN: from the PIN -> body list when loaded, else the name match.
  List<LocalBodyRef> get towns {
    final fromLinks = localBodies.where((b) => b.body.isUrban).map((b) => b.body).toList();
    return fromLinks.isNotEmpty ? fromLinks : urbanBodies;
  }

  factory PincodeArea.fromJson(Map<String, dynamic> j) {
    final lat = _d(j['centreLatitude']);
    final lng = _d(j['centreLongitude']);
    return PincodeArea(
      pincode: j['pincode'] as String,
      district: j['district'] == null ? null : DistrictRef.fromJson(j['district'] as Map<String, dynamic>),
      centre: lat != null && lng != null ? GeoPoint(lat, lng) : null,
      villagesExact: j['villagesExact'] as bool? ?? false,
      villages: ((j['villages'] as List?) ?? const []).map((e) => VillageRef.fromJson(e as Map<String, dynamic>)).toList(),
      urbanBodies: ((j['urbanBodies'] as List?) ?? const [])
          .map((e) => LocalBodyRef.fromJson(e as Map<String, dynamic>))
          .toList(),
      postOffices: ((j['postOffices'] as List?) ?? const []).cast<String>(),
      localBodies: ((j['localBodies'] as List?) ?? const [])
          .map((e) => PincodeBody.fromJson(e as Map<String, dynamic>))
          .toList(),
    );
  }
}

/// Builds the [IncidentArea] the complaint carries from the backend's
/// answer and what the citizen chose. Called by area_confirm_card.dart
/// (Yes / list choice) and manual_area_panel.dart (PIN flow).
IncidentArea areaFrom({
  GeoAddress? address,
  DistrictRef? district,
  VillageRef? village,
  LocalBodyRef? body,
  WardRef? ward,
  String? pincode,
}) {
  final d = district ?? address?.area.district;
  final b = body ?? (village != null && village.localBodies.length == 1 ? village.localBodies.first : null);
  // block shown to the citizen: the village's own, else the backend's block when
  // the citizen accepted the proposed place; none in a town
  final area = address?.area;
  final proposalBlock = area != null &&
          (village == null || village.code == area.village?.code) &&
          (b == null || b.code == area.localBody?.code || area.localBody == null)
      ? area.block?.name
      : null;
  final blockName = (b?.isUrban ?? false) ? null : (village?.blockName ?? proposalBlock);
  return IncidentArea(
    pincode: pincode ?? address?.pincode,
    landmark: address?.landmark,
    locality: address?.locality,
    formattedAddress: address?.formattedAddress,
    villageCode: village?.code,
    villageName: village?.name,
    localBodyCode: b?.code,
    localBodyName: b?.name,
    localBodyUrban: b?.isUrban ?? false,
    districtName: d?.name,
    blockName: blockName,
    districtServed: d?.served ?? true,
    wardId: ward?.wardId,
    wardName: ward?.name,
  );
}
