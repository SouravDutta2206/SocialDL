package com.socialdl.src.feature.home

import androidx.lifecycle.ViewModel
import com.socialdl.src.core.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Owns the "ask once, on first launch" bookkeeping for the startup permission
 * prompt. The grant state itself is never cached — it is re-read when a download
 * starts.
 */
@HiltViewModel
class StartupPermissionsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    /**
     * @return true exactly once per install: the first composition that reaches
     *   this method. The flag is persisted before the dialog is shown, so a
     *   rotation or process restart during the prompt cannot ask again.
     */
    suspend fun claimStartupPrompt(): Boolean {
        if (settingsRepository.startupPermissionsRequested.first()) return false
        settingsRepository.setStartupPermissionsRequested(true)
        return true
    }
}
