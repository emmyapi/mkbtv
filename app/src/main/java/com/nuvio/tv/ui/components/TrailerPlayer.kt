package com.nuvio.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import com.nuvio.tv.core.player.LetterboxDetector
import com.nuvio.tv.core.player.LetterboxSampler
import com.nuvio.tv.core.player.LetterboxTracker
import com.nuvio.tv.core.player.LocalTrailerPlayerPool
import com.nuvio.tv.core.player.TrailerPlayerPool
import com.nuvio.tv.data.trailer.TrailerDisplayCapabilities
import com.nuvio.tv.data.trailer.YoutubeChunkedDataSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.TextureView
import com.nuvio.tv.R
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun TrailerPlayer(
    trailerUrl: String?,
    trailerAudioUrl: String? = null,
    isPlaying: Boolean,
    isPaused: Boolean = false,
    onEnded: () -> Unit,
    onError: () -> Unit = {},
    onFirstFrameRendered: () -> Unit = {},
    muted: Boolean = false,
    seekRequestToken: Int = 0,
    seekDeltaMs: Long = 0L,
    onProgressChanged: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
    onRemoteKey: (keyCode: Int, action: Int, repeatCount: Int) -> Boolean = { _, _, _ -> false },
    cropToFill: Boolean = false,
    overscanZoom: Float = 1f,
    autoCropLetterbox: Boolean = false,
    modifier: Modifier = Modifier,
    enter: EnterTransition = fadeIn(animationSpec = tween(800)),
    exit: ExitTransition = fadeOut(animationSpec = tween(500)),
    trailerPlayerPool: TrailerPlayerPool? = null,
    fullscreenDisplay: TrailerDisplayCapabilities? = null,
    isHls: Boolean = false,
    startPositionMs: Long = 0L
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activityLifecycleOwner = remember(context) { context as? androidx.lifecycle.LifecycleOwner ?: lifecycleOwner }
    val currentIsPlaying by rememberUpdatedState(isPlaying)
    val currentIsPaused by rememberUpdatedState(isPaused)
    val currentTrailerUrl by rememberUpdatedState(trailerUrl)
    val currentTrailerAudioUrl by rememberUpdatedState(trailerAudioUrl)
    val currentOnEnded by rememberUpdatedState(onEnded)
    val currentOnError by rememberUpdatedState(onError)
    val currentOnFirstFrameRendered by rememberUpdatedState(onFirstFrameRendered)
    val currentOnProgressChanged by rememberUpdatedState(onProgressChanged)
    val currentOnRemoteKey by rememberUpdatedState(onRemoteKey)
    val zoomScale = if (cropToFill) overscanZoom.coerceAtLeast(1f) else 1f
    var hasRenderedFirstFrame by remember(trailerUrl) { mutableStateOf(false) }
    val playerAlphaState = animateFloatAsState(
        targetValue = if (isPlaying && hasRenderedFirstFrame) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "trailerFirstFrameAlpha"
    )
    val playerViewRef = remember { mutableStateOf<PlayerView?>(null) }
    var letterboxZoom by remember(trailerUrl) { mutableFloatStateOf(1f) }
    val letterboxZoomState = animateFloatAsState(
        targetValue = if (autoCropLetterbox) letterboxZoom else 1f,
        animationSpec = tween(durationMillis = 400),
        label = "trailerLetterboxZoom"
    )

    // Resolve pool: explicit parameter > CompositionLocal
    val resolvedPool = trailerPlayerPool ?: LocalTrailerPlayerPool.current

    // Use the shared pool instance instead of creating a new ExoPlayer per focus.
    // The pool keeps one ExoPlayer alive across poster focus changes, eliminating
    // the expensive create/teardown cycle that was the app-launch bottleneck.
    val trailerPlayer = remember(trailerUrl, resolvedPool, fullscreenDisplay) {
        when {
            trailerUrl == null -> null
            fullscreenDisplay != null -> resolvedPool?.acquireFullscreen(fullscreenDisplay)
            else -> resolvedPool?.acquire()
        }
    }
    DisposableEffect(trailerPlayer, resolvedPool) {
        onDispose {
            if (trailerPlayer != null) {
                if (fullscreenDisplay != null) resolvedPool?.releaseFullscreen(trailerPlayer)
                else resolvedPool?.stop()
            }
        }
    }

    // Configure player settings when acquired
    LaunchedEffect(trailerPlayer, muted, cropToFill) {
        val player = trailerPlayer ?: return@LaunchedEffect
        player.volume = if (muted) 0f else 1f
        player.videoScalingMode = if (cropToFill) {
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        } else {
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        }
    }

    LaunchedEffect(isPlaying, trailerUrl, trailerAudioUrl, isHls, startPositionMs, trailerPlayer) {
        val player = trailerPlayer ?: return@LaunchedEffect
        player.volume = if (muted) 0f else 1f
        if (isPlaying && trailerUrl != null) {
            hasRenderedFirstFrame = false
            if (!activityLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
            player.setTrailerSource(context, trailerUrl, trailerAudioUrl, isHls, fullscreenDisplay != null)
            if (startPositionMs > 0L) player.seekTo(startPositionMs)
            player.prepare()
            player.playWhenReady = !currentIsPaused
        } else {
            hasRenderedFirstFrame = false
            player.playWhenReady = false
            // Defer heavy stop and clear until focus settling/collapse has finished
            delay(150)
            if (!isPlaying) {
                player.stop()
                player.clearMediaItems()
            }
        }
    }

    LaunchedEffect(trailerPlayer, cropToFill) {
        val player = trailerPlayer ?: return@LaunchedEffect
        player.videoScalingMode = if (cropToFill) {
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        } else {
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        }
    }

    LaunchedEffect(autoCropLetterbox, hasRenderedFirstFrame, trailerPlayer) {
        letterboxZoom = 1f
        if (!autoCropLetterbox || !hasRenderedFirstFrame) return@LaunchedEffect
        val player = trailerPlayer ?: return@LaunchedEffect
        val tracker = LetterboxTracker()
        val sampler = LetterboxSampler()
        try {
            while (true) {
                delay(LetterboxDetector.SAMPLE_INTERVAL_MS)
                if (LetterboxDetector.isSampleWindowOver(player.currentPosition, player.duration)) break
                if (!player.isPlaying) continue
                val textureView = playerViewRef.value?.videoSurfaceView as? TextureView ?: continue
                val bar = sampler.sample(textureView) ?: continue
                letterboxZoom = tracker.onSample(bar) ?: continue
                break
            }
        } finally {
            sampler.release()
        }
    }

    LaunchedEffect(isPaused, trailerPlayer) {
        val player = trailerPlayer ?: return@LaunchedEffect
        if (!isPlaying || !activityLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        player.playWhenReady = !isPaused
    }

    LaunchedEffect(seekRequestToken, seekDeltaMs, trailerPlayer) {
        val player = trailerPlayer ?: return@LaunchedEffect
        if (seekRequestToken <= 0) return@LaunchedEffect
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        val current = player.currentPosition
        val target = (current + seekDeltaMs).coerceIn(0L, duration.coerceAtLeast(0L))
        player.seekTo(target)
    }

    LaunchedEffect(trailerPlayer, isPlaying) {
        val player = trailerPlayer ?: return@LaunchedEffect
        while (isPlaying) {
            val position = player.currentPosition.coerceAtLeast(0L)
            val duration = player.duration.takeIf { it > 0 } ?: 0L
            currentOnProgressChanged(position, duration)
            delay(250)
        }
        currentOnProgressChanged(0L, 0L)
    }

    DisposableEffect(activityLifecycleOwner, trailerPlayer) {
        val player = trailerPlayer ?: return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED &&
                    currentIsPlaying &&
                    player.playWhenReady
                ) {
                    currentOnEnded()
                }
            }

            override fun onRenderedFirstFrame() {
                hasRenderedFirstFrame = true
                if (fullscreenDisplay != null) {
                    val video = player.videoFormat
                    val audio = player.audioFormat
                    Log.i("FullscreenTrailer", "First frame ${video?.width}x${video?.height}" +
                        " video=${video?.sampleMimeType} codecs=${video?.codecs} transfer=${video?.colorInfo?.colorTransfer}" +
                        " audio=${audio?.sampleMimeType} audioCodecs=${audio?.codecs}")
                }
                currentOnFirstFrameRendered()
            }

            // A trailer that fails to load or decode never renders a frame, so without this the
            // caller stays in its trailer layout over a transparent player.
            override fun onPlayerError(error: PlaybackException) {
                if (currentIsPlaying) {
                    if (fullscreenDisplay != null) Log.w("FullscreenTrailer", "Playback error ${error.errorCodeName}")
                    currentOnError()
                }
            }
        }
        var resumePending = !activityLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (resumePending && currentIsPlaying && !currentTrailerUrl.isNullOrBlank()) {
                        resumePending = false
                        if (player.currentMediaItem == null) {
                            player.setTrailerSource(context, currentTrailerUrl!!, currentTrailerAudioUrl, isHls, fullscreenDisplay != null)
                            player.prepare()
                        }
                        player.playWhenReady = !currentIsPaused
                    }
                }
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> {
                    resumePending = true
                    player.playWhenReady = false
                    player.pause()
                    player.stop()
                    player.clearMediaItems()
                }
                else -> Unit
            }
        }
        player.addListener(listener)
        activityLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            runCatching { activityLifecycleOwner.lifecycle.removeObserver(observer) }
            runCatching { player.removeListener(listener) }
        }
    }

    if (trailerPlayer != null) {
        val videoContent: @Composable () -> Unit = {
            AndroidView(
                factory = { ctx ->
                    val layout = if (fullscreenDisplay != null) {
                        R.layout.fullscreen_trailer_player_view
                    } else R.layout.trailer_player_view
                    (LayoutInflater.from(ctx).inflate(layout, null) as PlayerView).apply {
                        if (fullscreenDisplay != null) setEnableComposeSurfaceSyncWorkaround(true)
                        playerViewRef.value = this
                        player = trailerPlayer
                        isFocusable = false
                        isFocusableInTouchMode = false
                        setOnKeyListener { _, keyCode, event ->
                            currentOnRemoteKey(keyCode, event.action, event.repeatCount)
                        }
                        keepScreenOn = true
                        resizeMode = if (cropToFill) {
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        } else {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    }
                },
                update = { view ->
                    // Re-attach player in case it was reclaimed after yield
                    if (view.player !== trailerPlayer) {
                        view.player = trailerPlayer
                    }
                    view.resizeMode = if (cropToFill) {
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    } else {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                onRelease = { view ->
                    playerViewRef.value = null
                    view.player = null
                    view.keepScreenOn = false
                },
                modifier = if (fullscreenDisplay != null) modifier else modifier
                    .clipToBounds()
                    .graphicsLayer {
                        alpha = playerAlphaState.value
                        scaleX = zoomScale * letterboxZoomState.value
                        scaleY = zoomScale * letterboxZoomState.value
                    }
            )
        }
        if (fullscreenDisplay != null) {
            if (isPlaying) videoContent()
        } else {
            AnimatedVisibility(visible = isPlaying, enter = enter, exit = exit) { videoContent() }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun ExoPlayer.setTrailerSource(
    context: Context,
    videoUrl: String,
    audioUrl: String?,
    isHls: Boolean,
    fullscreen: Boolean
) {
    if (audioUrl.isNullOrBlank() && !fullscreen && !isHls) {
        setMediaItem(MediaItem.fromUri(videoUrl))
        return
    }
    val factory = if (isHls) {
        DefaultMediaSourceFactory(context)
    } else {
        DefaultMediaSourceFactory(YoutubeChunkedDataSourceFactory())
    }
    fun source(url: String) = factory.createMediaSource(
        MediaItem.Builder().setUri(url).apply { if (isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
    )
    val video = source(videoUrl)
    setMediaSource(if (audioUrl.isNullOrBlank()) video else MergingMediaSource(video, source(audioUrl)))
}
