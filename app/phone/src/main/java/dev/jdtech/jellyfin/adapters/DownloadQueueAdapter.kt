package dev.jdtech.jellyfin.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.jdtech.jellyfin.R
import dev.jdtech.jellyfin.databinding.DownloadQueueItemBinding
import dev.jdtech.jellyfin.models.DownloadItem
import dev.jdtech.jellyfin.models.DownloadState
import java.util.UUID
import dev.jdtech.jellyfin.core.R as CoreR

/**
 * Adapter for displaying download queue items in a RecyclerView.
 */
class DownloadQueueAdapter(
    private val onCancelClick: (UUID) -> Unit,
    private val onRetryClick: (UUID) -> Unit,
) : ListAdapter<DownloadItem, DownloadQueueAdapter.DownloadQueueViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DownloadQueueViewHolder {
        return DownloadQueueViewHolder(
            DownloadQueueItemBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(holder: DownloadQueueViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class DownloadQueueViewHolder(
        private val binding: DownloadQueueItemBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(downloadItem: DownloadItem) {
            binding.downloadItemName.text = downloadItem.itemName

            // Set status text and progress based on state
            val context = binding.root.context
            when (downloadItem.state) {
                DownloadState.PENDING -> {
                    binding.downloadStatusText.text = context.getString(CoreR.string.download_queued)
                    binding.downloadProgressText.isVisible = false
                    binding.downloadProgressBar.isIndeterminate = true
                    binding.downloadProgressBar.isVisible = false
                    binding.downloadActionButton.setImageResource(CoreR.drawable.ic_close)
                    binding.downloadActionButton.contentDescription = context.getString(CoreR.string.cancel_download)
                    binding.downloadActionButton.setOnClickListener {
                        onCancelClick(downloadItem.id)
                    }
                }
                DownloadState.DOWNLOADING -> {
                    binding.downloadStatusText.text = context.getString(CoreR.string.download_downloading)
                    binding.downloadProgressText.isVisible = true
                    binding.downloadProgressText.text = context.getString(CoreR.string.download_progress, downloadItem.progress)
                    binding.downloadProgressBar.isIndeterminate = false
                    binding.downloadProgressBar.isVisible = true
                    binding.downloadProgressBar.progress = downloadItem.progress
                    binding.downloadActionButton.setImageResource(CoreR.drawable.ic_close)
                    binding.downloadActionButton.contentDescription = context.getString(CoreR.string.cancel_download)
                    binding.downloadActionButton.setOnClickListener {
                        onCancelClick(downloadItem.id)
                    }
                }
                DownloadState.PAUSED -> {
                    binding.downloadStatusText.text = context.getString(CoreR.string.download_paused)
                    binding.downloadProgressText.isVisible = true
                    binding.downloadProgressText.text = context.getString(CoreR.string.download_progress, downloadItem.progress)
                    binding.downloadProgressBar.isIndeterminate = false
                    binding.downloadProgressBar.isVisible = true
                    binding.downloadProgressBar.progress = downloadItem.progress
                    binding.downloadActionButton.setImageResource(CoreR.drawable.ic_close)
                    binding.downloadActionButton.contentDescription = context.getString(CoreR.string.cancel_download)
                    binding.downloadActionButton.setOnClickListener {
                        onCancelClick(downloadItem.id)
                    }
                }
                DownloadState.FAILED -> {
                    binding.downloadStatusText.text = downloadItem.errorMessage ?: context.getString(CoreR.string.download_failed)
                    binding.downloadProgressText.isVisible = false
                    binding.downloadProgressBar.isVisible = false
                    binding.downloadActionButton.setImageResource(CoreR.drawable.ic_retry)
                    binding.downloadActionButton.contentDescription = context.getString(CoreR.string.retry_download)
                    binding.downloadActionButton.setOnClickListener {
                        onRetryClick(downloadItem.id)
                    }
                }
                DownloadState.COMPLETED -> {
                    binding.downloadStatusText.text = context.getString(CoreR.string.download_completed)
                    binding.downloadProgressText.isVisible = false
                    binding.downloadProgressBar.isVisible = false
                    binding.downloadProgressBar.progress = 100
                    binding.downloadActionButton.isVisible = false
                }
                DownloadState.CANCELLED -> {
                    binding.downloadStatusText.text = context.getString(CoreR.string.download_cancelled)
                    binding.downloadProgressText.isVisible = false
                    binding.downloadProgressBar.isVisible = false
                    binding.downloadActionButton.setImageResource(CoreR.drawable.ic_retry)
                    binding.downloadActionButton.contentDescription = context.getString(CoreR.string.retry_download)
                    binding.downloadActionButton.setOnClickListener {
                        onRetryClick(downloadItem.id)
                    }
                }
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<DownloadItem>() {
        override fun areItemsTheSame(oldItem: DownloadItem, newItem: DownloadItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: DownloadItem, newItem: DownloadItem): Boolean {
            return oldItem == newItem
        }
    }
}
