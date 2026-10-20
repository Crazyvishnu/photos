package com.photosapp.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.photosapp.R
import com.photosapp.databinding.ItemFullscreenPhotoBinding
import com.photosapp.di.ServiceLocator
import kotlinx.coroutines.*

@OptIn(UnstableApi::class)
class FullscreenPhotoAdapter(private val scope: CoroutineScope, private val onMediaTap: () -> Unit) : ListAdapter<LocalPhoto, FullscreenPhotoAdapter.ViewHolder>(DiffCallback) {
    private val holders = mutableSetOf<ViewHolder>()
    private var immersive = false
    private var chromeInset = 0
    fun setAppearance(hidden: Boolean, inset: Int = chromeInset) {
        immersive = hidden; chromeInset = inset
        holders.toList().forEach { it.applyAppearance() }
    }
    fun stopPlayback() { holders.toList().forEach { it.stop() } }
    fun release() { holders.toList().forEach { it.release() }; holders.clear() }
    fun toggleMute(): Boolean? = holders.firstOrNull { it.hasPlayer() }?.toggleMute()
    fun frame(): android.graphics.Bitmap? = holders.firstOrNull { it.hasPlayer() }?.frame()
    data class Playback(val position: Long = 0, val duration: Long = 0, val playing: Boolean = false,
                        val muted: Boolean = false, val starting: Boolean = false)
    fun playback(uri: android.net.Uri): Playback = holders.firstOrNull { it.matches(uri) }?.playback() ?: Playback()
    fun togglePlayback(uri: android.net.Uri) { holders.firstOrNull { it.matches(uri) }?.togglePlayback() }
    fun seekTo(uri: android.net.Uri, fraction: Float) { holders.firstOrNull { it.matches(uri) }?.seekTo(fraction) }

