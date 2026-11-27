package com.cutflow.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Экспорт: GL-композитор -> Surface видеокодера (H.264/HEVC) + AAC -> MP4 в Movies/CutFlow. */
class Exporter(private val ctx: Context) {
    data class Settings(val shortSide: Int, val fps: Int, val hevc: Boolean, val quality: Int /*0..2*/)
    data class Result(val uri: Uri, val summary: String)
    class Cancelled : Exception()

    private class Pkt(val data: ByteArray, val pts: Long, val flags: Int)
    private class VCfg(val mime: String, val w: Int, val h: Int, val fps: Int, val bitrate: Int, val fmt: MediaFormat, val codec: String)

    fun export(p: Project, s: Settings, progress: (Float) -> Unit, isCancelled: () -> Boolean): Result {
        require(p.clips.isNotEmpty()) { "Проект пуст" }
        progress(0f)

        val pcm = AudioMixer.mix(ctx, p)
        val audio = pcm?.let { encodeAudio(it) }
        if (isCancelled()) throw Cancelled()

        val cfg = pickConfig(p.aspect, s)
        val resolver = ctx.contentResolver
        val name = "CutFlow_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CutFlow")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val outUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("Не удалось создать файл")
        val pfd = resolver.openFileDescriptor(outUri, "rw") ?: error("Не удалось открыть файл")

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var egl: EglCore? = null
        var comp: Compositor? = null
        val cache = SourceCache(ctx)
        var ok = false
        try {
            encoder = createEncoder(cfg)
            val inSurface = encoder.createInputSurface()
            encoder.start()
            egl = EglCore(recordable = true)
            val eglSurface = egl.windowSurface(inSurface)
            egl.makeCurrent(eglSurface)
            comp = Compositor(ctx)

            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var vTrack = -1
            var aTrack = -1
            var started = false
            var audioIdx = 0
            val info = MediaCodec.BufferInfo()

            fun flushAudio(upToUs: Long) {
                val pk = audio?.second ?: return
                if (!started || aTrack < 0) return
                while (audioIdx < pk.size && pk[audioIdx].pts <= upToUs) {
                    val a = pk[audioIdx++]
                    val bi = MediaCodec.BufferInfo().apply { set(0, a.data.size, a.pts, a.flags) }
                    muxer!!.writeSampleData(aTrack, ByteBuffer.wrap(a.data), bi)
                }
            }

            fun drain(endOfStream: Boolean) {
                val enc = encoder!!
                var guard = 0
                while (true) {
                    val i = enc.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                    when {
                        i == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) return
                            if (++guard > 1000) return
                        }
                        i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            vTrack = muxer!!.addTrack(enc.outputFormat)
                            if (audio != null) aTrack = muxer!!.addTrack(audio.first)
                            muxer!!.start()
                            started = true
                        }
                        i >= 0 -> {
                            val buf = enc.getOutputBuffer(i)!!
                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                            if (info.size > 0 && started) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                muxer!!.writeSampleData(vTrack, buf, info)
                                flushAudio(info.presentationTimeUs)
                            }
                            val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            enc.releaseOutputBuffer(i, false)
                            if (eos) return
                        }
                    }
                }
            }

            val total = p.totalUs
            val frames = ceil(total.toDouble() * cfg.fps / 1_000_000.0).toInt().coerceAtLeast(1)
            val starts = p.starts()
            for (f in 0 until frames) {
                if (isCancelled()) throw Cancelled()
                val t = f * 1_000_000L / cfg.fps
                comp.render(p, t, cfg.w, cfg.h, starts, cache)
                egl.setPresentationTime(eglSurface, t * 1000L)
                egl.swap(eglSurface)
                drain(false)
                if (f % 4 == 0) progress(0.02f + 0.96f * f / frames)
            }
            encoder.signalEndOfInputStream()
            drain(true)
            flushAudio(Long.MAX_VALUE)
            muxer.stop()
            ok = true
            progress(1f)
            val mbps = cfg.bitrate / 1_000_000f
            return Result(outUri, "${cfg.w}×${cfg.h}, ${cfg.fps} fps, ${if (cfg.mime.endsWith("hevc")) "HEVC" else "H.264"}, %.0f Мбит/с".format(mbps))
        } finally {
            runCatching { muxer?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { cache.releaseAll() }
            runCatching { comp?.release() }
            runCatching { egl?.release() }
            runCatching { pfd.close() }
            if (ok) {
                val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                resolver.update(outUri, v, null, null)
            } else {
                runCatching { resolver.delete(outUri, null, null) }
            }
        }
    }

    // ------------------------------------------------------------------ подбор параметров

    private fun bitrateFor(w: Int, h: Int, fps: Int, quality: Int, hevc: Boolean): Int {
        val bpp = floatArrayOf(0.09f, 0.16f, 0.24f)[quality.coerceIn(0, 2)]   // бит на пиксель при 30 fps
        var b = w.toDouble() * h * 30.0 * bpp * (fps / 30.0).pow(0.7)
        if (hevc) b *= 0.7
        return min(b, 250_000_000.0).toInt()
    }

    private fun dims(short: Int, aspect: Float): Pair<Int, Int> =
        if (aspect >= 1f) Pair(((short * aspect) / 2f).roundToInt() * 2, short)
        else Pair(short, ((short / aspect) / 2f).roundToInt() * 2)

    private fun pickConfig(aspect: Float, s: Settings): VCfg {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val shorts = (listOf(s.shortSide) + listOf(2160, 1440, 1080, 720, 540).filter { it < s.shortSide }).distinct()
        val fpsList = (listOf(s.fps) + listOf(60, 30, 24).filter { it < s.fps }).distinct()
        val mimes = if (s.hevc) listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC) else listOf(MediaFormat.MIMETYPE_VIDEO_AVC)
        for (sh in shorts) {
            val (w, h) = dims(sh, aspect)
            for (fps in fpsList) for (mime in mimes) for (k in floatArrayOf(1f, 0.7f, 0.5f)) {
                val br = (bitrateFor(w, h, fps, s.quality, mime == MediaFormat.MIMETYPE_VIDEO_HEVC) * k).toInt()
                val fmt = MediaFormat.createVideoFormat(mime, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, br)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                }
                val name = list.findEncoderForFormat(fmt)
                if (name != null) return VCfg(mime, w, h, fps, br, fmt, name)
            }
        }
        error("Это устройство не поддерживает кодирование видео с такими параметрами")
    }

    private fun createEncoder(cfg: VCfg): MediaCodec {
        fun make(withProfile: Boolean): MediaCodec {
            val enc = MediaCodec.createByCodecName(cfg.codec)
            try {
                if (withProfile) {
                    cfg.fmt.setInteger(
                        MediaFormat.KEY_PROFILE,
                        if (cfg.mime == MediaFormat.MIMETYPE_VIDEO_HEVC) MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                        else MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                    )
                } else cfg.fmt.removeKey(MediaFormat.KEY_PROFILE)
                enc.configure(cfg.fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return enc
            } catch (e: Exception) {
                enc.release()
                throw e
            }
        }
        return try { make(true) } catch (e: Exception) { make(false) }
    }

    // ------------------------------------------------------------------ AAC

    private fun encodeAudio(pcm: ShortArray): Pair<MediaFormat, List<Pkt>> {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AudioMixer.SR, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        val out = ArrayList<Pkt>()
        var outFmt: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        var pos = 0
        var inEos = false
        var done = false
        try {
            while (!done) {
                if (!inEos) {
                    val i = enc.dequeueInputBuffer(5000)
                    if (i >= 0) {
                        val b = enc.getInputBuffer(i)!!
                        b.clear()
                        var n = min(b.remaining() / 2, pcm.size - pos)
                        n -= n % 2
                        val ptsUs = (pos / 2) * 1_000_000L / AudioMixer.SR
                        if (n <= 0) {
                            enc.queueInputBuffer(i, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        } else {
                            b.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, pos, n)
                            enc.queueInputBuffer(i, 0, n * 2, ptsUs, 0)
                            pos += n
                        }
                    }
                }
                val o = enc.dequeueOutputBuffer(info, 5000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFmt = enc.outputFormat
                    o >= 0 -> {
                        val ob = enc.getOutputBuffer(o)!!
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                            val bytes = ByteArray(info.size)
                            ob.position(info.offset)
                            ob.get(bytes)
                            out += Pkt(bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                        }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true
                        enc.releaseOutputBuffer(o, false)
                    }
                }
            }
        } finally {
            runCatching { enc.stop() }
            runCatching { enc.release() }
        }
        return Pair(outFmt ?: fmt, out)
    }
}
