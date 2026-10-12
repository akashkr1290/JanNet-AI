import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'app_strings.dart';
import 'languages.dart';

/// Remembers the chosen language on this device, so the landing and sign-in
/// screens (where no account settings exist yet) open in it. Uses
/// flutter_secure_storage, like the onboarding flag. Once signed in, the
/// language saved in the account's settings takes over (AppPreferences.apply).
class LanguageStore {
  LanguageStore._();
  static final LanguageStore instance = LanguageStore._();

  static const _key = 'jannet_language_v1';
  final _storage = const FlutterSecureStorage();

  /// Applies the stored language, if any. Never throws.
  Future<void> load() async {
    try {
      final code = await _storage.read(key: _key);
      if (code != null) AppStrings.currentLanguage.value = JanLanguage.normalise(code);
    } catch (_) {
      // Best effort: the app simply starts in English.
    }
  }

  Future<void> save(String code) async {
    try {
      await _storage.write(key: _key, value: JanLanguage.normalise(code));
    } catch (_) {
      // Best effort.
    }
  }

  /// Changes the language everywhere and remembers it on this device.
  Future<void> choose(String code) async {
    AppStrings.currentLanguage.value = JanLanguage.normalise(code);
    await save(code);
  }
}
