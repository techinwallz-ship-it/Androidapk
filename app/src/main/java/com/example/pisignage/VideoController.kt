package com.example.pisignage

import android.app.Activity
import android.net.Uri
import android.util.Log
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer

/**
 * Plays playlist videos with native ExoPlayer on a TextureView layered on top of the WebView.
 *
 * Why this exists: WebView video playback exhausts the GPU buffer queue on weak TV-box GPUs
 * (image_reader_gl_owner: no buffers in the reader queue → renderer crash → white screen). ExoPlayer
 * uses the standard native MediaCodec→Surface path, which releases buffers correctly, so full-quality
 * video plays continuously for hours — just like images do in the WebView.
 *
 * The TextureView shows ONLY while a video plays (revealed on the first rendered frame, hidden on
 * stop), so images/UI/ticker keep rendering in the WebView underneath the rest of the time.
 *
 * All public methods MUST be called on the UI thread (the AndroidVideo bridge marshals onto it).
 */
class VideoController(
    private val activity: Activity,
    private val container: FrameLayout,
    private val onEnded: () -> Unit,
    private val onError: () -> Unit,
) {
    private var player: ExoPlayer? = null
    private var textureView: TextureView? = null
    private var preparedUri: String? = null
    private var playingUri: String? = null
    private var errorRetries = 0
    private var clipCount = 0
    private val recreateEvery = 10 // recycle the player every N clips to reclaim decoder memory

    private fun ensure() {
        if (player != null) return
        val tv = TextureView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // Keep it VISIBLE but transparent (alpha 0) when idle. GONE/INVISIBLE prevent the
            // TextureView's SurfaceTexture from being created/updated, so ExoPlayer renders nothing
            // → black video. A VISIBLE TextureView at alpha 0 stays drawn (surface alive, video
            // renders, onRenderedFirstFrame fires) yet is fully transparent so the WebView shows
            // through for images. We flip alpha to 1 on the first rendered frame.
            alpha = 0f
        }
        // Added last → sits on TOP of the WebView in the FrameLayout.
        container.addView(tv)

        // Small buffers: local files don't need much, and this cuts native memory on RAM-tight
        // 2 GB boxes that were hitting LOW_MEMORY / heavy zram swap.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(5_000, 15_000, 500, 2_000)
            .build()
        val p = ExoPlayer.Builder(activity).setLoadControl(loadControl).build()
        p.setVideoTextureView(tv)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    Log.d("VIDEO", "native clip ended")
                    onEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("VIDEO", "native player error (${error.errorCodeName})", error)
                val src = playingUri
                // Self-heal a transient decoder error by re-preparing the same clip once.
                if (src != null && errorRetries < 1) {
                    errorRetries++
                    try {
                        p.setMediaItem(MediaItem.fromUri(Uri.parse(src)))
                        p.prepare()
                        p.seekTo(0)
                        p.playWhenReady = true
                        Log.w("VIDEO", "retrying $src after error")
                        return
                    } catch (e: Exception) {
                        Log.e("VIDEO", "retry failed", e)
                    }
                }
                errorRetries = 0
                onError() // give up → SPA advances/recovers to the next item
            }

            override fun onRenderedFirstFrame() {
                // Reveal only when the first frame is ready → no black gap on image→video handoff.
                errorRetries = 0
                Log.d("VIDEO", "first frame rendered → showing video")
                tv.bringToFront() // ensure it sits above the WebView
                tv.alpha = 1f
            }
        })
        player = p
        textureView = tv
    }

    /** Show + play a video at [src] (a file:// URI), full quality. */
    fun play(src: String) {
        ensure()
        // Ignore redundant re-play of the clip already playing (storm guard). Check before counting.
        if (player?.let { it.isPlaying && src == playingUri } == true) return

        // Periodically recycle the whole player to reclaim accumulated native decoder memory. Each
        // switch to a DIFFERENT clip reconfigures the HW decoder and leaks a little; with several
        // videos rotating this creeps up over hours and starves the 2 GB box. A full release+recreate
        // every N clips bounds that growth.
        if (++clipCount >= recreateEvery) {
            clipCount = 0
            Log.d("VIDEO", "recycling ExoPlayer to free decoder memory")
            release()
            ensure()
        }

        val p = player ?: return
        try {
            if (preparedUri != src) {
                p.setMediaItem(MediaItem.fromUri(Uri.parse(src)))
                p.prepare()
                preparedUri = src
            }
            p.repeatMode = Player.REPEAT_MODE_OFF
            p.seekTo(0)
            p.playWhenReady = true
            playingUri = src
            Log.d("VIDEO", "play $src")
        } catch (e: Exception) {
            Log.e("VIDEO", "play failed", e)
            onError()
        }
    }

    /** Pre-load [src] so the next play() starts instantly (no buffering on local files). */
    fun prepare(src: String) {
        val p = player ?: return
        // NEVER disturb a clip that's currently playing — with a single ExoPlayer, calling
        // setMediaItem() here would interrupt/thrash it. Only pre-load during the image gap.
        if (p.isPlaying) return
        if (preparedUri == src) return
        try {
            p.setMediaItem(MediaItem.fromUri(Uri.parse(src)))
            p.prepare()
            p.playWhenReady = false
            preparedUri = src
            Log.d("VIDEO", "prepared $src")
        } catch (e: Exception) {
            Log.w("VIDEO", "prepare failed", e)
        }
    }

    /** Hide the native surface (reveal the WebView) — called for image/audio items. */
    fun stop() {
        val p = player
        if (p != null) {
            try {
                p.playWhenReady = false
                p.stop()
            } catch (e: Exception) {
                Log.w("VIDEO", "stop failed", e)
            }
        }
        preparedUri = null
        playingUri = null
        // Transparent (not GONE/INVISIBLE) so the surface stays alive for the next video; the
        // WebView shows through for the current image.
        textureView?.alpha = 0f
    }

    /** Release everything (call from onDestroy). */
    fun release() {
        try { player?.release() } catch (e: Exception) {}
        player = null
        textureView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        textureView = null
        preparedUri = null
        playingUri = null
    }
}
