import 'package:flutter/material.dart' hide Text;

import '../l10n/app_strings.dart';
import '../l10n/language_store.dart';
import '../l10n/languages.dart';
import '../l10n/tr_text.dart';
import '../theme/jan_tokens.dart';

/// A compact language picker (names are shown in each language's own script).
/// Used where no account settings exist yet: the landing page and sign-in.
class JanLanguageMenu extends StatelessWidget {
  final bool onDark;
  const JanLanguageMenu({super.key, this.onDark = false});

  @override
  Widget build(BuildContext context) {
    final color = onDark ? JanColors.white : JanColors.navy;
    return ValueListenableBuilder<String>(
      valueListenable: AppStrings.currentLanguage,
      builder: (context, code, _) => PopupMenuButton<String>(
        tooltip: context.tr('Language'),
        initialValue: code,
        onSelected: LanguageStore.instance.choose,
        itemBuilder: (_) => [
          for (final l in JanLanguage.all)
            PopupMenuItem<String>(
              value: l.code,
              child: Text(l.nativeName, style: const TextStyle(fontWeight: FontWeight.w600)),
            ),
        ],
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: JanSpace.sm, vertical: JanSpace.xs),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.language_rounded, size: 20, color: color),
              const SizedBox(width: 6),
              if (MediaQuery.sizeOf(context).width >= 420)
                Text(JanLanguage.nativeNameOf(code), style: TextStyle(color: color, fontWeight: FontWeight.w700)),
              Icon(Icons.arrow_drop_down_rounded, color: color),
            ],
          ),
        ),
      ),
    );
  }
}
