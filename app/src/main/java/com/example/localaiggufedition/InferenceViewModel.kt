package com.example.localaiggufedition

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

class InferenceViewModel(application: Application) : AndroidViewModel(application) {
    private val bridge = NativeBridge

    // Storage References
    private val chatsDir = File(application.filesDir, "chats").apply { mkdirs() }
    private val sessionsFile = File(application.filesDir, "sessions.json")

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState

    private val _chatUiState = MutableStateFlow(ChatUiState())
    val chatUiState: StateFlow<ChatUiState> = _chatUiState.asStateFlow()

    init {
        loadSessionsAndActiveChat()
    }

    // --- SESSION MANIPULATION UTILITIES ---

    fun createNewChat() {
        val newId = UUID.randomUUID().toString()
        val newSession = ChatSession(id = newId, title = "New Conversation")

        val updatedSessions = listOf(newSession) + _chatUiState.value.sessions
        _chatUiState.value = _chatUiState.value.copy(
            sessions = updatedSessions,
            currentSessionId = newId,
            messages = emptyList(),
            currentInput = ""
        )

        saveSessionsList(updatedSessions)
        saveActiveChatMessages(newId, emptyList())
    }

    fun switchSession(sessionId: String) {
        if (_chatUiState.value.currentSessionId == sessionId) return

        viewModelScope.launch(Dispatchers.IO) {
            val sessionFile = File(chatsDir, "chat_$sessionId.json")
            val loadedMessages = if (sessionFile.exists()) {
                try {
                    val jsonString = sessionFile.readText()
                    Json.decodeFromString(ListSerializer(Message.serializer()), jsonString)
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }

            withContext(Dispatchers.Main) {
                _chatUiState.value = _chatUiState.value.copy(
                    currentSessionId = sessionId,
                    messages = loadedMessages,
                    currentInput = ""
                )
            }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // Delete individual conversation file
            val sessionFile = File(chatsDir, "chat_$sessionId.json")
            if (sessionFile.exists()) sessionFile.delete()

            val updatedSessions = _chatUiState.value.sessions.filter { it.id != sessionId }
            saveSessionsList(updatedSessions)

            withContext(Dispatchers.Main) {
                if (_chatUiState.value.currentSessionId == sessionId) {
                    if (updatedSessions.isNotEmpty()) {
                        _chatUiState.value = _chatUiState.value.copy(sessions = updatedSessions)
                        switchSession(updatedSessions.first().id)
                    } else {
                        _chatUiState.value = _chatUiState.value.copy(sessions = updatedSessions)
                        createNewChat()
                    }
                } else {
                    _chatUiState.value = _chatUiState.value.copy(sessions = updatedSessions)
                }
            }
        }
    }

    // --- INFERENCE ENGINE HOOKS ---

    fun loadModelFromUri(uri: Uri) {
        viewModelScope.launch {
            try {
                _uiState.value = UiState.Loading(0.1f)
                _chatUiState.value = _chatUiState.value.copy(isModelLoading = true)

                val cachedModelPath = runInterruptible(Dispatchers.IO) {
                    val context = getApplication<Application>()
                    val targetFile = File(context.cacheDir, "loaded_model.gguf")

                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        java.io.FileOutputStream(targetFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    } ?: throw Exception("Failed to open storage URI stream.")

                    targetFile.absolutePath
                }

                _uiState.value = UiState.Loading(0.5f)

                val status = runInterruptible(Dispatchers.Default) {
                    bridge.initModel(cachedModelPath)
                }

                if (status.startsWith("Error:")) {
                    _uiState.value = UiState.Error(status)
                } else {
                    _uiState.value = UiState.Ready(status)
                }
            } catch (e: Throwable) {
                _uiState.value = UiState.Error(e.message ?: "Failed to load model")
            } finally {
                _chatUiState.value = _chatUiState.value.copy(isModelLoading = false)
            }
        }
    }

    fun updateInputChange(newInput: String) {
        _chatUiState.value = _chatUiState.value.copy(currentInput = newInput)
    }

    fun sendUserMessage(userPrompt: String) {
        val currentSessionId = _chatUiState.value.currentSessionId ?: return
        if (userPrompt.isNotBlank()) {
            val updatedMessages = _chatUiState.value.messages + Message(text = userPrompt, isUser = true)

            // Auto-rename chat session title from "New Conversation" to the user's first prompt snippet
            val updatedSessions = _chatUiState.value.sessions.map {
                if (it.id == currentSessionId && it.title == "New Conversation") {
                    it.copy(title = userPrompt.take(24).trim() + "...")
                } else it
            }

            _chatUiState.value = _chatUiState.value.copy(
                messages = updatedMessages,
                sessions = updatedSessions,
                currentInput = "",
                isGenerating = true
            )
            _uiState.value = UiState.Thinking

            saveSessionsList(updatedSessions)
            saveActiveChatMessages(currentSessionId, updatedMessages)

            viewModelScope.launch {
                try {
                    val formattedPrompt = "<|im_start|>user\n$userPrompt<|im_end|>\n<|im_start|>assistant\n"

                    val response = runInterruptible(Dispatchers.Default) {
                        bridge.runInference(formattedPrompt)
                    }

                    val finalMessages = _chatUiState.value.messages + Message(text = response, isUser = false)
                    _chatUiState.value = _chatUiState.value.copy(messages = finalMessages)
                    _uiState.value = UiState.Idle

                    saveActiveChatMessages(currentSessionId, finalMessages)
                } catch (e: Throwable) {
                    _uiState.value = UiState.Error(e.message ?: "Inference failed")
                } finally {
                    _chatUiState.value = _chatUiState.value.copy(isGenerating = false)
                }
            }
        }
    }

    // --- STORAGE SUBSYSTEM PERSISTENCE ---

    private fun saveSessionsList(sessions: List<ChatSession>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonString = Json.encodeToString(ListSerializer(ChatSession.serializer()), sessions)
                sessionsFile.writeText(jsonString)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun saveActiveChatMessages(sessionId: String, messages: List<Message>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sessionFile = File(chatsDir, "chat_$sessionId.json")
                val jsonString = Json.encodeToString(ListSerializer(Message.serializer()), messages)
                sessionFile.writeText(jsonString)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadSessionsAndActiveChat() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (sessionsFile.exists()) {
                    val jsonString = sessionsFile.readText()
                    val loadedSessions = Json.decodeFromString(ListSerializer(ChatSession.serializer()), jsonString)

                    if (loadedSessions.isNotEmpty()) {
                        val firstSessionId = loadedSessions.first().id
                        val sessionFile = File(chatsDir, "chat_$firstSessionId.json")
                        val loadedMessages = if (sessionFile.exists()) {
                            Json.decodeFromString(ListSerializer(Message.serializer()), sessionFile.readText())
                        } else emptyList()

                        withContext(Dispatchers.Main) {
                            _chatUiState.value = _chatUiState.value.copy(
                                sessions = loadedSessions,
                                currentSessionId = firstSessionId,
                                messages = loadedMessages
                            )
                        }
                        return@launch
                    }
                }

                // Fallback: If no sessions exist, spin up a default clean chat session
                withContext(Dispatchers.Main) {
                    createNewChat()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { createNewChat() }
            }
        }
    }
}