package dev.jdtech.jellyfin.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.AppPreferences
import dev.jdtech.jellyfin.models.DownloadItemType
import dev.jdtech.jellyfin.models.EpisodeItem
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidSeason
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.models.isDownloaded
import dev.jdtech.jellyfin.models.isDownloading
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.utils.Downloader
import dev.jdtech.jellyfin.utils.DownloadQueueManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.ItemFields
import java.util.UUID
import javax.inject.Inject
import kotlin.random.Random

@HiltViewModel
class SeasonViewModel
@Inject
constructor(
    private val jellyfinRepository: JellyfinRepository,
    private val downloader: Downloader,
    private val downloadQueueManager: DownloadQueueManager,
    private val appPreferences: AppPreferences,
) : ViewModel() {
    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _downloadStatus = MutableStateFlow(Pair(0, 0))
    val downloadStatus = _downloadStatus.asStateFlow()

    private val _downloadError = MutableSharedFlow<UiText>()
    val downloadError = _downloadError.asSharedFlow()

    private val _navigateBack = MutableSharedFlow<Boolean>()
    val navigateBack = _navigateBack.asSharedFlow()

    private val eventsChannel = Channel<SeasonEvent>()
    val eventsChannelFlow = eventsChannel.receiveAsFlow()

    sealed class UiState {
        data class Normal(val episodes: List<EpisodeItem>) : UiState()
        data object Loading : UiState()
        data class Error(val error: Exception) : UiState()
    }

    private lateinit var season: FindroidSeason

    fun loadEpisodes(seriesId: UUID, seasonId: UUID, offline: Boolean) {
        viewModelScope.launch {
            _uiState.emit(UiState.Loading)
            try {
                season = getSeason(seasonId)
                val episodes = getEpisodes(seriesId, seasonId, offline)
                _uiState.emit(UiState.Normal(episodes))
            } catch (_: NullPointerException) {
                // Navigate back because item does not exist (probably because it's been deleted)
                eventsChannel.send(SeasonEvent.NavigateBack)
            } catch (e: Exception) {
                _uiState.emit(UiState.Error(e))
            }
        }
    }

    /**
     * Download all episodes in the season using the custom download queue manager.
     * This queues all eligible episodes for download instead of downloading them immediately.
     */
    fun download(sourceIndex: Int = 0, storageIndex: Int = 0, downloadWatched: Boolean = false) {
        viewModelScope.launch {
            val serverId = appPreferences.currentServer ?: return@launch
            var queuedCount = 0
            var errorOccurred = false

            for (episode in jellyfinRepository.getEpisodes(season.seriesId, season.id)) {
                val item = jellyfinRepository.getEpisode(episode.id)

                // Skip watched episodes if downloadWatched is false
                if (item.played && !downloadWatched) {
                    continue
                }

                // Skip already downloaded or downloading episodes
                if (item.isDownloaded() || item.isDownloading()) {
                    continue
                }

                // Skip episodes without sources
                if (item.sources.isEmpty()) {
                    continue
                }

                // Queue the episode for download
                val result = downloadQueueManager.queueDownload(
                    item = item,
                    sourceId = item.sources[sourceIndex].id,
                    serverId = serverId,
                    storageIndex = storageIndex
                )

                if (result.second != null) {
                    _downloadError.emit(result.second!!)
                    errorOccurred = true
                } else {
                    queuedCount++
                }
            }

            // Send signal to fragment that downloads have been queued
            _downloadStatus.emit(Pair(10, Random.nextInt()))

            if (queuedCount > 0 && !errorOccurred) {
                // Optionally emit a success message
            }
        }
    }

    suspend fun delete() {
        for (episode in jellyfinRepository.getEpisodes(season.seriesId, season.id, offline = true)) {
            downloader.deleteItem(episode, episode.sources.first { it.type == FindroidSourceType.LOCAL })
        }
    }

    private suspend fun getSeason(seasonId: UUID): FindroidSeason {
        return jellyfinRepository.getSeason(seasonId)
    }

    private suspend fun getEpisodes(seriesId: UUID, seasonId: UUID, offline: Boolean): List<EpisodeItem> {
        val header = EpisodeItem.Header(seriesId = season.seriesId, seasonId = season.id, seriesName = season.seriesName, seasonName = season.name)
        val episodes =
            jellyfinRepository.getEpisodes(seriesId, seasonId, fields = listOf(ItemFields.OVERVIEW), offline = offline)

        return listOf(header) + episodes.map { EpisodeItem.Episode(it) }
    }
}

sealed interface SeasonEvent {
    data object NavigateBack : SeasonEvent
}
