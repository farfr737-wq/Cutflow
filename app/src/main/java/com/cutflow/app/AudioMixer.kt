package com.cutflow.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.min

/** Декодирует звук клипов/музыки/эффектов и микширует в один стерео-поток 44.1 кГц (16 бит). */
object AudioMixer {
    const val SR = 44100

    class Pcm(val data: ShortArray, val rate: Int, val ch: Int)

    fun decode(ctx: Context, uri: Uri, inUs: Long, outUs: Long): Pcm? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            val idx = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            ex.selectTrack(idx)
            val fmt = ex.getTrackFormat(idx)
            if (inUs > 0) ex.seekTo(inUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            dec.configure(fmt, null, null, 0)
            dec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var out = ShortArray(rate * ch * 10)
            var n = 0
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var done = false
            val deadline = System.nanoTime() + 120_000_000_000L
            while (!done && System.nanoTime() < deadline) {
                if (!inDone) {
                    val i = dec.dequeueInputBuffer(5000)
                    if (i >= 0) {
                        val b = dec.getInputBuffer(i)!!
                        val sz = ex.readSampleData(b, 0)
                        if (sz < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { dec.queueInputBuffer(i, 0, sz, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, 5000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = dec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    o >= 0 -> {
                        if (info.size > 0) {
                            val sb = dec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val total = info.size / 2
                            val frames = total / ch
                            for (k in 0 until frames) {
                                val tUs = info.presentationTimeUs + k * 1_000_000L / rate
                                if (tUs < inUs) continue
                                if (tUs >= outUs) { done = true; break }
                                if (n + ch > out.size) out = out.copyOf(out.size * 2)
                                for (c in 0 until ch) out[n++] = sb.get(k * ch + c)
                            }
                        }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true
                        dec.releaseOutputBuffer(o, false)
                    }
                }
            }
            runCatching { dec.stop() }; runCatching { dec.release() }
            return Pcm(out.copyOf(n), rate, ch)
        } catch (e: Throwable) {
            android.util.Log.w("CutFlow", "Аудио не декодировано: ${e.message}")
            return null
        } finally {
            runCatching { ex.release() }
        }
    }

    /** Линейный ресемплинг + громкость + fade-in/out, суммирование с насыщением. */
    private fun mixInto(
        dst: ShortArray, dstStartFrame: Int, pcm: Pcm, speed: Float, volume: Float,
        maxFrames: Int, fadeIn: Int, fadeOut: Int,
    ) {
        val step = pcm.rate.toDouble() * speed / SR
        val srcFrames = pcm.data.size / pcm.ch
        val dstFrames = dst.size / 2
        val count = min(maxFrames, ((srcFrames - 1) / step).toInt())
        val fi = maxOf(fadeIn, SR / 200)
        val fo = maxOf(fadeOut, SR / 200)
        for (i in 0 until count) {
            val df = dstStartFrame + i
            if (df < 0) continue
            if (df >= dstFrames) break
            val pos = i * step
            val i0 = pos.toInt()
            val fr = (pos - i0).toFloat()
            val i1 = min(i0 + 1, srcFrames - 1)
            var l = pcm.data[i0 * pcm.ch] * (1 - fr) + pcm.data[i1 * pcm.ch] * fr
            var r = if (pcm.ch > 1) pcm.data[i0 * pcm.ch + 1] * (1 - fr) + pcm.data[i1 * pcm.ch + 1] * fr else l
            var g = volume
            if (i < fi) g *= i.toFloat() / fi
            if (count - i < fo) g *= (count - i).toFloat() / fo
            l *= g; r *= g
            dst[df * 2] = (dst[df * 2] + l).toInt().coerceIn(-32768, 32767).toShort()
            dst[df * 2 + 1] = (dst[df * 2 + 1] + r).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** Весь проект -> interleaved stereo 44.1k. null, если звука нет. */
    fun mix(ctx: Context, p: Project): ShortArray? {
        val total = p.totalUs
        if (total <= 0) return null
        val frames = ceil(total / 1e6 * SR).toInt() + SR / 10
        val dst = ShortArray(frames * 2)
        val starts = p.starts()
        var any = false

        for ((i, c) in p.clips.withIndex()) {
            if (c.isImage || c.volume <= 0f) continue
            val pcm = decode(ctx, c.uri, c.inUs, c.outUs) ?: continue
            val startFrame = (starts[i] / 1e6 * SR).toInt()
            val maxFrames = (c.durationUs / 1e6 * SR).toInt()
            val fin = (p.overlap(i) / 1e6 * SR).toInt()
            val fout = (p.overlap(i + 1) / 1e6 * SR).toInt()
            mixInto(dst, startFrame, pcm, c.speed, c.volume, maxFrames, fin, fout)
            any = true
        }
        for (a in p.audios) {
            if (a.volume <= 0f) continue
            val pcm = when {
                a.sfx != null -> Pcm(Sfx.generate(a.sfx), SR, 1)
                a.uri != null -> decode(ctx, a.uri, a.srcInUs, a.srcInUs + a.durUs)
                else -> null
            } ?: continue
            mixInto(dst, (a.startUs / 1e6 * SR).toInt(), pcm, 1f, a.volume, (a.durUs / 1e6 * SR).toInt(), 0, 0)
            any = true
        }
        return if (any) dst else null
    }
}
