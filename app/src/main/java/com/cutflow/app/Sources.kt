package com.cutflow.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix as GMatrix
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max

/** Источник кадров: фото или видео, отдаёт GL-текстуру. Все вызовы — из GL-потока. */
interface FrameSource {
    val isOes: Boolean
    val texId: Int
    val codedW: Int
    val codedH: Int
    val rotation: Int          // градусы по часовой, которые нужно применить к кадру
    val texMatrix: FloatArray
    fun seek(srcUs: Long)
    fun release()
}

private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
private val FLIP_V = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)

private fun newTex2D(bmp: Bitmap?): Int {
    val t = IntArray(1)
    GLES20.glGenTextures(1, t, 0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    if (bmp != null) GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
    return t[0]
}

class ImageSource(ctx: Context, uri: Uri) : FrameSource {
    override val isOes = false
    override val rotation = 0
    override val texMatrix = FLIP_V
    override val texId: Int
    override val codedW: Int
    override val codedH: Int

    init {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Не удалось декодировать изображение")
        val deg = runCatching { cr.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0 }.getOrDefault(0)
        if (deg != 0) {
            val m = GMatrix().apply { postRotate(deg.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        codedW = bmp.width
        codedH = bmp.height
        texId = newTex2D(bmp)
        bmp.recycle()
    }

    override fun seek(srcUs: Long) {}
    override fun release() { GLES20.glDeleteTextures(1, intArrayOf(texId), 0) }
}

/** Заглушка, если файл не открылся: чёрный кадр. */
class BlankSource : FrameSource {
    override val isOes = false
    override val rotation = 0
    override val texMatrix = IDENTITY
    override val codedW = 16
    override val codedH = 9
    override val texId: Int = run {
        val b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        b.eraseColor(0xFF000000.toInt())
        newTex2D(b).also { b.recycle() }
    }
    override fun seek(srcUs: Long) {}
    override fun release() { GLES20.glDeleteTextures(1, intArrayOf(texId), 0) }
}

/**
 * Видео: MediaCodec -> SurfaceTexture (внешняя OES-текстура).
 * seek() выдаёт кадр, актуальный на момент srcUs; идём вперёд без перемотки, если цель близко.
 */
class VideoSource(ctx: Context, uri: Uri) : FrameSource {
    override val isOes = true
    override val texMatrix = FloatArray(16).also { System.arraycopy(IDENTITY, 0, it, 0, 16) }
    override val texId: Int
    override val codedW: Int
    override val codedH: Int
    override val rotation: Int

    private val extractor = MediaExtractor()
    private val decoder: MediaCodec
    private val st: SurfaceTexture
    private val surface: Surface
    private val thread = HandlerThread("cutflow-vsrc").also { it.start() }
    private val frameLock = Object()
    private var frameAvail = false
    private val info = MediaCodec.BufferInfo()

    private var curPts = Long.MIN_VALUE
    private var inputDone = false
    private var outEos = false
    private val frameDur: Long

    init {
        extractor.setDataSource(ctx, uri, null)
        val idx = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: error("В файле нет видеодорожки")
        extractor.selectTrack(idx)
        val fmt = extractor.getTrackFormat(idx)
        codedW = fmt.getInteger(MediaFormat.KEY_WIDTH)
        codedH = fmt.getInteger(MediaFormat.KEY_HEIGHT)
        rotation = if (fmt.containsKey(MediaFormat.KEY_ROTATION)) fmt.getInteger(MediaFormat.KEY_ROTATION) else 0
        val fps = runCatching { fmt.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrNull()
            ?: runCatching { fmt.getFloat(MediaFormat.KEY_FRAME_RATE).toInt() }.getOrNull() ?: 30
        frameDur = 1_000_000L / fps.coerceIn(5, 240)

        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        texId = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        st = SurfaceTexture(texId)
        st.setOnFrameAvailableListener({
            synchronized(frameLock) { frameAvail = true; frameLock.notifyAll() }
        }, Handler(thread.looper))
        surface = Surface(st)

        decoder = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(fmt, surface, null, 0)
        decoder.start()
    }

    override fun seek(srcUs: Long) {
        val target = srcUs.coerceAtLeast(0L)
        if (curPts != Long.MIN_VALUE) {
            if (target >= curPts - frameDur && target < curPts + frameDur) return   // текущий кадр ещё актуален
            if (outEos && target >= curPts) return                                 // конец файла — держим последний кадр
            if (target > curPts && target - curPts < 2_000_000L) { decodeUntil(target); return }
        }
        extractor.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        decoder.flush()
        inputDone = false
        outEos = false
        curPts = Long.MIN_VALUE
        decodeUntil(target)
    }

    private fun decodeUntil(target: Long) {
        val deadline = System.nanoTime() + 6_000_000_000L
        while (System.nanoTime() < deadline) {
            if (!inputDone) {
                val i = decoder.dequeueInputBuffer(0)
                if (i >= 0) {
                    val buf = decoder.getInputBuffer(i)!!
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) {
                        decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val o = decoder.dequeueOutputBuffer(info, 10_000)
            if (o >= 0) {
                val pts = info.presentationTimeUs
                val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                val hasFrame = info.size > 0
                // Кадр точно не нужен, если следующий (pts+frameDur) всё ещё не позже цели
                val skip = hasFrame && !eos && pts + frameDur <= target
                if (hasFrame && !skip) {
                    synchronized(frameLock) { frameAvail = false }
                    decoder.releaseOutputBuffer(o, true)
                    var ok = false
                    synchronized(frameLock) {
                        val end = System.currentTimeMillis() + 2000
                        while (!frameAvail) {
                            val rem = end - System.currentTimeMillis()
                            if (rem <= 0) break
                            frameLock.wait(rem)
                        }
                        ok = frameAvail
                        frameAvail = false
                    }
                    if (ok) {
                        st.updateTexImage()
                        st.getTransformMatrix(texMatrix)
                        curPts = pts
                    }
                    if (eos) outEos = true
                    return
                } else {
                    decoder.releaseOutputBuffer(o, false)
                    if (eos) { outEos = true; return }
                }
            }
        }
    }

    override fun release() {
        runCatching { decoder.stop() }
        runCatching { decoder.release() }
        runCatching { extractor.release() }
        runCatching { surface.release() }
        runCatching { st.release() }
        thread.quitSafely()
        GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
    }
}

/** Кэш источников (LRU): не держим больше нескольких открытых декодеров. */
class SourceCache(private val ctx: Context, private val maxOpen: Int = 3) {
    private val map = object : LinkedHashMap<Long, FrameSource>(8, 0.75f, true) {}

    fun get(clip: Clip): FrameSource {
        map[clip.id]?.let { return it }
        val s: FrameSource = try {
            if (clip.isImage) ImageSource(ctx, clip.uri) else VideoSource(ctx, clip.uri)
        } catch (e: Throwable) {
            android.util.Log.e("CutFlow", "Не открылся ${clip.uri}", e)
            BlankSource()
        }
        map[clip.id] = s
        while (map.size > maxOpen) {
            val eldest = map.entries.first()
            eldest.value.release()
            map.remove(eldest.key)
        }
        return s
    }

    fun releaseAll() {
        map.values.forEach { runCatching { it.release() } }
        map.clear()
    }
}
