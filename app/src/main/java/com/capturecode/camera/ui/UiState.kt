package com.capturecode.camera.ui

enum class UiState {
    PREPARING,
    IDLE,
    PAUSE,
    RECORDING,  // Camera is recording, only display Pause/Resume & Stop button.
    FINALIZED,  // Recording just completes, disable all RECORDING UI controls.
    STATUS,
    STOP,   // For future use.
    SWITCH
}