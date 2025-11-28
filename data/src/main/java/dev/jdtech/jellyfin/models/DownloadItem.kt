package dev.jdtech.jellyfin.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Represents the state of a download task in the custom download manager.
 */
enum class DownloadState {
    PENDING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Entity representing a download task in the download queue.
 */
@Entity(tableName = "downloadQueue")
data class DownloadItem(
    @PrimaryKey
    val id: UUID = UUID.randomUUID(),
    val itemId: UUID,
    val sourceId: String,
    val serverId: String,
    val itemName: String,
    val itemType: DownloadItemType,
    val state: DownloadState = DownloadState.PENDING,
    val progress: Int = 0,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    val storageIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
)

/**
 * Type of item being downloaded.
 */
enum class DownloadItemType {
    MOVIE,
    EPISODE
}