    inner class ViewHolder(private val binding: ItemFullscreenPhotoBinding) : RecyclerView.ViewHolder(binding.root) {
        private var playbackJob: Job? = null
        private var player: ExoPlayer? = null
        private var currentItem: LocalPhoto? = null
        private var generation = 0
        fun applyAppearance() {
            val color = if (immersive) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            binding.root.setBackgroundColor(color)
            binding.playerView.setBackgroundColor(color)
            binding.playerView.setShutterBackgroundColor(color)
            // The app owns playback chrome; Media3's controller adds a grey scrim and black footer.
            binding.playerView.useController = false
            binding.playerView.hideController()
            binding.immersiveTouchTarget.visibility = if (immersive || player != null) View.VISIBLE else View.GONE
            binding.immersiveTouchTarget.setContentDescription(binding.root.context.getString(
                if (immersive) R.string.viewer_show_controls else R.string.viewer_hide_controls))
            binding.videoIndicator.visibility = if (currentItem?.isVideo == true && player == null && playbackJob?.isActive != true && !immersive) View.VISIBLE else View.GONE
            val top = if (immersive) 0 else (64 * binding.root.resources.displayMetrics.density).toInt()
            val bottom = if (immersive) 0 else chromeInset
            binding.playerView.setPadding(0, top, 0, bottom)
            binding.imageView.setPadding(0, if (currentItem?.isVideo == true) top else 0, 0, if (currentItem?.isVideo == true) bottom else 0)
            val videoOffset = if (currentItem?.isVideo == true) (top - bottom) / 2f else 0f
            binding.videoIndicator.translationY = videoOffset
            binding.mediaLoading.translationY = videoOffset
            (binding.mediaError.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                if (params.bottomMargin != bottom) { params.bottomMargin = bottom; binding.mediaError.layoutParams = params }
            }
            binding.mediaError.setBackgroundColor(color)
            binding.mediaError.setTextColor(if (immersive) android.graphics.Color.WHITE else android.graphics.Color.rgb(32, 33, 36))
        }
        fun hasPlayer() = player != null
        fun matches(uri: android.net.Uri) = currentItem?.uri == uri
        fun playback(): Playback {
            val active = player ?: return Playback(starting = playbackJob?.isActive == true)
            return Playback(active.currentPosition.coerceAtLeast(0), active.duration.coerceAtLeast(0),
                active.playWhenReady && active.playbackState != Player.STATE_ENDED, active.volume == 0f)
        }
        fun togglePlayback() {
            val active = player
            if (active == null) { if (playbackJob?.isActive != true) currentItem?.let { play(it) }; return }
            if (active.playWhenReady && active.playbackState != Player.STATE_ENDED) active.pause()
            else { if (active.playbackState == Player.STATE_ENDED) active.seekTo(0); active.play() }
        }
        fun seekTo(fraction: Float) {
            val active = player ?: return
            if (active.duration > 0) active.seekTo((active.duration * fraction.coerceIn(0f, 1f)).toLong())
        }
        fun toggleMute(): Boolean? = player?.let { it.volume = if (it.volume > 0f) 0f else 1f; it.volume == 0f }
        fun frame(): android.graphics.Bitmap? {
            val texture = binding.playerView.videoSurfaceView as? android.view.TextureView ?: return null
            if (!texture.isAvailable || player?.playbackState != Player.STATE_READY || texture.width <= 0 || texture.height <= 0) return null
            player?.pause()
            val scale = minOf(1f, 1920f / maxOf(texture.width, texture.height))
            return texture.getBitmap((texture.width * scale).toInt().coerceAtLeast(1), (texture.height * scale).toInt().coerceAtLeast(1))
        }

        fun stop() {
            generation++
            playbackJob?.cancel()
            playbackJob = null
            binding.playerView.player = null
            player?.release()
            player = null
            binding.root.keepScreenOn = false
            binding.playerView.visibility = View.GONE
            binding.mediaLoading.visibility = View.GONE
            binding.imageView.visibility = View.VISIBLE
            binding.videoIndicator.visibility = if (currentItem?.isVideo == true && !immersive) View.VISIBLE else View.GONE
            applyAppearance()
        }

        fun release() { stop(); binding.imageView.dispose() }

        fun bind(item: LocalPhoto) {
            stop()
            currentItem = item
            binding.imageView.contentDescription = item.name
            binding.videoIndicator.contentDescription = binding.root.context.getString(R.string.media_play, item.name)
            binding.mediaError.visibility = View.GONE
            binding.videoIndicator.visibility = if (item.isVideo) View.VISIBLE else View.GONE
            loadPoster(item)
            binding.imageView.setOnClickListener { onMediaTap() }
            binding.immersiveTouchTarget.setOnClickListener { onMediaTap() }
            binding.videoIndicator.setOnClickListener { play(item) }
            binding.mediaError.setOnClickListener { if (item.isVideo) play(item) else loadPoster(item) }
            applyAppearance()
        }

        private fun loadPoster(item: LocalPhoto) {
            binding.mediaError.visibility = View.GONE
            binding.imageView.load(if (item.isVideo) item.thumbnailLink ?: item.uri else item.uri) {
                item.accountEmail?.let { setHeader("X-Photos-Account", it) }
                size(2048, 2048)
                crossfade(false)
                listener(onStart = { binding.mediaLoading.visibility = View.VISIBLE },
                    onSuccess = { _, _ -> binding.mediaLoading.visibility = View.GONE },
                    onError = { _, _ ->
                        binding.mediaLoading.visibility = View.GONE
                        if (!item.isVideo) showError(R.string.media_load_error)
                    })
            }
        }

        private fun play(item: LocalPhoto) {
            if (!item.isVideo || !binding.root.isAttachedToWindow) return
            stopPlayback()
            binding.mediaError.visibility = View.GONE
            binding.mediaLoading.visibility = View.VISIBLE
            binding.videoIndicator.visibility = View.GONE
            val request = ++generation
            playbackJob = scope.launch {
                try {
                    val headers = withContext(Dispatchers.IO) {
                        item.accountEmail?.let { mapOf("Authorization" to "Bearer ${ServiceLocator.tokenForAccount(it)}") } ?: emptyMap()
                    }
                    if (request != generation || !binding.root.isAttachedToWindow) return@launch
                    val context = binding.root.context
                    val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
                        .setConnectTimeoutMs(30_000).setReadTimeoutMs(60_000)
                    val dataSource = DefaultDataSource.Factory(context, http)
                    val buffer = DefaultLoadControl.Builder()
                        .setBufferDurationsMs(10_000, 30_000, 1_000, 2_000)
                        .setTargetBufferBytes(32 * 1024 * 1024)
                        .setPrioritizeTimeOverSizeThresholds(false).build()
                    val next = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
                        .setLoadControl(buffer)
                        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource)).build()
                    player = next
                    binding.playerView.player = next
                    applyAppearance()
                    binding.playerView.visibility = View.VISIBLE
                    binding.imageView.visibility = View.GONE
                    binding.mediaLoading.visibility = View.GONE
                    next.addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) { if (request == generation) binding.root.keepScreenOn = isPlaying }
                        override fun onPlayerError(error: PlaybackException) {
                            if (request != generation) return
                            android.util.Log.e("PhotoPlayback", "Playback error ${error.errorCodeName}", error)
                            stop()
                            showError(R.string.media_video_error)
                        }
                    })
                    next.setMediaItem(MediaItem.fromUri(item.uri))
                    next.prepare()
                    next.playWhenReady = true
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    if (request == generation) {
                        android.util.Log.e("PhotoPlayback", "Unable to start video", error)
                        stop()
                        showError(R.string.media_video_error)
                    }
                }
            }
        }

        private fun showError(message: Int) {
            binding.mediaError.text = binding.root.context.getString(message) + "\n" + binding.root.context.getString(R.string.media_retry_load)
            binding.mediaError.visibility = View.VISIBLE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(ItemFullscreenPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))
    override fun onViewAttachedToWindow(holder: ViewHolder) { holders.add(holder); holder.applyAppearance(); super.onViewAttachedToWindow(holder) }
    override fun onViewDetachedFromWindow(holder: ViewHolder) { holder.stop(); holders.remove(holder); super.onViewDetachedFromWindow(holder) }
    override fun onViewRecycled(holder: ViewHolder) { holder.release(); holders.remove(holder); super.onViewRecycled(holder) }

    companion object DiffCallback : DiffUtil.ItemCallback<LocalPhoto>() {
        override fun areItemsTheSame(a: LocalPhoto, b: LocalPhoto) = a.uri == b.uri
        override fun areContentsTheSame(a: LocalPhoto, b: LocalPhoto) = a.uri == b.uri && a.name == b.name &&
            a.accountEmail == b.accountEmail && a.thumbnailLink == b.thumbnailLink && a.isVideo == b.isVideo
    }
}
