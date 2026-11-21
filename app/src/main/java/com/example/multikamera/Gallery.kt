package com.example.multikamera

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** Zapisuje gotowy film tak, żeby był widoczny w galerii (Filmy/MultiKamera). */
object Gallery {
    private const val FOLDER = "MultiKamera"

    /** Zwraca czytelną dla człowieka ścieżkę zapisanego pliku. Plik źródłowy jest usuwany. */
    fun saveVideo(context: Context, source: File, displayName: String): String {
        return if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Nie można utworzyć pliku w galerii")
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("Nie można zapisać pliku w galerii")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            source.delete()
            "${Environment.DIRECTORY_MOVIES}/$FOLDER/$displayName"
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), FOLDER)
            dir.mkdirs()
            val target = File(dir, displayName)
            source.copyTo(target, overwrite = true)
            source.delete()
            target.absolutePath
        }
    }
}
