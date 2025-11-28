package dev.jdtech.jellyfin.utils

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.AppPreferences
import dev.jdtech.jellyfin.core.R
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.DownloadItem
import dev.jdtech.jellyfin.models.DownloadItemType
import dev.jdtech.jellyfin.models.DownloadState
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.work.DownloadWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for the custom download queue.
 * Handles queuing downloads, scheduling WorkManager tasks, and managing download state.
 */
@Singleton
class DownloadQueueManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
) {
    private val workManager = WorkManager.getInstance(context)

    /**
     * Add an item to the download queue.
     * Returns a Pair of the download ID and an optional error message.
     */
    fun queueDownload(
        item: FindroidItem,
        sourceId: String,
        serverId: String,
        storageIndex: Int = 0,
    ): Pair<UUID, UiText?> {
        // Check if already in queue
        val existingItem = database.getDownloadItemByItemId(item.id)
        if (existingItem != null && existingItem.state != DownloadState.FAILED && existingItem.state != DownloadState.CANCELLED) {
            return Pair(existingItem.id, UiText.StringResource(R.string.download_already_in_queue))
        }

        val itemType = when (item) {
            is FindroidMovie -> DownloadItemType.MOVIE
            is FindroidEpisode -> DownloadItemType.EPISODE
            else -> return Pair(UUID.randomUUID(), UiText.StringResource(R.string.download_unsupported_item_type))
        }

        val downloadItem = DownloadItem(
            itemId = item.id,
            sourceId = sourceId,
            serverId = serverId,
            itemName = item.name,
            itemType = itemType,
            state = DownloadState.PENDING,
            storageIndex = storageIndex,
        )

        database.insertDownloadItem(downloadItem)
        Timber.d("Queued download for item: ${item.name}")

        // Trigger processing of the queue
        processQueue()

        return Pair(downloadItem.id, null)
    }

    /**
     * Queue multiple items for download (e.g., for season download).
     */
    fun queueDownloads(
        items: List<Triple<FindroidItem, String, String>>, // item, sourceId, serverId
        storageIndex: Int = 0,
    ): List<Pair<UUID, UiText?>> {
        return items.map { (item, sourceId, serverId) ->
            queueDownload(item, sourceId, serverId, storageIndex)
        }
    }

    /**
     * Process the download queue and start downloads up to the concurrent limit.
     */
    fun processQueue() {
        val concurrentLimit = appPreferences.downloadConcurrentLimit
        val activeDownloads = database.getActiveDownloadsCount()
        val availableSlots = concurrentLimit - activeDownloads

        if (availableSlots <= 0) {
            Timber.d("Download slots full ($activeDownloads/$concurrentLimit active)")
            return
        }

        val pendingDownloads = database.getPendingDownloads(availableSlots)
        Timber.d("Starting ${pendingDownloads.size} downloads (${activeDownloads} active, $concurrentLimit max)")

        for (downloadItem in pendingDownloads) {
            startDownload(downloadItem)
        }
    }

    /**
     * Start a single download using WorkManager.
     */
    private fun startDownload(downloadItem: DownloadItem) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (appPreferences.downloadOverMobileData) {
                    NetworkType.CONNECTED
                } else {
                    NetworkType.UNMETERED
                }
            )
            .setRequiresBatteryNotLow(true)
            .build()

        val inputData = Data.Builder()
            .putString(DownloadWorker.DOWNLOAD_ITEM_ID_KEY, downloadItem.id.toString())
            .putString(DownloadWorker.ITEM_ID_KEY, downloadItem.itemId.toString())
            .putString(DownloadWorker.SOURCE_ID_KEY, downloadItem.sourceId)
            .putInt(DownloadWorker.STORAGE_INDEX_KEY, downloadItem.storageIndex)
            .putString(DownloadWorker.ITEM_TYPE_KEY, downloadItem.itemType.name)
            .build()

        val downloadWork = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .addTag("download_${downloadItem.id}")
            .addTag("download_item_${downloadItem.itemId}")
            .build()

        workManager.enqueueUniqueWork(
            "download_${downloadItem.id}",
            ExistingWorkPolicy.KEEP,
            downloadWork
        )

        // Update state to downloading (will be updated again when worker starts)
        database.updateDownloadState(downloadItem.id, DownloadState.PENDING)
        Timber.d("Started download work for: ${downloadItem.itemName}")
    }

    /**
     * Cancel a specific download.
     */
    fun cancelDownload(downloadItemId: UUID) {
        workManager.cancelUniqueWork("download_$downloadItemId")
        database.cancelDownload(downloadItemId)
        Timber.d("Cancelled download: $downloadItemId")

        // Process queue to start next pending download
        processQueue()
    }

    /**
     * Cancel all downloads.
     */
    fun cancelAllDownloads() {
        val activeDownloads = database.getDownloadItemsByStates(
            listOf(DownloadState.PENDING, DownloadState.DOWNLOADING)
        )
        for (download in activeDownloads) {
            workManager.cancelUniqueWork("download_${download.id}")
            database.cancelDownload(download.id)
        }
        Timber.d("Cancelled ${activeDownloads.size} downloads")
    }

    /**
     * Retry a failed download.
     */
    fun retryDownload(downloadItemId: UUID) {
        val downloadItem = database.getDownloadItem(downloadItemId) ?: return
        if (downloadItem.state == DownloadState.FAILED || downloadItem.state == DownloadState.CANCELLED) {
            database.updateDownloadState(downloadItemId, DownloadState.PENDING)
            processQueue()
            Timber.d("Retrying download: ${downloadItem.itemName}")
        }
    }

    /**
     * Remove a download from the queue.
     */
    fun removeDownload(downloadItemId: UUID) {
        workManager.cancelUniqueWork("download_$downloadItemId")
        database.deleteDownloadItem(downloadItemId)
        Timber.d("Removed download: $downloadItemId")

        // Process queue to start next pending download
        processQueue()
    }

    /**
     * Get all downloads as a Flow for UI updates.
     */
    fun getAllDownloadsFlow(): Flow<List<DownloadItem>> {
        return database.getAllDownloadItemsFlow()
    }

    /**
     * Get all downloads.
     */
    fun getAllDownloads(): List<DownloadItem> {
        return database.getAllDownloadItems()
    }

    /**
     * Get pending downloads.
     */
    fun getPendingDownloads(): List<DownloadItem> {
        return database.getDownloadItemsByState(DownloadState.PENDING)
    }

    /**
     * Get active downloads (downloading).
     */
    fun getActiveDownloads(): List<DownloadItem> {
        return database.getDownloadItemsByState(DownloadState.DOWNLOADING)
    }

    /**
     * Clear completed downloads from the queue.
     */
    fun clearCompletedDownloads() {
        database.clearCompletedDownloads()
        Timber.d("Cleared completed downloads")
    }

    /**
     * Called when a download completes to process the next item in queue.
     */
    fun onDownloadComplete(downloadItemId: UUID) {
        processQueue()
    }

    /**
     * Observe WorkManager state for a specific download.
     */
    fun observeDownloadState(downloadItemId: UUID): Flow<WorkInfo.State?> {
        return workManager.getWorkInfosForUniqueWorkFlow("download_$downloadItemId")
            .map { workInfos ->
                workInfos.firstOrNull()?.state
            }
    }
}
