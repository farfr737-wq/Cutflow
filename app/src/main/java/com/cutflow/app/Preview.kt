package com.cutflow.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.opengl.EGLSurface
import android.opengl.GLES20
import kotlinx.coroutines.asCoroutineDispatcher
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Предпросмотр: тот же GL-композитор, что и при экспорте, но в pbuffer низкого разрешения.
 * Все вызовы render() — только через dispatcher (один GL-поток).
 */
class PreviewEngine(private val ctx: Context) {
    private val exec = Executors.newSingleThreadExecutor { Thread(it, "cutflow-gl") }
    val dispatcher = exec.asCoroutineDispatcher()

    private var egl: EglCore? = null
    private var surface: EGLSurface? = null
    private var comp: Compositor? = null
    private var cache: SourceCache? = null
    private var curW = 0
    private var curH = 0

    private fun ensure(w: Int, h: Int) {
        if (egl == null) {
            egl = EglCore(recordable = false)
        }
        if (surface == null || w != curW || h != curH) {
            surface?.let { egl!!.destroySurface(it) }
            surface = egl!!.pbuffer(w, h)
            curW = w; curH = h
        }
        egl!!.makeCurrent(surface!!)
        if (comp == null) comp = Compositor(ctx)
        if (cache == null) cache = SourceCache(ctx)
    }

    fun render(p: Project, tUs: Long, height: Int): Bitmap {
        val h = height
        val w = max(2, ((h * p.aspect) / 2f).toInt() * 2)
        ensure(w, h)
        comp!!.render(p, tUs, w, h, p.starts(), cache!!)
        val bb = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(bb)
        val m = Matrix().apply { postScale(1f, -1f) }
        return Bitmap.createBitmap(bmp, 0, 0, w, h, m, false)
    }

    fun shutdown() {
        exec.execute {
            runCatching { cache?.releaseAll() }
            runCatching { comp?.release() }
            runCatching { egl?.release() }
        }
        exec.shutdown()
    }
}

/** Звук предпросмотра: стримим готовый микс в AudioTrack; позиция AudioTrack — главные часы. */
class AudioPreview {
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var baseUs = 0L
    private var auditionTrack: AudioTrack? = null

    fun start(pcm: ShortArray, startUs: Long) {
        stop()
        val startFrame = (startUs / 1e6 * AudioMixer.SR).toInt().coerceIn(0, pcm.size / 2)
        val minBuf = AudioTrack.getMinBufferSize(AudioMixer.SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(AudioMixer.SR).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(max(minBuf, AudioMixer.SR * 4 / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        baseUs = startUs
        running = true
        t.play()
        thread = Thread {
            var pos = startFrame * 2
            val chunk = 8192
            while (running && pos < pcm.size) {
                val n = minOf(chunk, pcm.size - pos)
                val w = t.write(pcm, pos, n)
                if (w < 0) break
                pos += w
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun positionUs(): Long {
        val t = track ?: return baseUs
        return baseUs + t.playbackHeadPosition * 1_000_000L / AudioMixer.SR
    }

    fun stop() {
        running = false
        thread?.let { runCatching { it.join(300) } }
        thread = null
        track?.let { runCatching { it.pause(); it.flush(); it.release() } }
        track = null
    }

    /** Прослушать звуковой эффект (моно, 44.1k). */
    fun audition(samples: ShortArray) {
        auditionTrack?.let { runCatching { it.stop(); it.release() } }
        if (samples.isEmpty()) return
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(AudioMixer.SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(samples, 0, samples.size)
        t.play()
        auditionTrack = t
    }
}
