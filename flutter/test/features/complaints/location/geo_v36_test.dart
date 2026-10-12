import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/features/complaints/location/geo_models.dart';
import 'package:jannet_ai/features/complaints/models/location_check.dart';
import 'package:jannet_ai/features/department/models/department_performance.dart';

/// V36 (no API key): the JSON the backend sends for map search, PIN
/// autocomplete / PIN -> local bodies, the "ward from the map only" rule,
/// the staff location check and the assignment card.
void main() {
  test('search result keeps the point and picks a zoom by kind', () {
    final r = PlaceResult.fromJson({
      'label': 'Ward 84 - Raj Nagar',
      'secondary': 'Ghaziabad',
      'latitude': 28.6823,
      'longitude': 77.4475,
      'kind': 'WARD',
      'source': 'DATASET',
    });
    expect(r.point.latitude, closeTo(28.6823, 1e-9));
    expect(r.zoom, 15);
    expect(PlaceResult.fromJson({'label': 'x', 'latitude': 1, 'longitude': 2, 'kind': 'PINCODE'}).zoom, 13);
    expect(PlaceResult.fromJson({'label': 'x', 'latitude': 1, 'longitude': 2}).kind, 'PLACE');
  });

  test('a PIN covering several bodies lists the towns from the PIN links first', () {
    final area = PincodeArea.fromJson({
      'pincode': '201102',
      'villages': [],
      'urbanBodies': [
        {'code': 1, 'name': 'Name match only', 'kind': 'URBAN', 'bodyType': 'Nagar Palika Parishad'}
      ],
      'postOffices': ['Loni S.O'],
      'localBodies': [
        {
          'localBody': {'code': 800, 'name': 'Loni', 'kind': 'URBAN', 'bodyType': 'Nagar Palika Parishad'},
          'mappingSource': 'LGD_URBAN_PIN',
          'verificationStatus': 'PUBLISHED',
          'villageCount': 0,
        },
        {
          'localBody': {'code': 900, 'name': 'Mirpur Hindu', 'kind': 'RURAL', 'bodyType': 'Gram Panchayat'},
          'mappingSource': 'LGD_VILLAGE_PIN',
          'verificationStatus': 'PUBLISHED',
          'villageCount': 2,
        },
      ],
    });
    expect(area.localBodies, hasLength(2));
    expect(area.towns.map((b) => b.name), ['Loni']);
    expect(area.localBodies.last.villageCount, 2);
  });

  test('without a ward map the backend sends only a suggestion, never the ward', () {
    final m = AreaMatch.fromJson({
      'localBody': {'code': 800734, 'name': 'Ghaziabad', 'kind': 'URBAN', 'bodyType': 'Municipal Corporation'},
      'ward': null,
      'suggestedWard': {'wardId': 12, 'name': 'Ward 12', 'wardNumber': '12'},
      'wardChoices': [],
      'villageChoices': [],
      'localBodyChoices': [],
      'confidence': 'EXACT',
      'areaSource': 'BOUNDARY',
      'wardVerification': 'REQUIRES_VERIFICATION',
    });
    expect(m.ward, isNull);
    expect(m.suggestedWard!.shortLabel, 'Ward 12');
    expect(m.fromBoundaries, isTrue);
    expect(m.wardVerification, 'REQUIRES_VERIFICATION');
  });

  test('staff location-check row explains why in plain words', () {
    final item = LocationCheckItem.fromJson({
      'complaintId': 41,
      'referenceNumber': 'JN-2026-000041',
      'category': 'POTHOLE',
      'status': 'ASSIGNED',
      'localBodyName': 'Ghaziabad',
      'reason': 'NO_WARD_BOUNDARY',
      'latitude': 28.64,
      'longitude': 77.37,
    });
    expect(item.reasonText, contains('No ward map'));
    expect(item.point, isNotNull);
    expect(LocationCheckItem.fromJson({'complaintId': 1, 'reason': 'SOMETHING_NEW'}).reasonText, 'Location needs a check');
  });

  test('assignment card data: history and no officer name for citizens', () {
    final a = ComplaintAssignment.fromJson({
      'referenceNumber': 'JN-2026-000041',
      'status': 'ASSIGNED',
      'departmentName': 'Roads',
      'officerAssigned': true,
      'officerName': null,
      'history': [
        {'departmentName': 'Sanitation', 'method': 'RULE'},
        {'departmentName': 'Roads', 'method': 'MANUAL'},
      ],
    });
    expect(a.officerAssigned, isTrue);
    expect(a.officerName, isNull);
    expect(a.history.map((h) => h.method), ['RULE', 'MANUAL']);
  });

  test('officer picker shows the area each officer works for', () {
    final a = OfficerSummary.fromJson({'userId': 1, 'fullName': 'Asha', 'area': 'Ward 91 - Kavi Nagar', 'openComplaints': 2});
    final b = OfficerSummary.fromJson({'userId': 2, 'fullName': 'Bala'});
    expect(a.pickerLabel, 'Asha · Ward 91 - Kavi Nagar · Available · 2 open');
    expect(b.pickerLabel, 'Bala · Available · 0 open');
  });
}
