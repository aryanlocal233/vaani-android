package com.vaani.android.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vaani.android.audio.ConversationState
import com.vaani.android.data.Language
import com.vaani.android.data.LanguageRepository
import com.vaani.android.translation.TranslationOrchestrator
import com.vaani.android.utils.NetworkUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val hasMicPermission: Boolean = false,
    val isSwitchingLanguage: Boolean = false,
    val detectedSrcLanguageName: String? = null,
    val detectedTgtLanguageName: String? = null
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
    private val _isSwitchingLanguage = MutableStateFlow(false)
    private var restartSessionJob: Job? = null

    val uiState: StateFlow<MainUiState> = combine(
        _sourceLanguage,
        _targetLanguage,
        orchestrator.conversationState,
        orchestrator.currentTranscript,
        orchestrator.currentTranslation,
        orchestrator.isConnected,
        orchestrator.error,
        _isSessionActive,
        _isSwitchingLanguage,
        orchestrator.detectedSrcLang,
        orchestrator.detectedTgtLang
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
            hasMicPermission = _hasMicPermission.value,
            isSwitchingLanguage = flows[8] as Boolean,
            detectedSrcLanguageName = (flows[9] as String?)?.let { languageRepository.getLanguageByCode(it)?.name },
            detectedTgtLanguageName = (flows[10] as String?)?.let { languageRepository.getLanguageByCode(it)?.name }
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

    init {
        // Dropdown 1 (operator/help-desk language) is manually selected and never auto-changes.
        // Dropdown 2 (the public-facing visitor) should reflect whoever the backend actually
        // detected speaking, if that's not the operator -- the backend now auto-detects across
        // every supported language on that side, not just whatever was pre-selected here, so
        // this just follows it. Deliberately a separate collector from the combine() below (not
        // folded into it) and doesn't call restartSession(): the backend already tracks the
        // visitor's language server-side for this connection, so there's nothing to reconnect
        // for -- this only updates what dropdown 2 displays.
        viewModelScope.launch {
            orchestrator.detectedSrcLang.collect { code ->
                if (code == null || code == _sourceLanguage.value.code) return@collect
                languageRepository.getLanguageByCode(code)?.let { visitorLanguage ->
                    if (visitorLanguage.code != _targetLanguage.value.code) {
                        _targetLanguage.value = visitorLanguage
                    }
                }
            }
        }
    }

    fun setMicPermissionGranted(granted: Boolean) {
        _hasMicPermission.value = granted
    }

    fun setSourceLanguage(language: Language) {
        if (language.code == _targetLanguage.value.code) return
        _sourceLanguage.value = language
        if (_isSessionActive.value) restartSession()
    }

    fun setTargetLanguage(language: Language) {
        if (language.code == _sourceLanguage.value.code) return
        _targetLanguage.value = language
        if (_isSessionActive.value) restartSession()
    }

    fun swapLanguages() {
        val src = _sourceLanguage.value
        val tgt = _targetLanguage.value
        _sourceLanguage.value = tgt
        _targetLanguage.value = src
        if (_isSessionActive.value) restartSession()
    }

    /**
     * Language change while a session is live: the old WebSocket connection was opened
     * with the previous language pair baked into its URL, so it must be torn down and
     * reopened rather than just relabeled -- otherwise new speech keeps going to the old
     * pair's endpoint and gets silently ignored server-side.
     */
    private fun restartSession() {
        restartSessionJob?.cancel()
        restartSessionJob = viewModelScope.launch {
            _isSwitchingLanguage.value = true
            orchestrator.stopSession()
            delay(LANGUAGE_SWITCH_DELAY_MS)
            orchestrator.startSession(_sourceLanguage.value.code, _targetLanguage.value.code)
            _isSwitchingLanguage.value = false
        }
    }

    fun startSession() {
        if (_isSessionActive.value) return
        if (!_hasMicPermission.value) return
        if (!networkUtils.isOnline()) return

        // Auto-stop after a prolonged period with no detected speech (any language -- see
        // TranslationOrchestrator.onIdleTimeout). Called from viewModelScope, not from inside
        // the orchestrator's own frame-processing coroutine, so this is exactly like a manual
        // stop-button press as far as the orchestrator is concerned.
        orchestrator.onIdleTimeout = { viewModelScope.launch { stopSession() } }

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

    companion object {
        /** Time given for the old WebSocket to close cleanly before opening the new one. */
        private const val LANGUAGE_SWITCH_DELAY_MS = 300L
    }
}
