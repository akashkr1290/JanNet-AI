import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart' hide Text;
import 'package:image_picker/image_picker.dart';

import '../../../core/api/api_exception.dart';
import '../../../core/platform_status.dart';
import '../../../core/widgets/error_text.dart';
import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_illustrations.dart';
import '../../../core/widgets/jan_states.dart';
import '../../../core/widgets/jan_step_progress.dart';
import '../../../core/widgets/jan_surfaces.dart';
import '../../../core/api/geo_api.dart';
import '../../../core/l10n/app_strings.dart';
import '../../../core/l10n/bi_text.dart';
import '../location/area_confirm_card.dart';
import '../location/device_position.dart';
import '../location/geo_models.dart';
import '../location/incident_location.dart';
import '../location/incident_location_picker_screen.dart';
import '../location/incident_map.dart';
import '../location/location_policy.dart';
import '../location/photo_intake.dart';
import '../pending_submission_sync.dart';
import '../picked_photo.dart';
import '../complaint_draft_service.dart';
import '../complaints_api.dart';
import 'complaint_detail_screen.dart';
import '../../../core/l10n/tr_text.dart';

/// SRS 15.1/17.2 (Complaint Submission Form): photo + description +
/// location. Category/severity are intentionally NOT fields here - both
/// are assigned later (AI Analysis Module, Phase 7, or the Phase 6 manual
/// Verification Team override), matching ComplaintCategory's own Javadoc
/// ("AI issue categories") and the SRS form spec, which doesn't list
/// category as citizen-entered.
///
/// V33 - INCIDENT LOCATION != SUBMISSION LOCATION. The location step asks
/// "Where is the problem?": the app proposes the location from the photo
/// (GPS read right after an in-app camera capture, else the photo's EXIF GPS)
/// and the citizen confirms it on the map - one tap when it is right - or
/// places the pin by hand.
///
/// V34: the confirmed point also gets its address and government area
/// (village / Gram Panchayat or Nagar Nigam / ward) - "Is this the place?"
/// (location/area_confirm_card.dart). A photo WITHOUT a location asks "Are
/// you at the place now?": Yes -> the phone's GPS now; No -> the PIN ->
/// village -> ward flow on the map (location/manual_area_panel.dart). The phone's position when this screen
/// opens or when Submit is pressed is never used as the incident location.
/// "Save & Report Later" keeps the photo, its location and its time together.
class ComplaintSubmissionScreen extends StatefulWidget {
  const ComplaintSubmissionScreen({super.key});

  @override
  State<ComplaintSubmissionScreen> createState() => _ComplaintSubmissionScreenState();
}

class _ComplaintSubmissionScreenState extends State<ComplaintSubmissionScreen> {
  final _descriptionController = TextEditingController();
  // Post-UI gap fix: bytes + content type (PickedPhoto) instead of a
  // dart:io File, so picking, preview and upload also work on Flutter Web.
  PickedPhoto? _photo;
  bool _pickingPhoto = false;
  String? _photoError;
  bool _submitting = false;
  bool _savingForLater = false;
  String? _error;

  // V33 incident location.
  IncidentLocation _location = IncidentLocation.none;
  String? _locationNote;
  bool _stillThere = false;
  bool _savedForLater = false;

  // V34: address + area of the confirmed point (GET /geo/reverse), for the
  // inline "Is this the place?" card.
  GeoAddress? _address;
  bool _lookingUpAddress = false;
  int _addressRequest = 0;

  // V34 situation 4: photo without location - "Are you at the place now?" -> Yes.
  bool _locatingHere = false;

  @override
  void initState() {
    super.initState();
    _restoreDraftIfAny();
    // Gap-backlog Patch 32/45 (Sep 2026 audit): auto-save on every edit.
    _descriptionController.addListener(_saveDraft);
  }

