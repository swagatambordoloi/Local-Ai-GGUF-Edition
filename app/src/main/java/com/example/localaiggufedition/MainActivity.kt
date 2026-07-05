package com.example.localaiggufedition

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.WindowCompat
import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Tells the window manager to pass keyboard and system insets directly to Compose layers
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()

        setContent {
            // Apply the system-aware Material 3 color theme wrapper
            LocalAiTheme {
                val viewModel: InferenceViewModel = viewModel(
                    factory = ViewModelProvider.AndroidViewModelFactory.getInstance(application)
                )
                MainScreen(viewModel)
            }
        }
    }

    fun getModelPath(context: Context, modelName: String): String {
        val file = File(context.cacheDir, modelName)
        if (!file.exists() || file.length() < 1000000) {
            context.assets.open(modelName).use { inputStream ->
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }
        return file.absolutePath
    }

    fun loadModelFromStorageUri(context: Context, fileUri: Uri) {
        try {
            val parcelFileDescriptor = context.contentResolver.openFileDescriptor(fileUri, "r")
            parcelFileDescriptor?.let {
                val fd = it.detachFd()
                val virtualPath = "/proc/self/fd/$fd"
                val result = NativeBridge.initModel(virtualPath)
                android.util.Log.i("GGUF-Engine", "Init Result: $result")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}