package com.example.localaiggufedition

import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ChatUiState(
    val sessions: List<ChatSession> = emptyList(),
    val currentSessionId: String? = null,
    val messages: List<Message> = emptyList(),
    val isModelLoading: Boolean = false,
    val isGenerating: Boolean = false,
    val currentInput: String = ""
)

sealed class UiState {
    data object Idle : UiState()
    data class Loading(val progress: Float) : UiState()
    data object Thinking : UiState()
    data class Ready(val message: String) : UiState()
    data class Error(val error: String) : UiState()
}