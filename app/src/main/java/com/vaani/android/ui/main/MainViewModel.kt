package com.vaani.android.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vaani.android.audio.ConversationState
import com.vaani.android.data.Language
import com.vaani.android.data.LanguageRepository
import com.vaani.android.translation.TranslationOrchestrator
import com.vaani.android.utils.NetworkUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val allLanguages: List<Language> = emptyList(),
    val sourceLanguage: Language? = null,
    val targetLanguage: Language? = null,
    val conversationState: ConversationState = ConversationState.IDLE,
    val transcript: String = "",
    val translation: String = "",
    val isConnected: Boolean = false,
    val error: String? = null,
    val isSessionActive: Boolean = false,
    val hasMicPermission: Boolean = false
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val orchestrator: TranslationOrchestrator,
    private val languageRepository: LanguageRepository,
    private val networkUtils: NetworkUtils
) : ViewModel() {

    private val _sourceLanguage = MutableStateFlow(languageRepository.getDefaultSourceLanguage())
    private val _targetLanguage = MutableStateFlow(languageRepository.getDefaultTargetLanguage())
    private val _isSessionActive = MutableStateFlow(false)
    private val _hasMicPermission = MutableStateFlow(false)

    val uiState: StateFlow<MainUiState> = combine(
        _sourceLanguage,
        _targetLanguage,
        orchestrator.conversationState,
        orchestrator.currentTranscript,
        orchestrator.currentTranslation,
        orchestrator.isConnected,
        orchestrator.error,
        _isSessionActive
    ) { flows ->
        MainUiState(
            allLanguages = languageRepository.getAllLanguages(),
            sourceLanguage = flows[0] as Language,
            targetLanguage = flows[1] as Language,
            conversationState = flows[2] as ConversationState,
            transcript = flows[3] as String,
            translation = flows[4] as String,
            isConnected = flows[5] as Boolean,
            error = flows[6] as String?,
            isSessionActive = flows[7] as Boolean,
            hasMicPermission = _hasMicPermission.value
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(
            allLanguages = languageRepository.getAllLanguages(),
            sourceLanguage = _sourceLanguage.value,
            targetLanguage = _targetLanguage.value
        )
    )

    fun setMicPermissionGranted(granted: Boolean) {
        _hasMicPermission.value = granted
    }

    fun setSourceLanguage(language: Language) {
        if (language.code == _targetLanguage.value.code) return
        _sourceLanguage.value = language
    }

    fun setTargetLanguage(language: Language) {
        if (language.code == _sourceLanguage.value.code) return
        _targetLanguage.value = language
    }

    fun swapLanguages() {
        val src = _sourceLanguage.value
        val tgt = _targetLanguage.value
        _sourceLanguage.value = tgt
        _targetLanguage.value = src
    }

    fun startSession() {
        if (_isSessionActive.value) return
        if (!_hasMicPermission.value) return
        if (!networkUtils.isOnline()) return

        _isSessionActive.value = true
        orchestrator.startSession(_sourceLanguage.value.code, _targetLanguage.value.code)
    }

    fun stopSession() {
        if (!_isSessionActive.value) return
        _isSessionActive.value = false
        orchestrator.stopSession()
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            orchestrator.stopSession()
        }
    }
}
