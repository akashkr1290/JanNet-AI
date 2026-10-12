import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:image_picker/image_picker.dart';

import '../../../core/api/upload_file.dart';
import '../picked_photo.dart';
import 'device_position.dart';
import 'incident_location.dart';
import 'location_policy.dart';
import 'photo_downscale.dart';
import 'photo_exif.dart';
import 'photo_native.dart';

/// A complaint photo plus the location the app found for it automatically.
class PhotoIntake {
  final PickedPhoto photo;

  /// Proposal only - not yet confirmed by the citizen.
  final IncidentLocation location;

  /// Plain-language note when no automatic location was found.
  final String? note;

  const PhotoIntake({required this.photo, required this.location, this.note});
}

/// Takes or picks a complaint photo and works out where it was taken.
///
/// Source priority (V33): capture GPS (in-app camera, read right after the
/// shot) -> the photo's EXIF GPS -> nothing (the citizen is then asked "Are
/// you at the place now?" - complaint_submission_screen.dart).
/// The citizen's position is read ONLY for a fresh in-app camera capture -
/// never for a gallery photo, which may have been taken anywhere.
///
/// V34 photo size: at most 2048 px and 2 MB (LocationPolicy.photoMaxSide /
/// photoMaxBytes), quality lowered step by step only when needed.
///  * Android camera: the picker saves 2048 px / quality 92 (EXIF kept); a
///    larger file is shrunk natively (photo_native.dart -> MainActivity.shrink).
///  * Android gallery: MainActivity.pickOriginal gives the ORIGINAL file with
///    its GPS (Android hides it from image_picker), EXIF is read here, then it
///    is shrunk natively. image_picker is the fallback if that fails.
///  * Web: the browser's own resize drops all metadata, so the ORIGINAL is
///    picked, read, then downscaled in the browser (photo_downscale_web.dart).
class PhotoIntakeService {
  PhotoIntakeService._();

  static Future<PhotoIntake?> takeOrPick(ImageSource source, {DateTime Function() clock = DateTime.now}) async {
    if (PhotoNative.available && source == ImageSource.gallery) {
      try {
        final picked = await PhotoNative.pickOriginal();
        if (picked == null) return null; // cancelled
        return await _fromAndroidFile(picked.path, picked.name, picked.mime, fresh: false, clock: clock);
      } catch (_) {
        // channel unavailable / failed: image_picker below (photo may lose its GPS)
      }
    }
    final picker = ImagePicker();
    final file = kIsWeb
        ? await picker.pickImage(source: source)
        : await picker.pickImage(
            source: source,
            imageQuality: 92,
            maxWidth: LocationPolicy.photoMaxSide.toDouble(),
            maxHeight: LocationPolicy.photoMaxSide.toDouble(),
          );
    if (file == null) return null;
    if (!kIsWeb) {
      return _fromAndroidFile(file.path, file.name, file.mimeType, fresh: source == ImageSource.camera, clock: clock);
    }

    // ---- Web
    final returnedAt = clock();
    final original = await file.readAsBytes();
    final exif = readPhotoExif(original);
    var fresh = source == ImageSource.camera;
    if (fresh) {
      // A browser "camera" button can also hand back an existing file (desktop
      // file chooser, iOS photo library). Only a just-created photo counts.
      DateTime? created = exif.takenAt;
      if (created == null) {
        try {
          created = await file.lastModified();
        } catch (_) {
          created = null;
        }
      }
      fresh = isFreshCapture(created, returnedAt);
    }
    PositionResult? fix;
    if (fresh) fix = await DevicePosition.read();

    var upload = UploadFile(
      bytes: original,
      filename: file.name.trim().isEmpty ? 'photo.jpg' : file.name,
      contentType: UploadFile.imageContentType(original, declared: file.mimeType, filename: file.name),
    );
    final smaller = await downscaleForUpload(original,
        maxSide: LocationPolicy.photoMaxSide, maxBytes: LocationPolicy.photoMaxBytes);
    if (smaller != null && smaller.isNotEmpty && smaller.length < original.length) {
      upload = UploadFile(bytes: smaller, filename: _jpegName(file.name), contentType: 'image/jpeg');
    }
    final decided = decideProposal(fresh: fresh, fix: fix, exif: exif, returnedAt: returnedAt);
    return PhotoIntake(photo: PickedPhoto(upload: upload), location: decided.location, note: decided.note);
  }