  Future<void> _restoreDraftIfAny() async {
    final draft = await ComplaintDraftService.instance.load();
    if (draft == null || draft.isEmpty || !mounted) return;
    setState(() {
      if (_photo == null && draft.photo != null && draft.photo!.validationError == null) {
        _photo = draft.photo;
        _location = draft.location;
        _stillThere = draft.stillThere;
      }
      _savedForLater = draft.savedForLater;
    });
    final confirmed = _location.confirmed;
    if (confirmed != null && _location.area == null) _lookUpArea(confirmed); // V34: area for the restored point
    final description = draft.description;
    if (description != null && description.isNotEmpty && _descriptionController.text.isEmpty) {
      _descriptionController.text = description;
    }
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(draft.savedForLater
            ? 'Your saved report is ready to finish. The problem location from the photo is kept.'
            : 'Restored your unsaved draft from earlier.'),
      ),
    );
  }

  void _saveDraft() {
    ComplaintDraftService.instance.save(
      description: _descriptionController.text,
      location: _location,
      stillThere: _stillThere,
      savedForLater: _savedForLater,
    );
  }

  @override
  void dispose() {
    _descriptionController.dispose();
    super.dispose();
  }

  Future<void> _pickPhoto(ImageSource source) async {
    if (_pickingPhoto) return;
    setState(() => _pickingPhoto = true);
    try {
      final intake = await PhotoIntakeService.takeOrPick(source);
      if (intake == null) return;
      final problem = intake.photo.validationError;
      if (!mounted) return;
      if (problem != null) {
        // Keep any previously selected (valid) photo; just explain the rejection.
        setState(() => _photoError = problem);
        return;
      }
      var location = intake.location;
      if (location.detected == null && _location.isConfirmed) {
        // A replacement photo without a location keeps the place already confirmed.
        location = IncidentLocation(
          photoTakenAt: location.photoTakenAt,
          confirmed: _location.confirmed,
          fallbackWardId: _location.fallbackWardId,
          fallbackWardName: _location.fallbackWardName,
          area: _location.area,
        );
      }
      setState(() {
        _photo = intake.photo;
        _photoError = null;
        _location = location;
        _locationNote = location.isConfirmed ? null : intake.note;
        _stillThere = false;
        if (_error == 'A photo is required.') _error = null;
      });
      _saveDraft();
      await ComplaintDraftService.instance.savePhoto(intake.photo);
    } catch (e) {
      if (mounted) {
        setState(() => _photoError = source == ImageSource.camera
            ? 'Could not open the camera. Check the camera permission, or choose a photo instead.'
            : 'Could not open the selected photo. Please try another one.');
      }
    } finally {
      if (mounted) setState(() => _pickingPhoto = false);
    }
  }

  // UI redesign: remove the selected photo (the draft is re-saved without it).
  void _removePhoto() {
    setState(() {
      _photo = null;
      _photoError = null;
      // The photo's own location and time go with it; a place the citizen
      // already confirmed stays.
      _location = _location.isConfirmed
          ? IncidentLocation(
              confirmed: _location.confirmed,
              fallbackWardId: _location.fallbackWardId,
              fallbackWardName: _location.fallbackWardName,
              area: _location.area,
            )
          : IncidentLocation.none;
      _locationNote = null;
      _stillThere = false;
    });
    _saveDraft();
    ComplaintDraftService.instance.savePhoto(null);
  }

  // ---- Incident location ----

  /// One-tap confirmation of the automatic location; then its address/area.
  void _confirmDetected() {
    final detected = _location.detected;
    if (detected == null) return;
    setState(() {
      _location = _location.confirm(detected.point);
      _locationNote = null;
      if (_error != null && _error!.startsWith('Please confirm')) _error = null;
    });
    _saveDraft();
    _lookUpArea(detected.point);
  }

  /// Address + LGD area of the confirmed point for the inline card
  /// (backend GeoController /geo/reverse -> AreaResolver).
  Future<void> _lookUpArea(GeoPoint point) async {
    final id = ++_addressRequest;
    setState(() {
      _lookingUpAddress = true;
      _address = null;
    });
    try {
      final a = await GeoApi.instance.reverse(point);
      if (mounted && id == _addressRequest) setState(() => _address = a);
    } catch (_) {
      // offline: the complaint can still be sent; the server works the area out
    } finally {
      if (mounted && id == _addressRequest) setState(() => _lookingUpAddress = false);
    }
  }

  void _setArea(IncidentArea? area) {
    setState(() => _location = _location.withArea(area));
    _saveDraft();
  }

  /// [manual]: photo without location and the citizen is not at the place
  /// (situation 4b, or "No" on the area card): open with the place-name
  /// search first; the PIN -> village -> ward steps stay one tap away.
  Future<void> _openMap({bool manual = false}) async {
    final detected = _location.detected;
    final note = detected != null && detected.isLowAccuracy && !_location.isConfirmed
        ? 'Your phone could only find an approximate location for this photo. '
            'Please move the map so the red pin is exactly on the problem.'
        : null;
    final place = await Navigator.of(context).push<IncidentPlace>(
      MaterialPageRoute(
        builder: (_) => IncidentLocationPickerScreen(
          initial: manual ? null : _location.proposedPoint,
          detected: detected?.point,
          note: note,
          startManual: false,
          searchFirst: manual,
          initialArea: manual ? null : _location.area,
        ),
      ),
    );
    if (place == null || !mounted) return;
    setState(() {
      _location = _location.confirm(place.point, area: place.area);
      _locationNote = null;
      if (_error != null && _error!.startsWith('Please confirm')) _error = null;
    });
    _saveDraft();
    if (place.area == null) _lookUpArea(place.point);
  }

  /// Situation 4 (photo has no location): "Are you at the place now?" -> Yes:
  /// the phone's GPS NOW becomes the proposal (the citizen still confirms it).
  Future<void> _atThePlaceNow() async {
    setState(() => _locatingHere = true);
    final fix = await DevicePosition.read();
    if (!mounted) return;
    setState(() {
      _locatingHere = false;
      if (fix.ok) {
        _location = IncidentLocation(
          photoTakenAt: _location.photoTakenAt,
          detected: DetectedLocation(point: fix.point!, source: DetectedSource.captureGps, accuracyMeters: fix.accuracyMeters),
        );
        _locationNote = null;
      } else {
        _locationNote = '${DevicePosition.explain(fix.failure!)} Please show us the place on the map.';
      }
    });
    _saveDraft();
  }

  void _changeLocation() {
    setState(() {
      _location = _location.unconfirmed();
      _address = null;
    });
    _saveDraft();
    _openMap();
  }

  // ---- Save & Report Later ----

  Future<bool> _keepForLater() async {
    _savedForLater = true;
    await ComplaintDraftService.instance.save(
      description: _descriptionController.text,
      location: _location,
      stillThere: _stillThere,
      savedForLater: true,
    );
    return ComplaintDraftService.instance.savePhoto(_photo);
  }

  Future<void> _saveForLater() async {
    if (_photo == null) {
      setState(() => _error = 'Add a photo first - it is saved together with where it was taken.');
      return;
    }
    setState(() {
      _savingForLater = true;
      _error = null;
    });
    final photoKept = await _keepForLater();
    if (!mounted) return;
    setState(() => _savingForLater = false);
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(photoKept
          ? 'Saved on this device. Open "Report an Issue" within '
              '${LocationPolicy.veryOldPhotoDays} days to finish it - the photo and the place it was taken are kept.'
          : 'Your details are saved, but this photo could not be stored on this device. '
              'Keep this screen open or add the photo again later.'),
    ));
  }

  // ---- Submit ----

  Future<void> _submit() async {
    if (_photo == null) {
      setState(() => _error = 'A photo is required.');
      return;
    }
    final photo = _photo!;
    final photoProblem = photo.validationError;
    if (photoProblem != null) {
      setState(() => _error = photoProblem);
      return;
    }
    if (!_location.isConfirmed) {
      setState(() => _error = 'Please confirm where the problem is on the map.');
      return;
    }
    if (_location.isVeryOld(DateTime.now()) && !_stillThere) {
      setState(() => _error = 'This photo is more than ${LocationPolicy.veryOldPhotoDays} days old. '
          'Please tick "The problem is still there" (or take a new photo).');
      return;
    }
    setState(() {
      _submitting = true;
      _error = null;
    });

    // Optional, privacy-preserving: only the DISTANCE between the problem and
    // where the citizen is now - and only if location permission was already
    // given (no prompt). The citizen's own position is never sent.
    double? submissionDistance;
    final confirmedPoint = _location.confirmed;
    if (confirmedPoint != null) {
      final here = await DevicePosition.ifAlreadyAllowed();
      if (here.ok) submissionDistance = distanceMeters(here.point!, confirmedPoint);
    }
    final locationFields = _location.toApiFields(submissionDistanceMeters: submissionDistance);
    final description = _descriptionController.text.trim();

    try {
      final complaint = await ComplaintsApi.instance.submit(
        photo: photo.upload,
        description: description,
        locationFields: locationFields,
      );
      // UI redesign: reset the form for the next report (without re-saving an
      // empty draft), then show the reference success dialog. "Track Status"
      // opens the detail page ON TOP of the citizen shell - the previous
      // pushReplacement replaced the whole shell, so Back left the app.
      _descriptionController.removeListener(_saveDraft);
      _descriptionController.clear();
      _descriptionController.addListener(_saveDraft);
      await ComplaintDraftService.instance.clear();
      if (!mounted) return;
      setState(() {
        _photo = null;
        _location = IncidentLocation.none;
        _locationNote = null;
        _stillThere = false;
        _savedForLater = false;
        _address = null;
      });
      await showJanSuccessDialog(
        context,
        title: 'Report Submitted',
        message: 'Complaint ${complaint.referenceNumber} has been lodged and is queued for review. '
            'You can track its progress at any time.',
        primaryLabel: 'Track Status',
        onPrimary: () {
          if (!mounted) return;
          Navigator.of(context).push(
            MaterialPageRoute(builder: (_) => ComplaintDetailScreen(complaintId: complaint.complaintId)),
          );
        },
        secondaryLabel: 'Done',
      );
    } on ApiException catch (e) {
      // Audit GAP-037 (SRS 15.1 Exceptions): during a maintenance window the
      // submission is kept and sent later, like an offline one.
      if (isMaintenanceRefusal(e)) {
        final queued = await _queueOrKeep(photo, description, locationFields);
        if (mounted) {
          setState(() => _error = queued
              ? '${e.message} Your complaint is saved and will be sent automatically when maintenance ends.'
              : '${e.message} Your report is saved on this device - press Submit again when maintenance ends.');
        }
        return;
      }
      if (mounted) setState(() => _error = e.message);
    } catch (e) {
      // Gap-backlog Patch 45: no server response at all (offline/timeout).
      final queued = await _queueOrKeep(photo, description, locationFields);
      if (!mounted) return;
      setState(() => _error = queued
          ? 'No connection right now. Your complaint is saved and will upload automatically when you are back online.'
          : 'Could not reach the server. Your report (photo and problem location) is saved on this device - '
              'press Submit again when you are back online.');
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  /// Android: queue for automatic upload (the confirmed incident location is
  /// sent unchanged later). Web (no durable file path): keep the full draft.
  Future<bool> _queueOrKeep(PickedPhoto photo, String description, Map<String, String> locationFields) async {
    final photoPath = photo.path;
    if (photoPath != null && (await PickedPhoto.fromSavedPath(photoPath)) != null) {
      await PendingSubmissionSync.instance.queue(
        photoPath: photoPath,
        description: description,
        locationFields: locationFields,
      );
      PendingSubmissionSync.instance.start();
      return true;
    }
    await _keepForLater();
    return false;
  }

  // UI redesign: reference "Report an Issue" step layout (Photo, Description,
  // Location, Submit). The page heading is rendered by the citizen shell.
  @override
  Widget build(BuildContext context) {
    final busy = _submitting || _savingForLater;
    final photoStep = _PhotoStep(
      photo: _photo,
      uploading: _submitting,
      picking: _pickingPhoto,
      error: _photoError,
      onPick: _pickPhoto,
      onRemove: _removePhoto,
    );
    const aiNote = JanBanner(
      tone: JanBannerTone.info,
      icon: Icons.auto_awesome_outlined,
      message: 'After you submit, JanNet AI analyses the photo to identify the issue '
          'and route it to the right department - no need to pick a category.',
    );
    final descriptionStep = Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const _StepHeader(number: 2, title: 'Description'),
        TextField(
          controller: _descriptionController,
          maxLength: 500,
          maxLines: 4,
          textCapitalization: TextCapitalization.sentences,
          decoration: InputDecoration(
            labelText: trs('Description (optional)'),
            alignLabelWithHint: true,
            hintText: trs('What\'s the issue? Any details that would help.'),
          ),
        ),
      ],
    );
    final locationStep = Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const _StepHeader(number: 3, title: 'Where is the problem?'),
        _IncidentLocationStep(
          location: _location,
          note: _locationNote,
          hasPhoto: _photo != null,
          stillThere: _stillThere,
          onStillThereChanged: (v) {
            setState(() => _stillThere = v);
            _saveDraft();
          },
          onConfirmDetected: _confirmDetected,
          onOpenMap: () => _openMap(),
          onManual: () => _openMap(manual: true),
          onChange: _changeLocation,
          address: _address,
          lookingUpAddress: _lookingUpAddress,
          onAreaChanged: _setArea,
          locatingHere: _locatingHere,
          onAtThePlaceNow: _atThePlaceNow,
        ),
      ],
    );
    final submitStep = Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const _StepHeader(number: 4, title: 'Submit'),
        if (_error != null) ...[
          ErrorText(_error!),
          const SizedBox(height: JanSpace.md),
        ],
        FilledButton.icon(
          onPressed: busy ? null : _submit,
          icon: _submitting
              ? const SizedBox(
                  height: 20,
                  width: 20,
                  child: CircularProgressIndicator(strokeWidth: 2.4, color: JanColors.white),
                )
              : const Icon(Icons.send_rounded),
          label: Text(_submitting ? 'Submitting...' : 'Report Now'),
        ),
        const SizedBox(height: JanSpace.xs),
        OutlinedButton.icon(
          onPressed: busy || _photo == null ? null : _saveForLater,
          icon: const Icon(Icons.bookmark_add_outlined),
          label: Text(_savingForLater ? 'Saving...' : 'Save & Report Later'),
        ),
      ],
    );

    // UI phase 1: step indicator. "Location" counts as done only once the
    // citizen has confirmed a point, never for an unconfirmed guess.
    final progress = ListenableBuilder(
      listenable: _descriptionController,
      builder: (context, _) => JanStepProgress(
        labels: const ['Photo', 'Details', 'Location', 'Submit'],
        done: [_photo != null, _descriptionController.text.trim().isNotEmpty, _location.isConfirmed, false],
      ),
    );

    return LayoutBuilder(builder: (context, constraints) {
      final wide = constraints.maxWidth >= 860;
      return SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.xs, JanSpace.md, JanSpace.xxl),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            progress,
            const SizedBox(height: JanSpace.xs),
            wide
            ? Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [photoStep, const SizedBox(height: JanSpace.md), aiNote],
                    ),
                  ),
                  const SizedBox(width: JanSpace.xl),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [descriptionStep, locationStep, const SizedBox(height: JanSpace.sm), submitStep],
                    ),
                  ),
                ],
              )
            : Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  photoStep,
                  const SizedBox(height: JanSpace.md),
                  aiNote,
                  descriptionStep,
                  locationStep,
                  const SizedBox(height: JanSpace.sm),
                  submitStep,
                ],
              ),
          ],
        ),
      );
    });
  }
}

