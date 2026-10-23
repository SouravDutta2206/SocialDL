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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    private var isPurgingStale = false

    private val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            refresh()
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
        viewModelScope.launch(Dispatchers.IO) {
            if (isPurgingStale) return@launch
            isPurgingStale = true
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
                isPurgingStale = false
            }
        }
    }

    val uiState: StateFlow<DownloadsUiState> = combine(
        downloadManager.activeTasks,
        downloadDao.getAllHistory(),
        refreshTrigger
    ) { activeMap, historyList, _ ->
        val validHistory = withContext(Dispatchers.IO) {
            historyList.filter { item ->
                MediaStorageUtils.doesMediaFileExist(context, item.localUri)
            }
        }

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

    fun deleteHistory(item: DownloadHistoryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            MediaStorageUtils.deleteMediaFromStorage(context, item.localUri)
            downloadDao.deleteHistory(item.id)
        }
    }

    fun deleteHistory(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val item = downloadDao.getHistoryById(id)
            if (item != null) {
                MediaStorageUtils.deleteMediaFromStorage(context, item.localUri)
            }
            downloadDao.deleteHistory(id)
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            context.contentResolver.unregisterContentObserver(contentObserver)
        } catch (_: Exception) {}
    }
}
