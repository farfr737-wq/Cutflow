package com.cutflow.app

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/** Встроенные звуковые эффекты — синтезируются в коде (никаких файлов, никаких лицензий). */
object Sfx {
    const val SR = 44100
    private val PIf = Math.PI.toFloat()

    data class Info(val id: String, val name: String, val cat: String)

    val all = listOf(
        Info("whoosh", "Вжух", "Движение"), Info("swish", "Свист", "Движение"),
        Info("riser", "Нарастание", "Движение"), Info("drop", "Падение", "Движение"),
        Info("pop", "Поп", "Интерфейс"), Info("click", "Клик", "Интерфейс"),
        Info("beep", "Бип", "Интерфейс"), Info("notify", "Уведомление", "Интерфейс"),
        Info("success", "Успех", "Интерфейс"), Info("error", "Ошибка", "Интерфейс"),
        Info("coin", "Монетка", "Интерфейс"),
        Info("ding", "Динь", "Музыка"), Info("bell", "Колокол", "Музыка"),
        Info("kick", "Бас-бочка", "Ударные"), Info("snare", "Малый барабан", "Ударные"),
        Info("hihat", "Хай-хэт", "Ударные"), Info("clap", "Хлопок", "Ударные"),
        Info("boom", "Бум", "Эффекты"), Info("hit", "Удар", "Эффекты"),
        Info("explosion", "Взрыв", "Эффекты"), Info("laser", "Лазер", "Эффекты"),
        Info("glitch", "Глитч", "Эффекты"), Info("shutter", "Затвор камеры", "Эффекты"),
        Info("typewriter", "Печатная машинка", "Эффекты"), Info("heartbeat", "Сердцебиение", "Эффекты"),
    )

    private val cache = HashMap<String, ShortArray>()

    /** Моно, 44100 Гц. */
    @Synchronized
    fun generate(id: String): ShortArray = cache.getOrPut(id) { synth(id) }

    fun durationUs(id: String): Long = generate(id).size * 1_000_000L / SR

    private fun buf(sec: Float, f: (Float) -> Float): ShortArray {
        val n = (sec * SR).toInt()
        return ShortArray(n) { i -> (f(i / SR.toFloat()).coerceIn(-1f, 1f) * 30000f).toInt().toShort() }
    }

    private fun sine(f: Float, t: Float) = sin(2f * PIf * f * t)
    private fun sweep(f0: Float, f1: Float, dur: Float, t: Float) =
        sin(2f * PIf * (f0 * t + (f1 - f0) * t * t / (2f * dur)))

    private fun noise(seed: Long): () -> Float {
        val r = java.util.Random(seed)
        return { r.nextFloat() * 2f - 1f }
    }

