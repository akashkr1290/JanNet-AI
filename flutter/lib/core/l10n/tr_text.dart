import 'package:flutter/material.dart' as m;
import 'package:flutter/widgets.dart' hide Text;

import 'app_strings.dart';
import 'translator.dart';

export 'languages.dart';

/// Rebuilds everything that shows translated text when the language changes
/// (also widgets created with `const`, which Flutter would otherwise skip).
class LangScope extends InheritedNotifier<ValueNotifier<String>> {
  const LangScope({super.key, required ValueNotifier<String> language, required super.child})
      : super(notifier: language);

  /// Registers [context] to rebuild on a language change. Safe where no scope exists (tests).
  static String watch(BuildContext context) {
    final scope = context.dependOnInheritedWidgetOfExactType<LangScope>();
    return scope?.notifier?.value ?? AppStrings.currentLanguage.value;
  }
}

extension TranslateContext on BuildContext {
  /// Translates an English string from inside a build method. Use it for text
  /// that Flutter itself draws from a String (labels, hints, tooltips).
  String tr(String english) {
    LangScope.watch(this);
    return Translator.translate(english);
  }
}

/// Drop-in replacement for Flutter's [m.Text] that shows the string in the
/// language chosen in Settings. Files use it with
/// `import 'package:flutter/material.dart' hide Text;`. Everything else
/// (style, overflow, maxLines, ...) behaves exactly like [m.Text].
class Text extends StatelessWidget {
  final String data;
  final TextStyle? style;
  final StrutStyle? strutStyle;
  final TextAlign? textAlign;
  final TextDirection? textDirection;
  final Locale? locale;
  final bool? softWrap;
  final TextOverflow? overflow;
  final TextScaler? textScaler;
  final int? maxLines;
  final String? semanticsLabel;
  final TextWidthBasis? textWidthBasis;
  final TextHeightBehavior? textHeightBehavior;
  final Color? selectionColor;

  const Text(
    this.data, {
    super.key,
    this.style,
    this.strutStyle,
    this.textAlign,
    this.textDirection,
    this.locale,
    this.softWrap,
    this.overflow,
    this.textScaler,
    this.maxLines,
    this.semanticsLabel,
    this.textWidthBasis,
    this.textHeightBehavior,
    this.selectionColor,
  });

  @override
  Widget build(BuildContext context) {
    LangScope.watch(context);
    return m.Text(
      Translator.translate(data),
      style: style,
      strutStyle: strutStyle,
      textAlign: textAlign,
      textDirection: textDirection,
      locale: locale,
      softWrap: softWrap,
      overflow: overflow,
      textScaler: textScaler,
      maxLines: maxLines,
      semanticsLabel: semanticsLabel == null ? null : Translator.translate(semanticsLabel!),
      textWidthBasis: textWidthBasis,
      textHeightBehavior: textHeightBehavior,
      selectionColor: selectionColor,
    );
  }
}

/// Rich text (`Text.rich`) in the chosen language: every span's wording is
/// translated, the styles are kept.
class TextRich extends StatelessWidget {
  final TextSpan span;
  final TextStyle? style;
  final TextAlign? textAlign;
  final int? maxLines;
  final TextOverflow? overflow;
  final bool? softWrap;

  const TextRich(this.span, {super.key, this.style, this.textAlign, this.maxLines, this.overflow, this.softWrap});

  static TextSpan _translate(TextSpan s) => TextSpan(
        text: s.text == null ? null : Translator.translate(s.text!),
        children: s.children?.map((c) => c is TextSpan ? _translate(c) : c).toList(),
        style: s.style,
        recognizer: s.recognizer,
        mouseCursor: s.mouseCursor,
        onEnter: s.onEnter,
        onExit: s.onExit,
        semanticsLabel: s.semanticsLabel,
        locale: s.locale,
        spellOut: s.spellOut,
      );

  @override
  Widget build(BuildContext context) {
    LangScope.watch(context);
    return m.Text.rich(
      _translate(span),
      style: style,
      textAlign: textAlign,
      maxLines: maxLines,
      overflow: overflow,
      softWrap: softWrap,
    );
  }
}

/// Translates an English string without a BuildContext. Use it where Flutter
/// draws a String itself (labels, hints, tooltips); the whole screen is
/// rebuilt when the language changes (see JannetApp), so the result stays fresh.
String trs(String english) => Translator.translate(english);

/// Same as [trs] for a value that may be null.
String? trsN(String? english) => english == null ? null : Translator.translate(english);
