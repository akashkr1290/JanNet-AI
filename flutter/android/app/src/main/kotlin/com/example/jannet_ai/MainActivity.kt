package com.example.jannet_ai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.FileOutputStream

/**
 * JanNet native helpers, channel "jannet/photo" - called from
 * lib/features/complaints/location/photo_native.dart.
 *
 *  - pickOriginal: gallery photo WITH its GPS. Android 10+ hides a photo's
 *    location from apps unless they hold ACCESS_MEDIA_LOCATION and open the
 *    file with MediaStore.setRequireOriginal (image_picker does neither, which
 *    is why gallery photos "had no location" although the gallery shows one).
 *    Returns { original: path of an untouched copy (EXIF kept, read by
 *    photo_exif.dart), name, mime } or null when cancelled.
 *  - shrink: resizes a JPEG to at most maxSide px (EXIF orientation applied)
 *    and lowers JPEG quality until it is at most maxBytes; returns the new
 *    path. Native code is fast; doing this in Dart on a 12 MP photo is slow.
 */
class MainActivity : FlutterActivity() {
    private val channelName = "jannet/photo"
    private val pickRequest = 4711
    private val permissionRequest = 4712
    private var pendingPick: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName).setMethodCallHandler { call, result ->
            when (call.method) {
                "pickOriginal" -> startPick(result)
                "shrink" -> {
                    val path = call.argument<String>("path")
                    val maxSide = call.argument<Int>("maxSide") ?: 2048
                    val maxBytes = call.argument<Int>("maxBytes") ?: 2 * 1024 * 1024
                    if (path == null) result.error("ARG", "path missing", null)
                    else Thread {
                        val out = try { shrink(path, maxSide, maxBytes) } catch (e: Exception) { null }
                        runOnUiThread { result.success(out) }
                    }.start()
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun startPick(result: MethodChannel.Result) {
        if (pendingPick != null) {
            result.error("BUSY", "A photo is already being picked", null)
            return
        }
        pendingPick = result
        // Android 10+: ask for the photo-location permission first (no storage permission
        // is needed - ACTION_PICK grants access to the one chosen photo).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION), permissionRequest)
        } else {
            launchPicker()
        }
    }

    private fun launchPicker() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        intent.type = "image/*"
        try {
            startActivityForResult(intent, pickRequest)
        } catch (e: Exception) {
            pendingPick?.error("NO_GALLERY", "No gallery app", null)
            pendingPick = null
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Denied is fine: the photo is still picked, only without its location.
        if (requestCode == permissionRequest) launchPicker()
    }

    @Deprecated("startActivityForResult is enough for one picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickRequest) return
        val result = pendingPick ?: return
        pendingPick = null
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            result.success(null)
            return
        }
        Thread {
            val out = try { copyOriginal(uri) } catch (e: Exception) { null }
            runOnUiThread {
                if (out == null) result.error("READ", "Could not read the photo", null) else result.success(out)
            }
        }.start()
    }

    /** Copies the photo as stored (GPS not redacted when the permission was granted). */
    private fun copyOriginal(uri: Uri): Map<String, String> {
        var source = uri
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            try { source = MediaStore.setRequireOriginal(uri) } catch (e: Exception) { /* not a MediaStore uri */ }
        }
        val mime = contentResolver.getType(uri) ?: "image/jpeg"
        val ext = if (mime.contains("png")) "png" else if (mime.contains("webp")) "webp" else "jpg"
        val dir = File(cacheDir, "jannet_photos").apply { mkdirs() }
        val file = File(dir, "gallery_${System.currentTimeMillis()}.$ext")
        val input = try { contentResolver.openInputStream(source) } catch (e: SecurityException) {
            contentResolver.openInputStream(uri) // original refused: redacted copy
        } ?: throw IllegalStateException("no stream")
        input.use { inp -> FileOutputStream(file).use { inp.copyTo(it) } }
        return mapOf("original" to file.absolutePath, "name" to file.name, "mime" to mime)
    }

    private fun shrink(path: String, maxSide: Int, maxBytes: Int): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        var bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (scale < 1f) {
            bitmap = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        }
        val rotation = when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation != 0f) {
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation) }, true)
        }
        val out = File(File(cacheDir, "jannet_photos").apply { mkdirs() }, "upload_${System.currentTimeMillis()}.jpg")
        var quality = 90
        while (true) {
            FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            if (out.length() <= maxBytes || quality <= 60) break
            quality -= 8
        }
        return out.absolutePath
    }
}
