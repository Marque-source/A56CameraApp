package com.a56camera.ui.settings

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsViewModel : ViewModel() {

    private val _settings = MutableStateFlow(CameraSettingsState())
    val settings: StateFlow<CameraSettingsState> = _settings.asStateFlow()

    fun updateSettings(newState: CameraSettingsState) {
        _settings.value = newState
    }
}