class _StepHeader extends StatelessWidget {
  final int number;
  final String title;
  const _StepHeader({required this.number, required this.title});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: JanSpace.lg, bottom: JanSpace.sm),
      child: Semantics(
        header: true,
        label: trs('Step $number: $title'),
        excludeSemantics: true,
        child: Row(
          children: [
            Container(
              width: 26,
              height: 26,
              alignment: Alignment.center,
              decoration: const BoxDecoration(color: JanColors.navy, shape: BoxShape.circle),
              child: Text('$number', style: const TextStyle(color: JanColors.white, fontWeight: FontWeight.w800, fontSize: 13)),
            ),
            const SizedBox(width: JanSpace.xs),
            Text(
              title.toUpperCase(),
              style: const TextStyle(fontSize: 13.5, fontWeight: FontWeight.w800, letterSpacing: 0.9, color: JanColors.navy),
            ),
          ],
        ),
      ),
    );
  }
}

class _PhotoStep extends StatelessWidget {
  final PickedPhoto? photo;
  final bool uploading;
  final bool picking;
  final String? error;
  final void Function(ImageSource) onPick;
  final VoidCallback onRemove;

  const _PhotoStep({
    required this.photo,
    required this.uploading,
    required this.picking,
    required this.error,
    required this.onPick,
    required this.onRemove,
  });

