package dev.jdtech.jellyfin.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.AppPreferences
import dev.jdtech.jellyfin.adapters.DownloadQueueAdapter
import dev.jdtech.jellyfin.adapters.FavoritesListAdapter
import dev.jdtech.jellyfin.databinding.FragmentDownloadsBinding
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.utils.restart
import dev.jdtech.jellyfin.utils.safeNavigate
import dev.jdtech.jellyfin.viewmodels.DownloadsEvent
import dev.jdtech.jellyfin.viewmodels.DownloadsViewModel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import dev.jdtech.jellyfin.core.R as CoreR

@AndroidEntryPoint
class DownloadsFragment : Fragment() {
    private lateinit var binding: FragmentDownloadsBinding
    private val viewModel: DownloadsViewModel by viewModels()

    @Inject
    lateinit var appPreferences: AppPreferences

    private lateinit var downloadQueueAdapter: DownloadQueueAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentDownloadsBinding.inflate(inflater, container, false)

        // Initialize download queue adapter
        downloadQueueAdapter = DownloadQueueAdapter(
            onCancelClick = { downloadItemId ->
                viewModel.cancelDownload(downloadItemId)
            },
            onRetryClick = { downloadItemId ->
                viewModel.retryDownload(downloadItemId)
            }
        )
        binding.downloadQueueRecyclerView.adapter = downloadQueueAdapter

        binding.downloadsRecyclerView.adapter = FavoritesListAdapter { item ->
            navigateToMediaItem(item)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.eventsChannelFlow.collect { event ->
                        when (event) {
                            is DownloadsEvent.ConnectionError -> {
                                Snackbar.make(binding.root, CoreR.string.no_server_connection, Snackbar.LENGTH_INDEFINITE)
                                    .setTextMaxLines(2)
                                    .setAction(CoreR.string.offline_mode) {
                                        appPreferences.offlineMode = true
                                        activity?.restart()
                                    }
                                    .show()
                            }
                        }
                    }
                }
                launch {
                    viewModel.uiState.collect { uiState ->
                        Timber.d("$uiState")
                        when (uiState) {
                            is DownloadsViewModel.UiState.Normal -> bindUiStateNormal(uiState)
                            is DownloadsViewModel.UiState.Loading -> bindUiStateLoading()
                            is DownloadsViewModel.UiState.Error -> Unit
                        }
                    }
                }
                launch {
                    viewModel.downloadQueue.collect { queuedDownloads ->
                        // Update download queue section visibility
                        binding.downloadQueueSection.isVisible = queuedDownloads.isNotEmpty()
                        downloadQueueAdapter.submitList(queuedDownloads)
                    }
                }
            }
        }

        return binding.root
    }

    override fun onResume() {
        super.onResume()

        viewModel.loadData()
    }

    private fun bindUiStateNormal(uiState: DownloadsViewModel.UiState.Normal) {
        binding.loadingIndicator.isVisible = false
        binding.nestedScrollView.isVisible = true
        binding.errorLayout.errorPanel.isVisible = false

        // Show download queue section if there are queued downloads
        binding.downloadQueueSection.isVisible = uiState.queuedDownloads.isNotEmpty()
        downloadQueueAdapter.submitList(uiState.queuedDownloads)

        // Show downloaded content
        val adapter = binding.downloadsRecyclerView.adapter as FavoritesListAdapter
        adapter.submitList(uiState.sections)

        // Show "no downloads" message only if both queue and downloads are empty
        binding.noDownloadsText.isVisible = uiState.sections.isEmpty() && uiState.queuedDownloads.isEmpty()
    }

    private fun bindUiStateLoading() {
        binding.loadingIndicator.isVisible = true
        binding.errorLayout.errorPanel.isVisible = false
    }

    private fun navigateToMediaItem(item: FindroidItem) {
        when (item) {
            is FindroidMovie -> {
                findNavController().safeNavigate(
                    DownloadsFragmentDirections.actionDownloadsFragmentToMovieFragment(
                        item.id,
                        item.name,
                    ),
                )
            }
            is FindroidShow -> {
                findNavController().safeNavigate(
                    DownloadsFragmentDirections.actionDownloadsFragmentToShowFragment(
                        item.id,
                        item.name,
                        true,
                    ),
                )
            }
        }
    }
}
