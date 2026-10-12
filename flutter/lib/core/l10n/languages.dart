/// The languages the app can be shown in. [code] is what is stored in the
/// person's settings (and sent to the backend); [nativeName] is always shown
/// in the language's own script, so a person can find their language even
/// when the screen is currently in another one.
class JanLanguage {
  final String code;
  final String nativeName;
  const JanLanguage(this.code, this.nativeName);

  static const List<JanLanguage> all = [
    JanLanguage('EN', 'English'),
    JanLanguage('HI', 'हिन्दी'),
    JanLanguage('MR', 'मराठी'),
    JanLanguage('BN', 'বাংলা'),
    JanLanguage('TA', 'தமிழ்'),
    JanLanguage('TE', 'తెలుగు'),
    JanLanguage('KN', 'ಕನ್ನಡ'),
  ];

  static bool isSupported(String? code) => all.any((l) => l.code == code);

  /// Unknown or missing codes fall back to English.
  static String normalise(String? code) {
    final upper = code?.trim().toUpperCase();
    return isSupported(upper) ? upper! : 'EN';
  }

  static String nativeNameOf(String code) => all.firstWhere((l) => l.code == code, orElse: () => all.first).nativeName;
}
