import 'app_strings.dart';
import 'languages.dart';
import 'translations/translations_bn.dart';
import 'translations/translations_hi.dart';
import 'translations/translations_kn.dart';
import 'translations/translations_mr.dart';
import 'translations/translations_ta.dart';
import 'translations/translations_te.dart';

/// Translates the app's own English wording into the chosen language.
///
/// The English text IS the key. Text that is built at run time (for example
/// 'Area: Ward 91') is matched against templates such as 'Area: {0}', whose
/// translations keep the same {0}, {1} placeholders. Anything that has no
/// translation (a person's name, a complaint description, text written by
/// the server) is shown unchanged, never blank. English is never touched.
class Translator {
  Translator._();

  static const Map<String, Map<String, String>> _tables = {
    'HI': kTranslationsHi,
    'MR': kTranslationsMr,
    'BN': kTranslationsBn,
    'TA': kTranslationsTa,
    'TE': kTranslationsTe,
    'KN': kTranslationsKn,
  };

  static final Map<String, Map<String, String>> _cache = {};
  static final Map<String, Map<String, String>> _lower = {};
  static final Map<String, List<_Template>> _templates = {};

  /// Translates [text] into the current app language.
  static String translate(String text) => translateTo(text, AppStrings.currentLanguage.value);

  static String translateTo(String text, String language) {
    final lang = JanLanguage.normalise(language);
    if (lang == 'EN' || text.isEmpty) return text;
    final cache = _cache.putIfAbsent(lang, () => {});
    final hit = cache[text];
    if (hit != null) return hit;
    final result = _translate(text, lang);
    if (cache.length > 4000) cache.clear();
    cache[text] = result;
    return result;
  }

  static String _translate(String text, String lang) {
    final table = _tables[lang];
    if (table == null) return text;
    final trimmed = text.trim();
    if (trimmed.isEmpty) return text;
    final lead = text.substring(0, text.indexOf(trimmed));
    final trail = text.substring(lead.length + trimmed.length);
    final core = _core(trimmed, table, lang);
    return core == null ? text : '$lead$core$trail';
  }

  static String? _core(String s, Map<String, String> table, String lang) {
    final exact = _exact(s, table, lang);
    if (exact != null) return exact;
    if (s.contains('\n')) {
      var any = false;
      final lines = s.split('\n').map((line) {
        final t = line.trim();
        if (t.isEmpty) return line;
        final r = _exact(t, table, lang) ?? _template(t, table, lang);
        if (r == null) return line;
        any = true;
        return line.replaceFirst(t, r);
      }).join('\n');
      return any ? lines : null;
    }
    final viaTemplate = _template(s, table, lang);
    if (viaTemplate != null) return viaTemplate;
    if (s.endsWith(':') && s.length > 1) {
      final r = _exact(s.substring(0, s.length - 1), table, lang);
      if (r != null) return '$r:';
    }
    return null;
  }

  static String? _exact(String s, Map<String, String> table, String lang) {
    final direct = table[s];
    if (direct != null) return direct;
    // Text that the screen upper-cases (section headings) still finds its translation.
    final lower = _lower.putIfAbsent(lang, () {
      final m = <String, String>{};
      for (final k in table.keys) {
        m.putIfAbsent(k.toLowerCase(), () => k);
      }
      return m;
    });
    final key = lower[s.toLowerCase()];
    return key == null ? null : table[key];
  }

  static String? _template(String s, Map<String, String> table, String lang) {
    final templates = _templates.putIfAbsent(lang, () {
      final list = <_Template>[];
      for (final key in table.keys) {
        if (!key.contains(RegExp(r'\{\d+\}'))) continue;
        final t = _Template.tryCompile(key);
        if (t != null) list.add(t);
      }
      list.sort((a, b) => b.letters.compareTo(a.letters));
      return list;
    });
    for (final t in templates) {
      final m = t.regex.firstMatch(s);
      if (m == null) continue;
      var out = table[t.key]!;
      for (var i = 0; i < m.groupCount; i++) {
        final raw = m.group(i + 1) ?? '';
        final inner = raw.length <= 60 ? (_exact(raw, table, lang) ?? raw) : raw;
        out = out.replaceAll('{$i}', inner);
      }
      return out;
    }
    return null;
  }
}

class _Template {
  final String key;
  final RegExp regex;
  final int letters;
  _Template(this.key, this.regex, this.letters);

  /// Only templates with enough fixed wording are used, so a short pattern
  /// such as '{0}h' can never capture unrelated text.
  static _Template? tryCompile(String key) {
    final parts = key.split(RegExp(r'\{\d+\}'));
    final letters = parts.join().replaceAll(RegExp(r'[^A-Za-z]'), '').length;
    if (letters < 4) return null;
    final pattern = StringBuffer('^');
    final re = RegExp(r'\{\d+\}');
    var last = 0;
    for (final m in re.allMatches(key)) {
      pattern.write(RegExp.escape(key.substring(last, m.start)));
      pattern.write('(.+?)');
      last = m.end;
    }
    pattern.write(RegExp.escape(key.substring(last)));
    pattern.write(r'$');
    return _Template(key, RegExp(pattern.toString(), dotAll: true), letters);
  }
}