    private fun synth(id: String): ShortArray = when (id) {
        "whoosh" -> { val nz = noise(1); var y = 0f
            buf(0.8f) { t -> val e = sin(PIf * t / 0.8f); val a = 0.03f + 0.5f * e * e; y += a * (nz() - y); y * e * e * 3f } }
        "swish" -> { val nz = noise(2); var y = 0f
            buf(0.35f) { t -> val e = sin(PIf * t / 0.35f); val x = nz(); y += 0.3f * (x - y); (x - y) * e * e * 0.9f } }
        "riser" -> { val nz = noise(3); var y = 0f
            buf(2f) { t -> val e = t / 2f; y += (0.02f + 0.3f * e) * (nz() - y); (y * 2f + sweep(200f, 1800f, 2f, t) * 0.35f) * e * e } }
        "drop" -> buf(1f) { t -> sweep(1200f, 80f, 1f, t) * exp(-t * 2.5f) }
        "pop" -> buf(0.2f) { t -> sweep(700f, 180f, 0.15f, t) * exp(-t * 30f) }
        "click" -> { val nz = noise(4); buf(0.03f) { t -> (nz() * 0.6f + sine(2200f, t) * 0.4f) * exp(-t * 300f) } }
        "beep" -> buf(0.2f) { t -> sine(880f, t) * (if (t < 0.18f) 1f else 0f) * 0.6f }
        "notify" -> buf(0.5f) { t ->
            if (t < 0.13f) sine(880f, t) * exp(-t * 8f) else sine(1320f, t) * exp(-(t - 0.13f) * 7f) }
        "success" -> buf(0.6f) { t ->
            val k = min((t / 0.1f).toInt(), 3); val f = floatArrayOf(523f, 659f, 784f, 1047f)[k]
            sine(f, t) * exp(-(t - k * 0.1f) * 6f) * 0.7f }
        "error" -> buf(0.4f) { t ->
            val on = (t < 0.15f) || (t in 0.2f..0.35f)
            if (on) (if (sine(150f, t) > 0f) 0.4f else -0.4f) else 0f }
        "coin" -> buf(0.5f) { t ->
            if (t < 0.08f) sine(988f, t) * 0.7f else sine(1319f, t) * exp(-(t - 0.08f) * 7f) * 0.7f }
        "ding" -> buf(1.5f) { t -> (sine(1318f, t) + 0.3f * sine(2636f, t)) * exp(-t * 3f) * 0.7f }
        "bell" -> buf(2.5f) { t ->
            (sine(440f, t) * exp(-t * 1.6f) + 0.6f * sine(1214f, t) * exp(-t * 2.2f) +
                0.4f * sine(2376f, t) * exp(-t * 3f) + 0.25f * sine(3916f, t) * exp(-t * 4f)) * 0.6f }
        "kick" -> buf(0.4f) { t -> sweep(160f, 45f, 0.25f, t) * exp(-t * 12f) }
        "snare" -> { val nz = noise(5); buf(0.3f) { t -> nz() * exp(-t * 20f) * 0.7f + sine(190f, t) * exp(-t * 25f) * 0.5f } }
        "hihat" -> { val nz = noise(6); var y = 0f
            buf(0.12f) { t -> val x = nz(); y += 0.2f * (x - y); (x - y) * exp(-t * 60f) } }
        "clap" -> { val nz = noise(7)
            buf(0.35f) { t ->
                var e = exp(-t * 15f) * 0.3f
                for (k in floatArrayOf(0f, 0.012f, 0.024f)) if (t >= k) e += exp(-(t - k) * 80f)
                nz() * e * 0.6f } }
        "boom" -> { val nz = noise(8); var y = 0f
            buf(1.4f) { t -> y += 0.02f * (nz() - y); sweep(90f, 30f, 1.2f, t) * exp(-t * 3f) + y * exp(-t * 5f) * 4f } }
        "hit" -> { val nz = noise(9); var y = 0f
            buf(0.4f) { t -> y += 0.1f * (nz() - y); y * exp(-t * 30f) * 3f + sweep(120f, 40f, 0.3f, t) * exp(-t * 14f) } }
        "explosion" -> { val nz = noise(10); var y = 0f
            buf(2f) { t -> y += (0.25f * exp(-t * 2f) + 0.01f) * (nz() - y)
                y * exp(-t * 2.2f) * 3.5f + sweep(70f, 25f, 1.5f, t) * exp(-t * 3f) * 0.8f } }
        "laser" -> buf(0.4f) { t -> sweep(2200f, 150f, 0.35f, t) * exp(-t * 5f) * 0.8f }
        "glitch" -> { val nz = noise(11); var h = 0f; var c = 0
            buf(0.4f) { t -> if (c++ % 24 == 0) h = nz(); if (((t * 25f).toInt() and 1) == 0) h * 0.6f else 0f } }
        "shutter" -> { val nz = noise(12)
            buf(0.2f) { t -> nz() * (exp(-t * 80f) + (if (t > 0.06f) exp(-(t - 0.06f) * 80f) else 0f)) * 0.8f } }
        "typewriter" -> { val nz = noise(13)
            buf(0.6f) { t ->
                var e = 0f
                for (k in floatArrayOf(0f, 0.12f, 0.22f, 0.37f, 0.47f)) if (t >= k) e += exp(-(t - k) * 200f)
                nz() * e * 0.8f } }
        "heartbeat" -> buf(1.4f) { t ->
            var e = 0f
            for (k in floatArrayOf(0f, 0.22f, 0.9f, 1.12f)) if (t >= k && t - k < 0.3f)
                e += sweep(70f, 45f, 0.2f, t - k) * exp(-(t - k) * 18f)
            e }
        else -> ShortArray(0)
    }
}
