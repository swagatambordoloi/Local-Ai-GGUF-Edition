package com.example.localaiggufedition

object NativeBridge {
    init {
        System.loadLibrary("localaiggufedition")
    }

    // Keep this as String to satisfy Kotlin compilation
    external fun initModel(modelPath: String): String
    external fun runInference(prompt: String): String
    external fun freeModel()
}