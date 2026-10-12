import 'dart:typed_data';

import 'photo_downscale_stub.dart' if (dart.library.js_interop) 'photo_downscale_web.dart' as impl;

/// Flutter Web only: shrinks a photo picked at full size (so its EXIF could be
/// read first) to at most [maxSide] px as a JPEG (audit GAP-009: 12 MP photos
/// made AI analysis time out). V34: starts at quality 0.92 and steps down
/// until the file is at most [maxBytes] (LocationPolicy.photoMaxBytes, 2 MB).
/// Returns null when not on the web or when the browser cannot decode the
/// image; the caller then keeps the original. Called by photo_intake.dart.
Future<Uint8List?> downscaleForUpload(Uint8List bytes, {int maxSide = 2048, int maxBytes = 2 * 1024 * 1024}) async {
  Uint8List? out;
  for (var quality = 0.92; quality >= 0.6; quality -= 0.08) {
    out = await impl.downscale(bytes, maxSide, quality);
    if (out == null || out.length <= maxBytes) return out;
  }
  return out;
}
