import 'package:flutter/foundation.dart';

import '../features/settings/models/personal_settings.dart';
import 'l10n/language_store.dart';
import 'l10n/languages.dart';

/// Gap-backlog Patches 46/47 (Sep 2026 strict recheck): the high-contrast and
/// language settings were stored server-side (PersonalSettings) but never
/// applied to the UI. main.dart listens to [highContrast]; screens using
/// AppStrings listen to AppStrings.currentLanguage.
class AppPreferences {
  AppPreferences._();

  static final ValueNotifier<bool> highContrast = ValueNotifier<bool>(false);

  static void apply(PersonalSettings settings) {
    highContrast.value = settings.highContrastEnabled;
    // An account language other than the default wins once signed in. When
    // the account still has the default (English), the language the person
    // already picked on this device is kept.
    final account = JanLanguage.normalise(settings.language);
    if (account != 'EN') LanguageStore.instance.choose(account);
  }
}
