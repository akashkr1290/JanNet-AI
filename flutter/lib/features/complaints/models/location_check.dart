import '../location/incident_location.dart';

/// backend LocationVerificationItem (GET /api/v1/complaints/location-verification):
/// a complaint whose ward / area could not be proven by a published boundary.
class LocationCheckItem {
  final int complaintId;
  final String referenceNumber;
  final String category;
  final String status;
  final String? departmentName;
  final int? localBodyCode;
  final String? localBodyName;
  final int? wardId;
  final String? wardName;
  final int? suggestedWardId;
  final String? suggestedWardName;
  final String? reason;
  final GeoPoint? point;

  const LocationCheckItem({
    required this.complaintId,
    required this.referenceNumber,
    required this.category,
    required this.status,
    this.departmentName,
    this.localBodyCode,
    this.localBodyName,
    this.wardId,
    this.wardName,
    this.suggestedWardId,
    this.suggestedWardName,
    this.reason,
    this.point,
  });

  /// Why the office must check it, in plain words.
  String get reasonText => switch (reason) {
        'NO_WARD_BOUNDARY' => 'No ward map for this spot - the ward is not known',
        'WARD_CHOSEN_BY_CITIZEN' => 'Ward chosen by the citizen (no ward map to confirm it)',
        'WARD_DIFFERS_FROM_MAP' => 'Citizen chose a different ward than the ward map',
        'AREA_CHOSEN_BY_CITIZEN' => 'Area chosen by the citizen from the lists',
        'AREA_DIFFERS_FROM_MAP' => 'Citizen chose a different town / panchayat than the map',
        'LOW_GPS_ACCURACY' => 'The phone GPS was not precise - the spot may be in the next ward',
        'NO_COORDINATES' => 'No map position - only the ward was given',
        'AREA_FROM_ADDRESS_NAMES' => 'Area found from address names only',
        'AREA_UNKNOWN' => 'The area could not be found',
        _ => 'Location needs a check',
      };

  factory LocationCheckItem.fromJson(Map<String, dynamic> j) {
    final lat = (j['latitude'] as num?)?.toDouble();
    final lng = (j['longitude'] as num?)?.toDouble();
    return LocationCheckItem(
      complaintId: (j['complaintId'] as num).toInt(),
      referenceNumber: j['referenceNumber'] as String? ?? '',
      category: j['category'] as String? ?? '',
      status: j['status'] as String? ?? '',
      departmentName: j['departmentName'] as String?,
      localBodyCode: (j['localBodyCode'] as num?)?.toInt(),
      localBodyName: j['localBodyName'] as String?,
      wardId: (j['wardId'] as num?)?.toInt(),
      wardName: j['wardName'] as String?,
      suggestedWardId: (j['suggestedWardId'] as num?)?.toInt(),
      suggestedWardName: j['suggestedWardName'] as String?,
      reason: j['reason'] as String?,
      point: lat != null && lng != null ? GeoPoint(lat, lng) : null,
    );
  }
}

/// backend ComplaintAssignmentResponse (GET /api/v1/complaints/{id}/assignment).
class ComplaintAssignment {
  final String referenceNumber;
  final String status;
  final String? departmentName;
  final bool officerAssigned;
  final String? officerName; // staff only
  final String? localBodyName;
  final String? wardName;
  final String? locationVerificationStatus;
  final List<AssignmentStep> history;

  const ComplaintAssignment({
    required this.referenceNumber,
    required this.status,
    this.departmentName,
    this.officerAssigned = false,
    this.officerName,
    this.localBodyName,
    this.wardName,
    this.locationVerificationStatus,
    this.history = const [],
  });

  factory ComplaintAssignment.fromJson(Map<String, dynamic> j) => ComplaintAssignment(
        referenceNumber: j['referenceNumber'] as String? ?? '',
        status: j['status'] as String? ?? '',
        departmentName: j['departmentName'] as String?,
        officerAssigned: j['officerAssigned'] as bool? ?? false,
        officerName: j['officerName'] as String?,
        localBodyName: j['localBodyName'] as String?,
        wardName: j['wardName'] as String?,
        locationVerificationStatus: j['locationVerificationStatus'] as String?,
        history: ((j['history'] as List?) ?? const [])
            .map((e) => AssignmentStep.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

class AssignmentStep {
  final String? departmentName;
  final String? officerName;
  final String method; // RULE | AREA_RULE | FALLBACK | MANUAL
  final String? reason;

  const AssignmentStep({this.departmentName, this.officerName, required this.method, this.reason});

  factory AssignmentStep.fromJson(Map<String, dynamic> j) => AssignmentStep(
        departmentName: j['departmentName'] as String?,
        officerName: j['officerName'] as String?,
        method: j['method'] as String? ?? '',
        reason: j['reason'] as String?,
      );
}
