import 'package:flutter/foundation.dart';

/// Guided filing (JanNet Sahayak v1): a complaint description the assistant
/// wrote from the citizen's own words. ComplaintSubmissionScreen picks it up
/// (also when it is already open) and puts it in the description box; the
/// citizen still takes the photo, confirms the place and presses Submit.
class ComplaintPrefill {
  ComplaintPrefill._();

  static final ValueNotifier<String?> description = ValueNotifier<String?>(null);

  /// The waiting description (once), or null.
  static String? take() {
    final value = description.value;
    if (value != null) description.value = null;
    return value;
  }
}
