package com.socialdl.src.feature.downloads

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.common.util.MediaStorageUtils
import com.socialdl.src.core.database.dao.DownloadDao
import com.socialdl.src.core.database.entity.DownloadHistoryEntity
import com.socialdl.src.core.download.DownloadManager
import com.socialdl.src.core.model.DownloadTask
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class DownloadsUiState(
    val activeTasks: List<DownloadTask> = emptyList(),
    val historyItems: List<DownloadHistoryEntity> = emptyList(),
)
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadManager: DownloadManager,
    private val downloadDao: DownloadDao,
) : ViewModel() {

    private val tag = "DownloadsViewModel"
    private val refreshTrigger = MutableStateFlow(0)
    private val isPurgingStale = AtomicBoolean(false)

    // Debounce handler for MediaStore change notifications (they can fire in rapid bursts).
    private val handler = Handler(Looper.getMainLooper())

    private val contentObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            // Debounce: MediaStore can fire several onChange calls for one logical change.
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ refresh() }, 1000)
        }
    }

    init {
        try {
            context.contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                contentObserver
            )
            context.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                contentObserver
            )
        } catch (e: Exception) {
            Logger.w(tag, "Could not register MediaStore content observer", e)
        }
        purgeStaleRecords()
    }

    private fun purgeStaleRecords() {
        // Guard against overlapping purge runs with an atomic CAS instead of a checked-then-set flag.
        if (!isPurgingStale.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentHistory = downloadDao.getAllHistory().first()
                val staleIds = currentHistory.filterNot { MediaStorageUtils.doesMediaFileExist(context, it.localUri) }.map { it.id }
                if (staleIds.isNotEmpty()) {
                    Logger.i(tag, "Purging ${staleIds.size} stale download history record(s) missing from storage")
                    downloadDao.deleteHistory(staleIds)
                }
            } catch (e: Exception) {
                Logger.w(tag, "Error purging stale download history", e)
            } finally {
                isPurgingStale.set(false)
            }
        }
    }

    /**
     * History validity scan runs only when the history table or the refresh trigger changes.
     * Progress ticks from [combine]d activeTasks below no longer re-run the disk IO scan.
     */
    private val validHistoryFlow = combine(
        downloadDao.getAllHistory(),
        refreshTrigger
    ) { historyList, _ ->
        withContext(Dispatchers.IO) {
            historyList.filter { item ->
                MediaStorageUtils.doesMediaFileExist(context, item.localUri)
            }
        }
    }

    val uiState: StateFlow<DownloadsUiState> = combine(
        downloadManager.activeTasks,
        validHistoryFlow
    ) { activeMap, validHistory ->
        // Pure filter/map only — no IO on this path.
        val completedIds = validHistory.map { it.id }.toSet()
        val filteredActive = activeMap.values.filterNot { it.id in completedIds }

        DownloadsUiState(
            activeTasks = filteredActive,
            historyItems = validHistory
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DownloadsUiState()
    )

    fun refresh() {
        refreshTrigger.update { it + 1 }
        purgeStaleRecords()
    }

    fun cancelTask(taskId: String) {
        downloadManager.cancelDownload(taskId)
    }

    // One-shot user message (e.g. deletion failures) consumed by the Screen as a toast.
    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    fun clearUserMessage() {
        _userMessage.value = null
    }

    fun deleteHistory(item: DownloadHistoryEntity) {
        deleteHistory(item.id)
    }

    fun deleteHistory(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val persisted = downloadDao.getHistoryById(id)
            val localUri = persisted?.localUri
            val fileDeleted = localUri == null || MediaStorageUtils.deleteMediaFromStorage(context, localUri)
            if (fileDeleted) {
                downloadDao.deleteHistory(id)
            } else {
                // The file could not be removed; keep the DB record so we do not leave a
                // silent dangling entry — the UI shows a toast and the record remains
                // dismissible (and a later retry / stale purge can still clean it up).
                Logger.w(tag, "File deletion failed for history $id; keeping DB record")
                _userMessage.value = "Deleted entry but file could not be removed"
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            context.contentResolver.unregisterContentObserver(contentObserver)
        } catch (_: Exception) {}
    }
}
