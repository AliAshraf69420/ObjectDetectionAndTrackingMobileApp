package com.aliashraf69420.rcbowlingtracker.processing

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream

object VideoProcessor {

    data class Result(
        val outputVideoUri: String,
        val elapsedMs: Long,
        val pinsKnockedDown: Int,
        val pinEvents: List<Map<String, Any>>
    )

    fun process(
        context: Context,
        inputUriString: String,
        onProgress: (stage: String, percent: Int, message: String) -> Unit
    ): Result {
        val startMs = System.currentTimeMillis()

        onProgress("decoding", 10, "Opening input video…")
        val tempFile = copyToTemp(context, inputUriString)

        onProgress("preprocessing", 40, "Preparing output file…")
        Thread.sleep(200)

        onProgress("rendering", 65, "Saving to gallery…")
        val galleryUri = saveToGallery(context, tempFile)
        tempFile.delete()

        onProgress("rendering", 100, "Done!")

        return Result(
            outputVideoUri = galleryUri,
            elapsedMs = System.currentTimeMillis() - startMs,
            pinsKnockedDown = 0,
            pinEvents = emptyList()
        )
    }

    private fun copyToTemp(context: Context, uriString: String): File {
        val uri = Uri.parse(uriString)
        val inputStream = if (uri.scheme == "file") {
            FileInputStream(uri.path ?: throw IllegalArgumentException("Null path in URI: $uriString"))
        } else {
            context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Cannot open input video: $uriString")
        }

        val outFile = File(context.cacheDir, "rc_bowl_${System.currentTimeMillis()}.mp4")
        inputStream.use { input ->
            outFile.outputStream().use { output ->
                input.copyTo(output, bufferSize = 65_536)
            }
        }
        return outFile
    }

    private fun saveToGallery(context: Context, file: File): String {
        val fileName = "rc_bowling_${System.currentTimeMillis()}.mp4"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveGalleryQ(context, file, fileName)
        } else {
            saveGalleryLegacy(context, file, fileName)
        }
    }

    private fun saveGalleryQ(context: Context, file: File, fileName: String): String {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/RCBowling")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val collectionUri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = resolver.insert(collectionUri, values)
            ?: throw IllegalStateException("MediaStore insert returned null")

        resolver.openOutputStream(itemUri)?.use { out ->
            file.inputStream().use { it.copyTo(out, bufferSize = 65_536) }
        } ?: throw IllegalStateException("Cannot open MediaStore output stream")

        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(itemUri, values, null, null)

        return itemUri.toString()
    }

    private fun saveGalleryLegacy(context: Context, file: File, fileName: String): String {
        @Suppress("DEPRECATION")
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val rcDir = File(moviesDir, "RCBowling").also { it.mkdirs() }
        val destFile = File(rcDir, fileName)

        file.inputStream().use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output, bufferSize = 65_536)
            }
        }

        @Suppress("DEPRECATION")
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DATA, destFile.absolutePath)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.TITLE, fileName)
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: Uri.fromFile(destFile)

        return uri.toString()
    }
}
