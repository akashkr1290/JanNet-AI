import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/features/complaints/location/geo_models.dart';
import 'package:jannet_ai/features/complaints/location/incident_location.dart';

/// V34: the area the citizen confirmed travels with the complaint (POST
/// /complaints fields) and survives "Save & Report Later" (draft JSON).
void main() {
  const pakri = LocalBodyRef(code: 95600, name: 'Pakri', kind: 'RURAL', bodyType: 'Village Panchayat', districtCode: 196);
  const jolah = VillageRef(code: 255609, name: 'Jolah Bigha', districtCode: 196, subdistrictName: 'Gurua', localBodies: [pakri]);
  const gaya = DistrictRef(code: 196, name: 'Gaya', stateCode: 10, stateName: 'Bihar', served: true);
  const ward = WardRef(wardId: 7, name: 'Ward 7', wardNumber: '7', localBodyCode: 95600);

  test('areaFrom takes the only Gram Panchayat of the village', () {
    final a = areaFrom(district: gaya, village: jolah, ward: ward, pincode: '824205');
    expect(a.localBodyCode, 95600);
    expect(a.villageCode, 255609);
    expect(a.summary, 'Jolah Bigha · Pakri GP · Ward 7 · Gaya · 824205');
  });

  test('confirmed point sends the area codes with the complaint', () {
    final loc = const IncidentLocation().confirm(const GeoPoint(24.94, 84.84),
        area: areaFrom(district: gaya, village: jolah, ward: ward, pincode: '824205'));
    final f = loc.toApiFields();
    expect(f['latitude'], '24.940000');
    expect(f['villageCode'], '255609');
    expect(f['localBodyCode'], '95600');
    expect(f['wardId'], '7');
    expect(f['pincode'], '824205');
  });

  test('a new point without an area clears the old area', () {
    final loc = const IncidentLocation()
        .confirm(const GeoPoint(24.94, 84.84), area: areaFrom(district: gaya, village: jolah))
        .confirm(const GeoPoint(25.0, 85.0));
    expect(loc.area, isNull);
    expect(loc.toApiFields().containsKey('villageCode'), isFalse);
  });

  test('draft JSON keeps the area', () {
    final loc = const IncidentLocation()
        .confirm(const GeoPoint(24.94, 84.84), area: areaFrom(district: gaya, village: jolah, ward: ward));
    final back = IncidentLocation.fromJson(loc.toJson());
    expect(back.area!.villageName, 'Jolah Bigha');
    expect(back.area!.wardId, 7);
  });

  test('AreaMatch from the backend JSON', () {
    final m = AreaMatch.fromJson({
      'district': {'code': 196, 'name': 'Gaya', 'stateCode': 10, 'stateName': 'Bihar', 'served': true},
      'village': {
        'code': 255609,
        'name': 'Jolah Bigha',
        'subdistrictName': 'Gurua',
        'localBodies': [
          {'code': 95600, 'name': 'Pakri', 'kind': 'RURAL', 'bodyType': 'Village Panchayat', 'districtCode': 196}
        ]
      },
      'localBody': {'code': 95600, 'name': 'Pakri', 'kind': 'RURAL', 'bodyType': 'Village Panchayat'},
      'wardChoices': [],
      'villageChoices': [],
      'localBodyChoices': [],
      'confidence': 'EXACT',
    });
    expect(m.isProposal, isTrue);
    expect(m.village!.detail, 'Gurua tehsil · Pakri GP');
  });
}
