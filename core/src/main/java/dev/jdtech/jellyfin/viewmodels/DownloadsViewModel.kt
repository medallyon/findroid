package dev.jdtech.jellyfin.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.AppPreferences
import dev.jdtech.jellyfin.Constants
import dev.jdtech.jellyfin.core.R
import dev.jdtech.jellyfin.models.DownloadItem
import dev.jdtech.jellyfin.models.DownloadState
import dev.jdtech.jellyfin.models.FavoriteSection
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.utils.DownloadQueueManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel
@Inject
constructor(
    private val appPreferences: AppPreferences,
    private val repository: JellyfinRepository,
    private val downloadQueueManager: DownloadQueueManager,
) : ViewModel() {
    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _downloadQueue = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloadQueue = _downloadQueue.asStateFlow()

    private val eventsChannel = Channel<DownloadsEvent>()
    val eventsChannelFlow = eventsChannel.receiveAsFlow()

    sealed class UiState {
        data class Normal(val sections: List<FavoriteSection>, val queuedDownloads: List<DownloadItem> = emptyList()) : UiState()
        data object Loading : UiState()
        data class Error(val error: Exception) : UiState()
    }

    init {
        testServerConnection()
        observeDownloadQueue()
    }

    private fun testServerConnection() {
        viewModelScope.launch {
            try {
                if (appPreferences.isOffline) return@launch
                repository.getPublicSystemInfo()
                // Give the UI a chance to load
                delay(100)
            } catch (e: Exception) {
                eventsChannel.send(DownloadsEvent.ConnectionError(e))
            }
        }
    }

    private fun observeDownloadQueue() {
        viewModelScope.launch {
            downloadQueueManager.getAllDownloadsFlow().collect { downloads ->
                val activeDownloads = downloads.filter {
                    it.state == DownloadState.PENDING ||
                        it.state == DownloadState.DOWNLOADING ||
                        it.state == DownloadState.PAUSED ||
                        it.state == DownloadState.FAILED
                }
                _downloadQueue.emit(activeDownloads)

                // Refresh the UI state with current queue
                val currentState = _uiState.value
                if (currentState is UiState.Normal) {
                    _uiState.emit(currentState.copy(queuedDownloads = activeDownloads))
                }
            }
        }
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.emit(UiState.Loading)

            val sections = mutableListOf<FavoriteSection>()

            val items = repository.getDownloads()

            FavoriteSection(
                Constants.FAVORITE_TYPE_MOVIES,
                UiText.StringResource(R.string.movies_label),
                items.filterIsInstance<FindroidMovie>(),
            ).let {
                if (it.items.isNotEmpty()) {
                    sections.add(
                        it,
                    )
                }
            }
            FavoriteSection(
                Constants.FAVORITE_TYPE_SHOWS,
                UiText.StringResource(R.string.shows_label),
                items.filterIsInstance<FindroidShow>(),
            ).let {
                if (it.items.isNotEmpty()) {
                    sections.add(
                        it,
                    )
                }
            }

            // Get current download queue
            val activeDownloads = downloadQueueManager.getAllDownloads().filter {
                it.state == DownloadState.PENDING ||
                    it.state == DownloadState.DOWNLOADING ||
                    it.state == DownloadState.PAUSED ||
                    it.state == DownloadState.FAILED
            }

            _uiState.emit(UiState.Normal(sections, activeDownloads))
        }
    }

    /**
     * Cancel a specific download from the queue.
     */
    fun cancelDownload(downloadItemId: UUID) {
        viewModelScope.launch {
            downloadQueueManager.cancelDownload(downloadItemId)
            loadData() // Refresh the UI
        }
    }

    /**
     * Retry a failed download.
     */
    fun retryDownload(downloadItemId: UUID) {
        viewModelScope.launch {
            downloadQueueManager.retryDownload(downloadItemId)
        }
    }

    /**
     * Remove a download from the queue.
     */
    fun removeDownload(downloadItemId: UUID) {
        viewModelScope.launch {
            downloadQueueManager.removeDownload(downloadItemId)
            loadData() // Refresh the UI
        }
    }

    /**
     * Cancel all active downloads.
     */
    fun cancelAllDownloads() {
        viewModelScope.launch {
            downloadQueueManager.cancelAllDownloads()
            loadData() // Refresh the UI
        }
    }

    /**
     * Clear completed downloads from the queue.
     */
    fun clearCompletedDownloads() {
        viewModelScope.launch {
            downloadQueueManager.clearCompletedDownloads()
            loadData() // Refresh the UI
        }
    }
}

sealed interface DownloadsEvent {
    data class ConnectionError(val error: Exception) : DownloadsEvent
}