  static String _size(int bytes) =>
      bytes >= 1024 * 1024 ? '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB' : '${(bytes / 1024).ceil()} KB';

  @override
  Widget build(BuildContext context) {
    // Post-UI gap fix: Web now has a real photo flow. image_picker's web
    // implementation opens the browser file chooser; for the camera source
    // it adds the HTML `capture` hint, so phone browsers open the camera and
    // desktop browsers fall back to the file chooser. Android keeps the
    // native camera and gallery.
    final cameraLabel = kIsWeb ? 'Use Camera' : 'Camera';
    final galleryLabel = kIsWeb ? 'Choose Photo' : 'Gallery';
    final busy = uploading || picking;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const _StepHeader(number: 1, title: 'Photo'),
        if (photo == null)
          JanCard(
            padding: const EdgeInsets.all(JanSpace.lg),
            child: Column(
              children: [
                const JanStateIllustration(icon: Icons.photo_camera_outlined, color: JanColors.primary, size: 112),
                const SizedBox(height: JanSpace.sm),
                const Text(
                  'Add a photo of the issue',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: JanColors.navy),
                ),
                const SizedBox(height: 4),
                Text(
                  kIsWeb
                      ? 'Choose a JPEG, PNG or WEBP photo (up to 10 MB) from this device.'
                      : 'A clear photo helps the AI and officers understand the problem.',
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: JanColors.muted),
                ),
                const SizedBox(height: JanSpace.md),
                if (picking)
                  const Padding(
                    padding: EdgeInsets.symmetric(vertical: JanSpace.xs),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2)),
                        SizedBox(width: JanSpace.sm),
                        Text('Opening photo...', style: TextStyle(fontWeight: FontWeight.w600)),
                      ],
                    ),
                  )
                else
                  Wrap(
                    alignment: WrapAlignment.center,
                    spacing: JanSpace.sm,
                    runSpacing: JanSpace.sm,
                    children: [
                      // Web: the file chooser is the primary action.
                      if (kIsWeb)
                        _pickButton(galleryLabel, Icons.upload_file_rounded, ImageSource.gallery, primary: true,
                            semantic: 'Choose photo from this device'),
                      _pickButton(cameraLabel, Icons.camera_alt_outlined, ImageSource.camera, primary: !kIsWeb,
                          semantic: 'Take photo with camera'),
                      if (!kIsWeb)
                        _pickButton(galleryLabel, Icons.photo_library_outlined, ImageSource.gallery,
                            primary: false, semantic: 'Choose photo from gallery'),
                    ],
                  ),
              ],
            ),
          )
        else
          JanCard(
            padding: const EdgeInsets.all(JanSpace.xs),
            borderColor: JanColors.tealBrand,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                ClipRRect(
                  borderRadius: JanRadius.mdAll,
                  child: Stack(
                    children: [
                      // Image.memory renders the same bytes that will be
                      // uploaded, on every platform.
                      Image.memory(
                        photo!.upload.bytes,
                        height: 230,
                        width: double.infinity,
                        fit: BoxFit.cover,
                        gaplessPlayback: true,
                        semanticLabel: trs('Selected complaint photo'),
                        errorBuilder: (_, __, ___) => Container(
                          height: 230,
                          color: JanColors.surfaceAlt,
                          alignment: Alignment.center,
                          child: const Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Icon(Icons.broken_image_outlined, size: 40, color: JanColors.muted),
                              SizedBox(height: 6),
                              Text('Preview not available for this photo', style: TextStyle(color: JanColors.muted)),
                            ],
                          ),
                        ),
                      ),
                      if (uploading)
                        Positioned.fill(
                          child: Container(
                            color: const Color(0x99203A5F),
                            alignment: Alignment.center,
                            child: const Column(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                CircularProgressIndicator(color: JanColors.white),
                                SizedBox(height: JanSpace.sm),
                                Text('Uploading...', style: TextStyle(color: JanColors.white, fontWeight: FontWeight.w700)),
                              ],
                            ),
                          ),
                        )
                      else
                        Positioned(
                          top: 8,
                          right: 8,
                          child: Material(
                            color: const Color(0xB3203A5F),
                            shape: const CircleBorder(),
                            child: IconButton(
                              tooltip: trs('Remove photo'),
                              color: JanColors.white,
                              icon: const Icon(Icons.close_rounded),
                              onPressed: picking ? null : onRemove,
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(JanSpace.xs, JanSpace.xs, JanSpace.xs, 2),
                  child: Wrap(
                    spacing: JanSpace.xs,
                    runSpacing: 4,
                    crossAxisAlignment: WrapCrossAlignment.center,
                    children: [
                      const Icon(Icons.check_circle_rounded, color: JanColors.teal, size: 18),
                      Text(
                        'Photo added · ${_size(photo!.upload.bytes.length)}',
                        style: const TextStyle(color: JanColors.teal, fontWeight: FontWeight.w700),
                      ),
                      TextButton.icon(
                        onPressed: busy ? null : () => onPick(ImageSource.camera),
                        icon: const Icon(Icons.camera_alt_outlined, size: 18),
                        label: const Text('Retake'),
                      ),
                      TextButton.icon(
                        onPressed: busy ? null : () => onPick(ImageSource.gallery),
                        icon: Icon(kIsWeb ? Icons.upload_file_rounded : Icons.photo_library_outlined, size: 18),
                        label: const Text('Replace'),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        if (error != null) ...[
          const SizedBox(height: JanSpace.sm),
          ErrorText(error!),
        ],
      ],
    );
  }

  Widget _pickButton(String label, IconData icon, ImageSource source, {required bool primary, required String semantic}) {
    final onPressed = uploading ? null : () => onPick(source);
    return Semantics(
      label: trs(semantic),
      button: true,
      excludeSemantics: true,
      child: ConstrainedBox(
        constraints: const BoxConstraints(minWidth: 140),
        child: primary
            ? FilledButton.icon(onPressed: onPressed, icon: Icon(icon), label: Text(label))
            : OutlinedButton.icon(onPressed: onPressed, icon: Icon(icon), label: Text(label)),
      ),
    );
  }
}

/// V33: "Where is the problem?" - the mandatory incident-location
/// confirmation. Citizens see a map and plain words only; sources, accuracy
/// and flags are for staff.
class _IncidentLocationStep extends StatelessWidget {
  final IncidentLocation location;
  final String? note;
  final bool hasPhoto;
  final bool stillThere;
  final ValueChanged<bool> onStillThereChanged;
  final VoidCallback onConfirmDetected;
  final VoidCallback onOpenMap;
  final VoidCallback onManual;
  final VoidCallback onChange;
  // V34: address / area of the confirmed point
  final GeoAddress? address;
  final bool lookingUpAddress;
  final ValueChanged<IncidentArea?> onAreaChanged;
  // V34 situation 4: photo without location
  final bool locatingHere;
  final VoidCallback onAtThePlaceNow;

  const _IncidentLocationStep({
    required this.location,
    required this.note,
    required this.hasPhoto,
    required this.stillThere,
    required this.onStillThereChanged,
    required this.onConfirmDetected,
    required this.onOpenMap,
    required this.onManual,
    required this.onChange,
    required this.address,
    required this.lookingUpAddress,
    required this.onAreaChanged,
    required this.locatingHere,
    required this.onAtThePlaceNow,
  });

  @override
  Widget build(BuildContext context) {
    final now = DateTime.now();
    final age = location.photoAgeDays(now);
    final children = <Widget>[];

    if (location.isStale(now)) {
      children.add(JanBanner(
        tone: JanBannerTone.warning,
        icon: Icons.history_rounded,
        message: 'This photo was taken $age days ago. If the problem is still there you can still report it.',
      ));
      if (location.isVeryOld(now)) {
        children.add(CheckboxListTile(
          value: stillThere,
          onChanged: (v) => onStillThereChanged(v ?? false),
          contentPadding: EdgeInsets.zero,
          controlAffinity: ListTileControlAffinity.leading,
          title: const Text('The problem is still there'),
        ));
      }
      children.add(const SizedBox(height: JanSpace.sm));
    }

    if (location.confirmed != null) {
      // ---- confirmed point: preview + area ("Is this the place?")
      children.addAll([
        IncidentMapPreview(point: location.confirmed!, detected: location.detected?.point),
        const SizedBox(height: JanSpace.xs),
        _ConfirmedRow(text: 'Problem location confirmed', onChange: onChange),
      ]);
      final area = location.area;
      if (area != null && (area.villageCode != null || area.localBodyCode != null) && address == null) {
        // area accepted earlier (picker or restored draft)
        children.add(Text(area.summary, style: const TextStyle(fontWeight: FontWeight.w700, color: JanColors.ink)));
      } else if (address != null || lookingUpAddress) {
        children.add(JanCard(
          child: AreaConfirmCard(
            address: address,
            loading: lookingUpAddress,
            value: area,
            onChanged: onAreaChanged,
            onManual: onManual,
          ),
        ));
      }
    } else if (location.fallbackWardId != null) {
      // draft from an older version (ward fallback)
      children.addAll([
        JanCard(
          child: Row(children: [
            const Icon(Icons.map_outlined, color: JanColors.navy),
            const SizedBox(width: JanSpace.xs),
            Expanded(
              child: Text('Approximate location: ${location.fallbackWardName ?? 'your ward'}',
                  style: const TextStyle(fontWeight: FontWeight.w600, color: JanColors.slate)),
            ),
          ]),
        ),
        const SizedBox(height: JanSpace.xs),
        _ConfirmedRow(text: 'Ward chosen', onChange: onChange, changeLabel: 'Use the map instead'),
      ]);
    } else if (location.detected != null) {
      // ---- situations 1-3: location from the camera GPS / photo / GPS now
      final detected = location.detected!;
      children.addAll([
        IncidentMapPreview(point: detected.point),
        const SizedBox(height: JanSpace.xs),
        if (detected.isLowAccuracy) ...[
          const JanBanner(
            tone: JanBannerTone.warning,
            icon: Icons.gps_not_fixed_rounded,
            message: 'Your phone could only find an approximate location. Please check the pin on the map.',
          ),
          const SizedBox(height: JanSpace.xs),
          FilledButton.tonalIcon(
            onPressed: onOpenMap,
            icon: const Icon(Icons.edit_location_alt_outlined),
            label: const Text('Check on map'),
          ),
        ] else ...[
          Text(
            detected.source == DetectedSource.captureGps
                ? 'This is where you took the photo. Is the problem here?'
                : 'This is where the photo was taken. Is the problem here?',
            style: const TextStyle(color: JanColors.slate, fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: JanSpace.xs),
          FilledButton.icon(
            style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(52)),
            onPressed: onConfirmDetected,
            icon: const Icon(Icons.check_rounded),
            label: Text(AppStrings.of('loc_yes')),
          ),
          TextButton.icon(
            onPressed: onOpenMap,
            icon: const Icon(Icons.edit_location_alt_outlined, size: 18),
            label: const Text('Change on map'),
          ),
        ],
      ]);
    } else if (hasPhoto) {
      // ---- situation 4: the photo has no location
      children.add(JanCard(
        borderColor: JanColors.amber,
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Text(note ?? AppStrings.of('loc_no_photo_location'),
              style: const TextStyle(color: JanColors.slate, fontWeight: FontWeight.w600)),
          const SizedBox(height: JanSpace.sm),
          const BiText('loc_at_place_now'),
          const SizedBox(height: JanSpace.sm),
          Row(children: [
            Expanded(
              child: FilledButton.icon(
                style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(52)),
                onPressed: locatingHere ? null : onAtThePlaceNow,
                icon: locatingHere
                    ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.my_location_rounded),
                label: Text(AppStrings.of('common_yes')),
              ),
            ),
            const SizedBox(width: JanSpace.sm),
            Expanded(
              child: OutlinedButton.icon(
                style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(52)),
                onPressed: locatingHere ? null : onManual,
                icon: const Icon(Icons.pin_drop_outlined),
                label: Text(AppStrings.of('common_no')),
              ),
            ),
          ]),
          const SizedBox(height: JanSpace.xs),
          Text(AppStrings.of('loc_camera_tip'), style: const TextStyle(color: JanColors.muted, fontSize: 12.5)),
        ]),
      ));
    } else {
      children.add(JanCard(
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Row(children: [
            const Icon(Icons.place_outlined, color: JanColors.navy),
            const SizedBox(width: JanSpace.xs),
            const Expanded(
              child: Text('Add a photo first - if it was taken with your camera we can usually find the place for you.',
                  style: TextStyle(color: JanColors.slate, fontWeight: FontWeight.w600)),
            ),
          ]),
          const SizedBox(height: JanSpace.sm),
          FilledButton.tonalIcon(
            onPressed: onOpenMap,
            icon: const Icon(Icons.map_outlined),
            label: const Text('Choose on map'),
          ),
        ]),
      ));
    }

    if (!location.isConfirmed) {
      children.add(Align(
        alignment: Alignment.centerLeft,
        child: TextButton.icon(
          onPressed: onManual,
          icon: const Icon(Icons.pin_drop_outlined, size: 18),
          label: Text(AppStrings.of('loc_enter_pin')),
        ),
      ));
    }
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: children);
  }
}

class _ConfirmedRow extends StatelessWidget {
  final String text;
  final VoidCallback onChange;
  final String changeLabel;
  const _ConfirmedRow({required this.text, required this.onChange, this.changeLabel = 'Change'});

  @override
  Widget build(BuildContext context) => Wrap(
        crossAxisAlignment: WrapCrossAlignment.center,
        spacing: JanSpace.xs,
        children: [
          const Icon(Icons.check_circle_rounded, size: 20, color: JanColors.teal),
          Text(text, style: const TextStyle(color: JanColors.teal, fontWeight: FontWeight.w700)),
          TextButton.icon(
            onPressed: onChange,
            icon: const Icon(Icons.edit_location_alt_outlined, size: 18),
            label: Text(changeLabel),
          ),
        ],
      );
}
