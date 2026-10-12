import 'package:flutter/foundation.dart' show kIsWeb, defaultTargetPlatform, TargetPlatform;
import 'package:flutter/services.dart';

/// Dart side of the Android channel "jannet/photo"
/// (android/app/src/main/kotlin/.../MainActivity.kt). Used by photo_intake.dart.
class PhotoNative {
  PhotoNative._();

  static const _channel = MethodChannel('jannet/photo');

  static bool get available => !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  /// Gallery photo with its GPS kept (ACCESS_MEDIA_LOCATION + setRequireOriginal).
  /// Returns ({path, name, mime}) or null when cancelled. Throws when the
  /// channel fails - the caller then falls back to image_picker.
  static Future<({String path, String name, String mime})?> pickOriginal() async {
    final r = await _channel.invokeMapMethod<String, String>('pickOriginal');
    if (r == null) return null;
    return (path: r['original']!, name: r['name'] ?? 'photo.jpg', mime: r['mime'] ?? 'image/jpeg');
  }

  /// Resized JPEG copy (<= [maxSide] px, <= [maxBytes]) or null on failure.
  static Future<String?> shrink(String path, {int maxSide = 2048, int maxBytes = 2 * 1024 * 1024}) =>
      _channel.invokeMethod<String>('shrink', {'path': path, 'maxSide': maxSide, 'maxBytes': maxBytes});
}
