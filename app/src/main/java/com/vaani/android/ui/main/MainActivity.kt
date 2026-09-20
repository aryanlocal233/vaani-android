package com.vaani.android.ui.main

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.AdapterView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.vaani.android.R
import com.vaani.android.audio.ConversationState
import com.vaani.android.audio.TranslationForegroundService
import com.vaani.android.data.Language
import com.vaani.android.databinding.ActivityMainBinding
import com.vaani.android.utils.PermissionUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var languageAdapter: ArrayAdapter<Language>? = null
    private var suppressSpinnerCallbacks = false

    private val requestMicPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.setMicPermissionGranted(granted)
        if (granted) {
            viewModel.startSession()
        } else {
            showPermissionDeniedDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel.setMicPermissionGranted(PermissionUtils.hasRecordAudioPermission(this))

        setupSpinners()
        setupListeners()
        observeUiState()
    }

    private fun setupSpinners() {
        languageAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf())
        languageAdapter?.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSourceLang.adapter = languageAdapter
        binding.spinnerTargetLang.adapter = languageAdapter

        binding.spinnerSourceLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (suppressSpinnerCallbacks) return
                (parent?.getItemAtPosition(position) as? Language)?.let { viewModel.setSourceLanguage(it) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.spinnerTargetLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (suppressSpinnerCallbacks) return
                (parent?.getItemAtPosition(position) as? Language)?.let { viewModel.setTargetLanguage(it) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupListeners() {
        binding.btnSwapLanguages.setOnClickListener {
            viewModel.swapLanguages()
        }

        binding.btnStartStop.setOnClickListener {
            val state = viewModel.uiState.value
            if (state.isSessionActive) {
                stopSessionAndService()
            } else {
                requestMicPermissionAndStart()
            }
        }
    }

    private fun requestMicPermissionAndStart() {
        if (PermissionUtils.hasRecordAudioPermission(this)) {
            viewModel.setMicPermissionGranted(true)
            viewModel.startSession()
            startForegroundService(Intent(this, TranslationForegroundService::class.java))
        } else if (PermissionUtils.shouldShowRecordAudioRationale(this)) {
            showRationaleDialog()
        } else {
            requestMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun stopSessionAndService() {
        viewModel.stopSession()
        stopService(Intent(this, TranslationForegroundService::class.java))
    }

    private fun showRationaleDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(R.string.permission_mic_rationale)
            .setPositiveButton(R.string.permission_grant) { _, _ ->
                requestMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(R.string.permission_mic_denied)
            .setPositiveButton(R.string.permission_open_settings) { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderLanguages(state)
                    renderConversationState(state.conversationState)
                    renderTranscriptAndTranslation(state.transcript, state.translation)
                    renderConnectionStatus(state.isConnected)
                    renderError(state.error)
                    renderStartStopButton(state.isSessionActive)
                }
            }
        }
    }

    private fun renderLanguages(state: MainUiState) {
        val adapter = languageAdapter ?: return
        if (adapter.count != state.allLanguages.size) {
            adapter.clear()
            adapter.addAll(state.allLanguages)
        }

        suppressSpinnerCallbacks = true
        state.sourceLanguage?.let { src ->
            val idx = state.allLanguages.indexOfFirst { it.code == src.code }
            if (idx >= 0 && binding.spinnerSourceLang.selectedItemPosition != idx) {
                binding.spinnerSourceLang.setSelection(idx)
            }
        }
        state.targetLanguage?.let { tgt ->
            val idx = state.allLanguages.indexOfFirst { it.code == tgt.code }
            if (idx >= 0 && binding.spinnerTargetLang.selectedItemPosition != idx) {
                binding.spinnerTargetLang.setSelection(idx)
            }
        }
        suppressSpinnerCallbacks = false
    }

    private fun renderConversationState(state: ConversationState) {
        val (textRes, color) = when (state) {
            ConversationState.IDLE -> R.string.state_idle to R.color.state_idle
            ConversationState.LISTENING -> R.string.state_listening to R.color.state_listening
            ConversationState.PROCESSING -> R.string.state_processing to R.color.state_processing
            ConversationState.SPEAKING -> R.string.state_speaking to R.color.state_speaking
        }
        binding.tvConversationState.setText(textRes)
        binding.tvConversationState.setTextColor(ContextCompat.getColor(this, color))
        binding.waveformView.visibility = if (state == ConversationState.LISTENING) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
    }

    private fun renderTranscriptAndTranslation(transcript: String, translation: String) {
        binding.tvTranscript.text = transcript.ifBlank { getString(R.string.hint_transcript) }
        binding.tvTranslation.text = translation.ifBlank { getString(R.string.hint_translation) }
    }

    private fun renderConnectionStatus(isConnected: Boolean) {
        binding.tvConnectionStatus.setText(
            if (isConnected) R.string.status_connected else R.string.status_disconnected
        )
        binding.tvConnectionStatus.setTextColor(
            ContextCompat.getColor(this, if (isConnected) R.color.state_listening else R.color.text_secondary)
        )
    }

    private fun renderError(error: String?) {
        if (error.isNullOrBlank()) {
            binding.tvError.visibility = android.view.View.GONE
        } else {
            binding.tvError.visibility = android.view.View.VISIBLE
            binding.tvError.text = error
        }
    }

    private fun renderStartStopButton(isSessionActive: Boolean) {
        binding.btnStartStop.setText(if (isSessionActive) R.string.button_stop else R.string.button_start)
        binding.btnStartStop.setIconResource(
            if (isSessionActive) R.drawable.ic_stop else R.drawable.ic_mic_active
        )
    }
}
