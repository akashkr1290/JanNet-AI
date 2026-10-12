import 'package:flutter/material.dart';

import '../theme/jan_tokens.dart';
import 'app_strings.dart';

/// A key question, large and plain, in the language the person chose in
/// Settings (English or Hindi). It shows ONE language only, so the screen is
/// not cluttered. The name is kept from the earlier two-language version.
/// Used for the location questions (area_confirm_card.dart,
/// manual_area_panel.dart, the "Are you at the place now?" card).
class BiText extends StatelessWidget {
  final String stringKey;
  final TextStyle? style;
  final TextAlign textAlign;

  const BiText(this.stringKey, {super.key, this.style, this.textAlign = TextAlign.start});

  @override
  Widget build(BuildContext context) {
    final mainStyle = style ?? const TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: JanColors.ink);
    // Rebuilds when the language is changed, even if the parent does not.
    return ValueListenableBuilder<String>(
      valueListenable: AppStrings.currentLanguage,
      builder: (context, _, __) => Text(AppStrings.of(stringKey), style: mainStyle, textAlign: textAlign),
    );
  }
}
