package com.example.minimalplayer.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContract
import android.media.MediaMetadataRetriever
import com.example.minimalplayer.data.MusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OpenMultipleAudioDocuments : ActivityResultContract<Unit, List<Uri>>() {
    override fun createIntent(context: Context, input: Unit): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        val uris = mutableListOf<Uri>()
        intent.clipData?.let { clipData ->
            for (i in 0 until clipData.itemCount) { uris.add(clipData.getItemAt(i).uri) }
        } ?: intent.data?.let { uri -> uris.add(uri) }
        return uris
    }
}

object FileImportHelper {
    suspend fun processAndInsertUris(context: Context, uris: List<Uri>, repository: MusicRepository) = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext
        val contentResolver = context.contentResolver
        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION

        for (uri in uris) {
            try { contentResolver.takePersistableUriPermission(uri, takeFlags) } catch (e: SecurityException) { continue }

            var title = "Unknown Track"
            var artist = "Unknown Artist"
            
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val extractedTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val extractedArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                if (!extractedTitle.isNullOrEmpty()) title = extractedTitle
                if (!extractedArtist.isNullOrEmpty()) artist = extractedArtist
            } catch (_: Exception) {} finally { retriever.release() }
            
            if (title == "Unknown Track") {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                        title = cursor.getString(nameIndex).substringBeforeLast('.')
                    }
                }
            }
            repository.insertTrack(uri.toString(), title, artist)
        }
    }
}
