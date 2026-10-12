import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/core/l10n/app_strings.dart';
import 'package:jannet_ai/core/l10n/bi_text.dart';

void main() {
  tearDown(() => AppStrings.currentLanguage.value = 'EN');

  testWidgets('shows only the chosen language and follows a language change', (tester) async {
    await tester.pumpWidget(const MaterialApp(home: Scaffold(body: BiText('loc_at_place_now'))));
    AppStrings.currentLanguage.value = 'EN';
    await tester.pump();
    expect(find.text(AppStrings.of('loc_at_place_now')), findsOneWidget);
    expect(find.byType(Text), findsOneWidget);
    final english = AppStrings.of('loc_at_place_now');

    AppStrings.currentLanguage.value = 'HI';
    await tester.pump();
    expect(find.byType(Text), findsOneWidget);
    expect(find.text(AppStrings.of('loc_at_place_now')), findsOneWidget);
    expect(AppStrings.of('loc_at_place_now'), isNot(english));
  });
}
