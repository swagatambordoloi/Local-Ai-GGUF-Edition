package com.example.localaiggufedition

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModelManager(private val context: Context) {

    suspend fun prepareModelFile(uri: Uri): String = withContext(Dispatchers.IO) {
        val outputFile = File(context.cacheDir, "user_model.gguf")
        if (outputFile.exists()) outputFile.delete()

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(outputFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        return@withContext outputFile.absolutePath
    }
}