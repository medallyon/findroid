package dev.jdtech.jellyfin.work

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.jdtech.jellyfin.AppPreferences
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.DownloadItem
import dev.jdtech.jellyfin.models.DownloadItemType
import dev.jdtech.jellyfin.models.DownloadState
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidSources
import dev.jdtech.jellyfin.models.FindroidTrickplayInfo
import dev.jdtech.jellyfin.models.VideoQuality
import dev.jdtech.jellyfin.models.toFindroidEpisodeDto
import dev.jdtech.jellyfin.models.toFindroidMediaStreamDto
import dev.jdtech.jellyfin.models.toFindroidMovieDto
import dev.jdtech.jellyfin.models.toFindroidSeasonDto
import dev.jdtech.jellyfin.models.toFindroidSegmentsDto
import dev.jdtech.jellyfin.models.toFindroidShowDto
import dev.jdtech.jellyfin.models.toFindroidSourceDto
import dev.jdtech.jellyfin.models.toFindroidTrickplayInfoDto
import dev.jdtech.jellyfin.models.toFindroidUserDataDto
import dev.jdtech.jellyfin.repository.JellyfinRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.ceil

/**
 * Worker that handles downloading media items in the background using WorkManager.
 * This replaces the Android DownloadManager with a custom implementation that supports
 * queue management, concurrent download limits, and bandwidth throttling.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val workerParams: WorkerParameters,
    private val database: ServerDatabaseDao,
    private val jellyfinRepository: JellyfinRepository,
    private val appPreferences: AppPreferences,
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val DOWNLOAD_ITEM_ID_KEY = "download_item_id"
        const val ITEM_ID_KEY = "item_id"
        const val SOURCE_ID_KEY = "source_id"
        const val STORAGE_INDEX_KEY = "storage_index"
        const val ITEM_TYPE_KEY = "item_type"

        const val PROGRESS_KEY = "progress"
        const val BYTES_DOWNLOADED_KEY = "bytes_downloaded"
        const val TOTAL_BYTES_KEY = "total_bytes"

        private const val BUFFER_SIZE = 8 * 1024 // 8KB buffer
    }

    override suspend fun doWork(): Result {
        val downloadItemIdString = inputData.getString(DOWNLOAD_ITEM_ID_KEY)
            ?: return Result.failure()
        val downloadItemId = UUID.fromString(downloadItemIdString)

        val itemIdString = inputData.getString(ITEM_ID_KEY)
            ?: return Result.failure()
        val itemId = UUID.fromString(itemIdString)

        val sourceId = inputData.getString(SOURCE_ID_KEY)
            ?: return Result.failure()

        val storageIndex = inputData.getInt(STORAGE_INDEX_KEY, 0)
        val itemType = inputData.getString(ITEM_TYPE_KEY)
            ?: return Result.failure()

        return withContext(Dispatchers.IO) {
            try {
                // Update state to downloading
                database.updateDownloadState(downloadItemId, DownloadState.DOWNLOADING)

                // Get the item to download
                val item = when (DownloadItemType.valueOf(itemType)) {
                    DownloadItemType.MOVIE -> jellyfinRepository.getMovie(itemId)
                    DownloadItemType.EPISODE -> jellyfinRepository.getEpisode(itemId)
                }

                // Perform the download
                val result = downloadItem(item, sourceId, storageIndex, downloadItemId)

                if (result) {
                    database.markDownloadCompleted(downloadItemId)
                    Result.success()
                } else {
                    database.markDownloadFailed(downloadItemId, "Download failed")
                    Result.failure()
                }
            } catch (e: Exception) {
                Timber.e(e, "Download failed for item $itemId")
                database.markDownloadFailed(downloadItemId, e.message)
                Result.failure()
            }
        }
    }

    private suspend fun downloadItem(
        item: FindroidItem,
        sourceId: String,
        storageIndex: Int,
        downloadItemId: UUID,
    ): Boolean {
        try {
            val source = jellyfinRepository.getMediaSources(item.id, true).first { it.id == sourceId }
            val segments = jellyfinRepository.getSegments(item.id)
            val trickplayInfo = if (item is FindroidSources) {
                item.trickplayInfo?.get(sourceId)
            } else {
                null
            }

            val storageLocation = context.getExternalFilesDirs(null).getOrNull(storageIndex)
            if (storageLocation == null || Environment.getExternalStorageState(storageLocation) != Environment.MEDIA_MOUNTED) {
                throw Exception("Storage unavailable")
            }

            val path = Uri.fromFile(File(storageLocation, "downloads/${item.id}.${source.id}.download"))
            val stats = StatFs(storageLocation.path)
            if (stats.availableBytes < source.size) {
                throw Exception(
                    "Not enough storage: requires ${Formatter.formatFileSize(context, source.size)}, " +
                        "available ${Formatter.formatFileSize(context, stats.availableBytes)}"
                )
            }

            // Save metadata to database based on item type
            when (item) {
                is FindroidMovie -> {
                    database.insertMovie(item.toFindroidMovieDto(appPreferences.currentServer!!))
                    database.insertSource(source.toFindroidSourceDto(item.id, path.path.orEmpty()))
                    database.insertUserData(item.toFindroidUserDataDto(jellyfinRepository.getUserId()))
                }
                is FindroidEpisode -> {
                    database.insertShow(
                        jellyfinRepository.getShow(item.seriesId).toFindroidShowDto(appPreferences.currentServer!!)
                    )
                    database.insertSeason(
                        jellyfinRepository.getSeason(item.seasonId).toFindroidSeasonDto()
                    )
                    database.insertEpisode(item.toFindroidEpisodeDto(appPreferences.currentServer!!))
                    database.insertSource(source.toFindroidSourceDto(item.id, path.path.orEmpty()))
                    database.insertUserData(item.toFindroidUserDataDto(jellyfinRepository.getUserId()))
                }
            }

            // Download external media streams (subtitles, etc.)
            downloadExternalMediaStreams(item, source, storageIndex)

            // Download trickplay data if available
            if (trickplayInfo != null) {
                downloadTrickplayData(item.id, sourceId, trickplayInfo)
            }

            // Save segments
            segments.forEach {
                database.insertSegment(it.toFindroidSegmentsDto(item.id))
            }

            // Determine the download URL based on quality settings
            val downloadUrl = if (appPreferences.downloadQuality != VideoQuality.Original.toString()) {
                downloadEmbeddedMediaStreams(item, source, storageIndex)
                getTranscodedUrl(item.id, appPreferences.downloadQuality!!)
            } else {
                source.path.toUri()
            }

            // Perform the actual file download
            if (downloadUrl != null) {
                downloadFile(downloadUrl, path, downloadItemId)
            } else {
                throw Exception("Could not determine download URL")
            }

            // Rename file after successful download
            val finalPath = path.path?.replace(".download", "") ?: return false
            val downloadFile = File(path.path!!)
            val finalFile = File(finalPath)
            if (downloadFile.renameTo(finalFile)) {
                database.setSourcePath(source.id, finalPath)
            } else {
                throw Exception("Failed to rename downloaded file")
            }

            return true
        } catch (e: Exception) {
            Timber.e(e, "Error downloading item ${item.id}")
            throw e
        }
    }

    private fun downloadFile(
        url: Uri,
        destinationUri: Uri,
        downloadItemId: UUID,
    ) {
        val destinationFile = File(destinationUri.path!!)
        destinationFile.parentFile?.mkdirs()

        val connection = URL(url.toString()).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000

            val totalBytes = connection.contentLengthLong
            var bytesDownloaded = 0L

            // Update total bytes in database
            database.updateDownloadProgress(downloadItemId, DownloadState.DOWNLOADING, 0, 0)

            connection.inputStream.use { input ->
                FileOutputStream(destinationFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int

                    val bandwidthLimit = appPreferences.downloadBandwidthLimit
                    val startTime = System.currentTimeMillis()

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (isStopped) {
                            throw Exception("Download cancelled")
                        }

                        output.write(buffer, 0, bytesRead)
                        bytesDownloaded += bytesRead

                        // Calculate and update progress
                        val progress = if (totalBytes > 0) {
                            ((bytesDownloaded * 100) / totalBytes).toInt()
                        } else {
                            -1
                        }

                        // Update progress in database
                        database.updateDownloadProgress(
                            downloadItemId,
                            DownloadState.DOWNLOADING,
                            progress,
                            bytesDownloaded
                        )

                        // Apply bandwidth throttling if configured
                        if (bandwidthLimit > 0) {
                            val elapsedTime = System.currentTimeMillis() - startTime
                            val expectedTime = (bytesDownloaded * 1000) / (bandwidthLimit * 1024) // Convert KB/s to ms
                            if (elapsedTime < expectedTime) {
                                Thread.sleep(expectedTime - elapsedTime)
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadExternalMediaStreams(
        item: FindroidItem,
        source: dev.jdtech.jellyfin.models.FindroidSource,
        storageIndex: Int = 0,
    ) {
        val storageLocation = context.getExternalFilesDirs(null)[storageIndex]
        for (mediaStream in source.mediaStreams.filter { it.isExternal }) {
            val id = UUID.randomUUID()
            val streamPath = Uri.fromFile(
                File(storageLocation, "downloads/${item.id}.${source.id}.$id.download")
            )
            database.insertMediaStream(
                mediaStream.toFindroidMediaStreamDto(id, source.id, streamPath.path.orEmpty())
            )

            try {
                // Download the external media stream file
                val connection = URL(mediaStream.path).openConnection() as HttpURLConnection
                try {
                    connection.inputStream.use { input ->
                        FileOutputStream(File(streamPath.path!!)).use { output ->
                            input.copyTo(output)
                        }
                    }
                    // Rename after download
                    val finalPath = streamPath.path!!.replace(".download", "")
                    File(streamPath.path!!).renameTo(File(finalPath))
                    database.setMediaStreamPath(id, finalPath)
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to download media stream")
            }
        }
    }

    private fun downloadEmbeddedMediaStreams(
        item: FindroidItem,
        source: dev.jdtech.jellyfin.models.FindroidSource,
        storageIndex: Int = 0,
    ) {
        val storageLocation = context.getExternalFilesDirs(null)[storageIndex]
        val subtitleStreams = source.mediaStreams.filter {
            !it.isExternal && it.type == MediaStreamType.SUBTITLE && it.path != null
        }

        for (mediaStream in subtitleStreams) {
            var deliveryUrl = mediaStream.path!!
            if (mediaStream.codec == "webvtt") {
                deliveryUrl = deliveryUrl.replace("Stream.srt", "Stream.vtt")
            }

            val id = UUID.randomUUID()
            val streamPath = Uri.fromFile(
                File(storageLocation, "downloads/${item.id}.${source.id}.$id.download")
            )
            database.insertMediaStream(
                mediaStream.toFindroidMediaStreamDto(id, source.id, streamPath.path.orEmpty())
            )

            try {
                val connection = URL(deliveryUrl).openConnection() as HttpURLConnection
                try {
                    connection.inputStream.use { input ->
                        FileOutputStream(File(streamPath.path!!)).use { output ->
                            input.copyTo(output)
                        }
                    }
                    val finalPath = streamPath.path!!.replace(".download", "")
                    File(streamPath.path!!).renameTo(File(finalPath))
                    database.setMediaStreamPath(id, finalPath)
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to download embedded media stream")
            }
        }
    }

    private suspend fun downloadTrickplayData(
        itemId: UUID,
        sourceId: String,
        trickplayInfo: FindroidTrickplayInfo,
    ) {
        val maxIndex = ceil(
            trickplayInfo.thumbnailCount.toDouble()
                .div(trickplayInfo.tileWidth * trickplayInfo.tileHeight)
        ).toInt()

        val byteArrays = mutableListOf<ByteArray>()
        for (i in 0..maxIndex) {
            jellyfinRepository.getTrickplayData(itemId, trickplayInfo.width, i)?.let { byteArray ->
                byteArrays.add(byteArray)
            }
        }
        saveTrickplayData(itemId, sourceId, trickplayInfo, byteArrays)
    }

    private fun saveTrickplayData(
        itemId: UUID,
        sourceId: String,
        trickplayInfo: FindroidTrickplayInfo,
        byteArrays: List<ByteArray>,
    ) {
        val basePath = "trickplay/$itemId/$sourceId"
        database.insertTrickplayInfo(trickplayInfo.toFindroidTrickplayInfoDto(sourceId))
        File(context.filesDir, basePath).mkdirs()
        for ((i, byteArray) in byteArrays.withIndex()) {
            val file = File(context.filesDir, "$basePath/$i")
            file.writeBytes(byteArray)
        }
    }

    private suspend fun getTranscodedUrl(
        itemId: UUID,
        quality: String,
    ): Uri? {
        val videoQuality = VideoQuality.fromString(quality) ?: return null
        return try {
            val deviceProfile = jellyfinRepository.buildDeviceProfile(
                VideoQuality.getBitrate(videoQuality),
                "mkv",
                EncodingContext.STATIC
            )
            val playbackInfo = jellyfinRepository.getPostedPlaybackInfo(
                itemId,
                false,
                deviceProfile,
                VideoQuality.getBitrate(videoQuality)
            )
            val mediaSourceId = playbackInfo.content.mediaSources.firstOrNull()?.id!!
            val playSessionId = playbackInfo.content.playSessionId!!
            val deviceId = jellyfinRepository.getDeviceId()

            jellyfinRepository.getVideoStreambyContainerUrl(
                itemId,
                deviceId,
                mediaSourceId,
                playSessionId,
                VideoQuality.getBitrate(videoQuality),
                "ts",
                VideoQuality.getHeight(videoQuality),
            ).toUri()
        } catch (e: Exception) {
            Timber.e(e, "Failed to get transcoded URL")
            null
        }
    }
}
