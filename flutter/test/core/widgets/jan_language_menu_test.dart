import 'package:flutter/material.dart' hide Text;
import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/core/l10n/app_strings.dart';
import 'package:jannet_ai/core/widgets/jan_language_menu.dart';

void main() {
  tearDown(() => AppStrings.currentLanguage.value = 'EN');

  testWidgets('lists all seven languages in their own script and switches', (tester) async {
    await tester.binding.setSurfaceSize(const Size(800, 900));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(const MaterialApp(home: Scaffold(body: Center(child: JanLanguageMenu()))));

    await tester.tap(find.byType(PopupMenuButton<String>));
    await tester.pumpAndSettle();
    for (final name in ['English', 'हिन्दी', 'मराठी', 'বাংলা', 'தமிழ்', 'తెలుగు', 'ಕನ್ನಡ']) {
      expect(find.text(name), findsWidgets, reason: name);
    }

    await tester.tap(find.text('தமிழ்').last);
    await tester.pumpAndSettle();
    expect(AppStrings.currentLanguage.value, 'TA');
  });
}
