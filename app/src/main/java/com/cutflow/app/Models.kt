package com.cutflow.app

import android.net.Uri

/** Клип на основной дорожке (видео или фото). Всё immutable — правки через copy(). */
data class Clip(
    val id: Long,
    val uri: Uri,
    val isImage: Boolean,
    val sourceUs: Long,
    val inUs: Long = 0L,
    val outUs: Long = sourceUs,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val filter: String = "none",
    val brightness: Float = 0f,   // -0.5..0.5
    val contrast: Float = 1f,     // 0.5..1.5
    val saturation: Float = 1f,   // 0..2
    val zoom: Float = 1f,
    val rotation: Float = 0f,     // градусы, по часовой
    val mirror: Boolean = false,
    val fill: Boolean = false,    // true = заполнить кадр (обрезка), false = вписать
    val kenBurns: Boolean = false,
    val transition: String = "none", // переход ВХОДЯЩИЙ в этот клип (из предыдущего)
    val transitionUs: Long = 700_000L,
) {
    val durationUs: Long get() = ((outUs - inUs) / speed).toLong().coerceAtLeast(100_000L)
}

data class TextItem(
    val id: Long,
    val text: String,
    val font: String = "sans-serif",
    val sizeFrac: Float = 0.08f,          // доля высоты кадра
    val color: Int = 0xFFFFFFFF.toInt(),
    val strokeColor: Int = 0xFF000000.toInt(),
    val stroke: Boolean = true,
    val bold: Boolean = false,
    val shadow: Boolean = true,
    val bgColor: Int = 0,
    val startUs: Long = 0L,
    val durUs: Long = 3_000_000L,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val anim: String = "fade",
)

/** Звуковая дорожка: либо встроенный эффект (sfx), либо файл (музыка/свой звук). */
data class AudioItem(
    val id: Long,
    val name: String,
    val uri: Uri?,
    val sfx: String?,
    val startUs: Long,
    val srcInUs: Long = 0L,
    val durUs: Long,
    val volume: Float = 1f,
    val music: Boolean,
)

data class Plan(val to: Int, val from: Int, val progress: Float, val transition: String)

data class Project(
    val clips: List<Clip> = emptyList(),
    val texts: List<TextItem> = emptyList(),
    val audios: List<AudioItem> = emptyList(),
    val aspect: Float = 16f / 9f,
) {
    /** Длительность перекрытия клипа i с предыдущим (переход). */
    fun overlap(i: Int): Long {
        if (i <= 0 || i >= clips.size) return 0L
        val c = clips[i]
        if (c.transition == "none") return 0L
        return minOf(c.transitionUs, clips[i - 1].durationUs / 2, c.durationUs / 2)
    }

    fun starts(): LongArray {
        val r = LongArray(clips.size)
        var end = 0L
        for (i in clips.indices) {
            val s = if (i == 0) 0L else end - overlap(i)
            r[i] = s
            end = s + clips[i].durationUs
        }
        return r
    }

    val totalUs: Long
        get() {
            if (clips.isEmpty()) return 0L
            return starts().last() + clips.last().durationUs
        }

    fun planAt(t: Long, starts: LongArray): Plan? {
        if (clips.isEmpty()) return null
        val tt = t.coerceIn(0L, (starts.last() + clips.last().durationUs - 1).coerceAtLeast(0L))
        var cur = 0
        for (i in clips.indices) if (starts[i] <= tt) cur = i
        if (cur > 0) {
            val ov = overlap(cur)
            if (ov > 0 && tt < starts[cur] + ov) {
                return Plan(cur, cur - 1, (tt - starts[cur]).toFloat() / ov, clips[cur].transition)
            }
        }
        return Plan(cur, -1, 0f, "none")
    }
}

object Filters {
    val list = listOf(
        "none" to "Без фильтра", "bw" to "Ч/Б", "sepia" to "Сепия", "vivid" to "Яркий",
        "warm" to "Тёплый", "cool" to "Холодный", "vignette" to "Виньетка", "invert" to "Негатив",
        "fade" to "Выцветший", "cinema" to "Кино", "noir" to "Нуар", "retro" to "Ретро", "dream" to "Мечта",
    )
    fun index(id: String): Int = list.indexOfFirst { it.first == id }.coerceAtLeast(0)
}

val TEXT_ANIMS = listOf(
    "none" to "Без анимации", "fade" to "Появление", "pop" to "Поп", "slide_up" to "Снизу",
    "slide_left" to "Справа", "reveal" to "Печать", "bounce" to "Прыжок",
)
