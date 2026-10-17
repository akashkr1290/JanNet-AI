import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/features/admin/models/admin_configuration.dart';
import 'package:jannet_ai/features/admin/widgets/staff_area_picker.dart';
import 'package:jannet_ai/features/complaints/location/geo_models.dart';
import 'package:jannet_ai/features/complaints/location/incident_location.dart';
import 'package:jannet_ai/features/complaints/models/complaint.dart';

/// V37: the development block travels from the backend's pin answer to the
/// citizen's confirm card, the officer's area line and the Admin department form.
void main() {
  const pakri = LocalBodyRef(code: 95600, name: 'Pakri', kind: 'RURAL', bodyType: 'Village Panchayat', districtCode: 196);
  const tekari = LocalBodyRef(code: 900001, name: 'Tekari', kind: 'URBAN', bodyType: 'Municipality', districtCode: 196);
  const gaya = DistrictRef(code: 196, name: 'Gaya', stateCode: 10, stateName: 'Bihar', served: true);

  Map<String, dynamic> pin({Map<String, dynamic>? village, Map<String, dynamic>? body, Map<String, dynamic>? block}) => {
        'latitude': 24.94,
        'longitude': 84.84,
        'area': {
          'district': {'code': 196, 'name': 'Gaya', 'stateCode': 10, 'stateName': 'Bihar', 'served': true},
          'village': village,
          'localBody': body,
          'block': block,
          'confidence': 'EXACT',
          'areaSource': 'BOUNDARY',
        },
      };

  const pakriJson = {'code': 95600, 'name': 'Pakri', 'kind': 'RURAL', 'bodyType': 'Village Panchayat', 'districtCode': 196};
  const jolahJson = {
    'code': 255609,
    'name': 'Jolah Bigha',
    'subdistrictName': 'Gurua',
    'blockName': 'Gurua',
    'blockCode': 1129,
    'localBodies': [pakriJson],
  };

  test('the pin answer carries the block', () {
    final a = GeoAddress.fromJson(pin(village: jolahJson, body: pakriJson, block: {'code': 1129, 'name': 'Gurua', 'districtCode': 196}));
    expect(a.area.block, const BlockRef(code: 1129, name: 'Gurua'));
    expect(a.area.block!.districtCode, 196);
    expect(a.area.village!.blockCode, 1129);
    expect(a.area.village!.detail, 'Gurua tehsil · Gurua block · Pakri GP');
  });

  test('an older backend without a block still parses', () {
    final a = GeoAddress.fromJson(pin(village: jolahJson, body: pakriJson));
    expect(a.area.block, isNull);
    expect(a.area.isProposal, isTrue);
  });

  test('accepting the proposal shows the block in the summary', () {
    final a = GeoAddress.fromJson(pin(village: jolahJson, body: pakriJson, block: {'code': 1129, 'name': 'Gurua'}));
    final area = areaFrom(address: a, district: gaya, village: a.area.village, body: pakri);
    expect(area.blockName, 'Gurua');
    expect(area.summary, 'Jolah Bigha · Pakri GP · Gurua block · Gaya');
    // display only: the backend works the block out itself
    expect(area.toApiFields().containsKey('blockName'), isFalse);
    expect(area.toApiFields().containsKey('blockCode'), isFalse);
  });

  test('a Gram Panchayat accepted without a village takes the backend block', () {
    final a = GeoAddress.fromJson(pin(body: pakriJson, block: {'code': 1130, 'name': 'Konch'}));
    expect(areaFrom(address: a, district: gaya, body: pakri).blockName, 'Konch');
  });

  test('a town has no block', () {
    final a = GeoAddress.fromJson(pin(body: {'code': 900001, 'name': 'Tekari', 'kind': 'URBAN', 'bodyType': 'Municipality'}));
    final area = areaFrom(address: a, district: gaya, body: tekari);
    expect(area.blockName, isNull);
    expect(area.summary, 'Tekari · Gaya');
  });

  test('draft JSON keeps the block', () {
    final a = GeoAddress.fromJson(pin(village: jolahJson, body: pakriJson, block: {'code': 1129, 'name': 'Gurua'}));
    final loc = const IncidentLocation()
        .confirm(const GeoPoint(24.94, 84.84), area: areaFrom(address: a, district: gaya, village: a.area.village));
    expect(IncidentLocation.fromJson(loc.toJson()).area!.blockName, 'Gurua');
    expect(loc.area!.withWard(7, 'Ward 7').blockName, 'Gurua');
  });

  test('officers see the block in the area line', () {
    final l = ComplaintLocation.fromJson(const {
      'villageName': 'Jolah Bigha',
      'localBodyName': 'Pakri',
      'localBodyType': 'Village Panchayat',
      'blockName': 'Gurua',
      'districtName': 'Gaya',
      'pincode': '824236',
    });
    expect(l.blockName, 'Gurua');
    expect(l.areaLine, 'Jolah Bigha, Pakri (Village Panchayat), Gurua block, Gaya 824236');
  });

  test('Admin department list labels a block department', () {
    final d = AdminDepartment.fromJson(const {
      'departmentId': 3,
      'name': 'Public Works',
      'isActive': true,
      'districtCode': 196,
      'districtName': 'Gaya',
      'blockCode': 1129,
      'blockName': 'Gurua',
    });
    expect(d.blockCode, 1129);
    expect(d.areaLabel, 'Gurua block, Gaya');
    expect(AdminDepartment.fromJson(const {'departmentId': 1, 'name': 'Roads', 'isActive': true}).areaLabel, 'All areas');
  });

  test('Admin area summary names the block', () {
    const area = StaffArea(district: gaya, block: BlockRef(code: 1129, name: 'Gurua'));
    expect(area.summary, 'Gurua block · Gaya, Bihar');
  });
}