  /// Android: [path] is a full-size or picker-resized file. Location from EXIF
  /// (gallery) or GPS right after the shot (camera); upload copy <= 2048 px / 2 MB.
  static Future<PhotoIntake> _fromAndroidFile(String path, String name, String? mime,
      {required bool fresh, required DateTime Function() clock}) async {
    final returnedAt = clock();
    final original = await XFile(path).readAsBytes();
    final exif = readPhotoExif(original);
    final fix = fresh ? await DevicePosition.read() : null;

    var uploadPath = path;
    var bytes = original;
    final tooBig = original.length > LocationPolicy.photoMaxBytes;
    if (tooBig || !fresh) {
      // gallery originals are full size (often 4-10 MB); camera files only when over 2 MB
      try {
        final shrunk = await PhotoNative.shrink(path,
            maxSide: LocationPolicy.photoMaxSide, maxBytes: LocationPolicy.photoMaxBytes);
        if (shrunk != null) {
          final b = await XFile(shrunk).readAsBytes();
          if (b.isNotEmpty && b.length < original.length) {
            uploadPath = shrunk;
            bytes = b;
          }
        }
      } catch (_) {
        // keep the original; the backend accepts up to 10 MB
      }
    }
    final type = UploadFile.imageContentType(bytes,
        declared: uploadPath == path ? mime : 'image/jpeg', filename: uploadPath == path ? name : 'photo.jpg');
    final photo = PickedPhoto(
      upload: UploadFile(bytes: bytes, filename: uploadPath == path ? name : _jpegName(name), contentType: type),
      path: uploadPath,
    );
    final decided = decideProposal(fresh: fresh, fix: fix, exif: exif, returnedAt: returnedAt);
    return PhotoIntake(photo: photo, location: decided.location, note: decided.note);
  }

  static String _jpegName(String name) {
    final base = name.trim().isEmpty ? 'photo' : name.replaceFirst(RegExp(r'\.[A-Za-z0-9]+$'), '');
    return '$base.jpg';
  }
}

/// True when the photo was created within [LocationPolicy.freshCaptureWindow]
/// of the camera returning it.
bool isFreshCapture(DateTime? created, DateTime returnedAt) =>
    created != null && returnedAt.difference(created).abs() <= LocationPolicy.freshCaptureWindow;

/// The automatic proposal for a photo - pure, unit-tested.
({IncidentLocation location, String? note}) decideProposal({
  required bool fresh,
  required PositionResult? fix,
  required PhotoExif exif,
  required DateTime returnedAt,
}) {
  if (fresh && fix != null && fix.ok) {
    return (
      location: IncidentLocation(
        photoTakenAt: returnedAt,
        detected: DetectedLocation(
          point: fix.point!,
          source: DetectedSource.captureGps,
          accuracyMeters: fix.accuracyMeters,
        ),
      ),
      note: null,
    );
  }
  final takenAt = fresh ? returnedAt : exif.takenAt;
  if (exif.hasGps) {
    return (
      location: IncidentLocation(
        photoTakenAt: takenAt,
        detected: DetectedLocation(point: GeoPoint(exif.latitude!, exif.longitude!), source: DetectedSource.exif),
      ),
      note: null,
    );
  }
  final String note;
  if (fresh && fix?.failure != null) {
    note = '${DevicePosition.explain(fix!.failure!)} Please show us where the problem is on the map.';
  } else {
    note = 'This photo has no location saved in it. Please show us where the problem is on the map.';
  }
  return (location: IncidentLocation(photoTakenAt: takenAt), note: note);
}
