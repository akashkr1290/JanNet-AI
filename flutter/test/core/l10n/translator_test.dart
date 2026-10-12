import 'package:flutter/material.dart' hide Text;
import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/core/l10n/app_strings.dart';
import 'package:jannet_ai/core/l10n/languages.dart';
import 'package:jannet_ai/core/l10n/tr_text.dart';
import 'package:jannet_ai/core/l10n/translations/translations_bn.dart';
import 'package:jannet_ai/core/l10n/translations/translations_hi.dart';
import 'package:jannet_ai/core/l10n/translations/translations_kn.dart';
import 'package:jannet_ai/core/l10n/translations/translations_mr.dart';
import 'package:jannet_ai/core/l10n/translations/translations_ta.dart';
import 'package:jannet_ai/core/l10n/translations/translations_te.dart';
import 'package:jannet_ai/core/l10n/translator.dart';

void main() {
  tearDown(() => AppStrings.currentLanguage.value = 'EN');

  final tables = <String, Map<String, String>>{
    'HI': kTranslationsHi,
    'MR': kTranslationsMr,
    'BN': kTranslationsBn,
    'TA': kTranslationsTa,
    'TE': kTranslationsTe,
    'KN': kTranslationsKn,
  };
  final placeholder = RegExp(r'\{\d+\}');

  group('translation tables', () {
    test('every language has exactly the same keys', () {
      final keys = kTranslationsHi.keys.toSet();
      for (final entry in tables.entries) {
        expect(entry.value.keys.toSet(), keys, reason: entry.key);
      }
    });

    test('every translation keeps the placeholders of its English key', () {
      for (final entry in tables.entries) {
        entry.value.forEach((en, tr) {
          final a = placeholder.allMatches(en).map((m) => m.group(0)).toList()..sort();
          final b = placeholder.allMatches(tr).map((m) => m.group(0)).toList()..sort();
          expect(b, a, reason: '${entry.key}: $en');
          expect(tr.trim().isNotEmpty, isTrue, reason: '${entry.key}: $en');
        });
      }
    });
  });

  group('Translator', () {
    test('English text is never touched', () {
      expect(Translator.translateTo('Report an Issue', 'EN'), 'Report an Issue');
    });

    test('exact wording is translated in every language', () {
      for (final code in tables.keys) {
        final out = Translator.translateTo('Report an Issue', code);
        expect(out, isNot('Report an Issue'), reason: code);
        expect(out, tables[code]!['Report an Issue']);
      }
    });

    test('run-time text matches a {0} template and keeps the value', () {
      expect(Translator.translateTo('Complaint ID: JN-1042', 'HI'), 'शिकायत आईडी: JN-1042');
      expect(Translator.translateTo('Photos (3)', 'TA'), 'புகைப்படங்கள் (3)');
    });

    test('a line break between two known sentences is translated line by line', () {
      final out = Translator.translateTo('Track Complaint\nReport an Issue', 'MR');
      expect(out, '${kTranslationsMr['Track Complaint']}\n${kTranslationsMr['Report an Issue']}');
    });

    test('upper-cased headings still find their translation', () {
      expect(Translator.translateTo('WHAT YOU CAN REPORT', 'HI'), kTranslationsHi['What you can report']);
    });

    test('text with no translation (names, server text) is returned unchanged', () {
      for (final code in tables.keys) {
        expect(Translator.translateTo('Gandhi Nagar Ward 91', code), 'Gandhi Nagar Ward 91');
      }
    });

    test('surrounding spaces are kept', () {
      expect(Translator.translateTo(' Sign in ', 'BN'), ' ${kTranslationsBn['Sign in']} ');
    });

    test('AppStrings falls back to translated English for languages without their own map', () {
      AppStrings.currentLanguage.value = 'KN';
      expect(AppStrings.of('common_yes'), kTranslationsKn['Yes']);
      AppStrings.currentLanguage.value = 'EN';
      expect(AppStrings.of('common_yes'), 'Yes');
    });
  });

  group('JanLanguage', () {
    test('lists English plus six Indian languages', () {
      expect(JanLanguage.all.map((l) => l.code), ['EN', 'HI', 'MR', 'BN', 'TA', 'TE', 'KN']);
    });

    test('unknown codes fall back to English and case is ignored', () {
      expect(JanLanguage.normalise('xx'), 'EN');
      expect(JanLanguage.normalise(null), 'EN');
      expect(JanLanguage.normalise(' ta '), 'TA');
    });
  });

  group('Text wrapper', () {
    testWidgets('shows the chosen language and follows a change', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: LangScope(
          language: AppStrings.currentLanguage,
          child: const Scaffold(body: Text('Report an Issue')),
        ),
      ));
      expect(find.text('Report an Issue'), findsOneWidget);

      AppStrings.currentLanguage.value = 'TE';
      await tester.pump();
      expect(find.text(kTranslationsTe['Report an Issue']!), findsOneWidget);
      expect(find.text('Report an Issue'), findsNothing);

      AppStrings.currentLanguage.value = 'EN';
      await tester.pump();
      expect(find.text('Report an Issue'), findsOneWidget);
    });

    testWidgets('TextRich translates every span and keeps the style', (tester) async {
      AppStrings.currentLanguage.value = 'HI';
      await tester.pumpWidget(const MaterialApp(
        home: Scaffold(
          body: TextRich(TextSpan(text: 'Sign in', children: [TextSpan(text: ' Next', style: TextStyle(fontWeight: FontWeight.bold))])),
        ),
      ));
      final text = tester.widget<RichText>(find.byType(RichText).first).text.toPlainText();
      expect(text, '${kTranslationsHi['Sign in']} ${kTranslationsHi['Next']}');
    });
  });
}
