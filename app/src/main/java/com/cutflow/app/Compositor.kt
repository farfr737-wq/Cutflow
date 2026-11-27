package com.cutflow.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class Prog(vs: String, fs: String) {
    val id: Int
    private val locs = HashMap<String, Int>()

    init {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, v)
        GLES20.glAttachShader(id, f)
        GLES20.glLinkProgram(id)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(id)
            GLES20.glDeleteProgram(id)
            throw RuntimeException("Link: $log")
        }
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("Shader: $log")
        }
        return s
    }

    fun u(name: String): Int = locs.getOrPut("u$name") { GLES20.glGetUniformLocation(id, name) }
    fun a(name: String): Int = locs.getOrPut("a$name") { GLES20.glGetAttribLocation(id, name) }
    fun delete() { GLES20.glDeleteProgram(id) }
}

private class TrProg(val prog: Prog, val defaults: List<Triple<String, String, FloatArray>>)
private class TextTex(val tex: Int, val w: Int, val h: Int)

/**
 * Собирает кадр проекта в момент t: слои клипов (фильтры/трансформации) -> переход -> текст.
 * Рисует в текущий framebuffer 0 (экран/pbuffer/вход кодека). Только GL-поток.
 */
class Compositor(private val ctx: Context) {
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
    }

    private val layerOes by lazy { Prog(LAYER_VS, layerFs(true)) }
    private val layer2d by lazy { Prog(LAYER_VS, layerFs(false)) }
    private val overlay by lazy { Prog(LAYER_VS, OVERLAY_FS) }
    private val trCache = HashMap<String, TrProg?>()
    private val textCache = HashMap<String, TextTex>()

    private val fbo = IntArray(2)
    private val fboTex = IntArray(2)
    private var fw = 0
    private var fh = 0

    private fun ensureFbos(w: Int, h: Int) {
        if (fw == w && fh == h && fbo[0] != 0) return
        if (fbo[0] != 0) { GLES20.glDeleteFramebuffers(2, fbo, 0); GLES20.glDeleteTextures(2, fboTex, 0) }
        GLES20.glGenFramebuffers(2, fbo, 0)
        GLES20.glGenTextures(2, fboTex, 0)
        for (i in 0..1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[i])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex[i], 0)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        fw = w; fh = h
    }

    private fun drawQuad(p: Prog) {
        val ap = p.a("aPos")
        val au = p.a("aUv")
        quad.position(0)
        GLES20.glEnableVertexAttribArray(ap)
        GLES20.glVertexAttribPointer(ap, 2, GLES20.GL_FLOAT, false, 16, quad)
        quad.position(2)
        GLES20.glEnableVertexAttribArray(au)
        GLES20.glVertexAttribPointer(au, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    // ------------------------------------------------------------------ кадр

    fun render(p: Project, tUs: Long, w: Int, h: Int, starts: LongArray, srcs: SourceCache) {
        ensureFbos(w, h)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val plan = p.planAt(tUs, starts)
        if (plan != null) {
            val prog = if (plan.from >= 0) trProg(plan.transition) else null
            if (plan.from < 0 || prog == null) {
                // без перехода (или переход не скомпилировался -> простое наложение через fade)
                if (plan.from >= 0 && prog == null) {
                    drawLayer(p, plan.from, tUs, starts, srcs, fbo[0], w, h)
                    drawLayer(p, plan.to, tUs, starts, srcs, fbo[1], w, h)
                    transitionPass(trProg("fade")!!, plan.progress, w, h)
                } else {
                    drawLayer(p, plan.to, tUs, starts, srcs, 0, w, h)
                }
            } else {
                drawLayer(p, plan.from, tUs, starts, srcs, fbo[0], w, h)
                drawLayer(p, plan.to, tUs, starts, srcs, fbo[1], w, h)
                transitionPass(prog, plan.progress, w, h)
            }
        }
        drawTexts(p, tUs, w, h)
    }

    private fun drawLayer(p: Project, idx: Int, t: Long, starts: LongArray, srcs: SourceCache, target: Int, w: Int, h: Int) {
        val clip = p.clips[idx]
        val src = srcs.get(clip)
        val local = (t - starts[idx]).coerceAtLeast(0L)
        val srcUs = (clip.inUs + (local * clip.speed).toLong()).coerceIn(clip.inUs, max(clip.inUs, clip.outUs - 1))
        src.seek(srcUs)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val pr = if (src.isOes) layerOes else layer2d
        GLES20.glUseProgram(pr.id)
        val frac = (local.toFloat() / clip.durationUs).coerceIn(0f, 1f)
        GLES20.glUniformMatrix4fv(pr.u("uMvp"), 1, false, layerMvp(clip, src, w.toFloat() / h, frac), 0)
        GLES20.glUniformMatrix4fv(pr.u("uTexM"), 1, false, src.texMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(if (src.isOes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D, src.texId)
        GLES20.glUniform1i(pr.u("uTex"), 0)
        GLES20.glUniform1i(pr.u("uFilter"), Filters.index(clip.filter))
        GLES20.glUniform1f(pr.u("uBri"), clip.brightness)
        GLES20.glUniform1f(pr.u("uCon"), clip.contrast)
        GLES20.glUniform1f(pr.u("uSat"), clip.saturation)
        drawQuad(pr)
    }

    private fun layerMvp(c: Clip, src: FrameSource, outAspect: Float, frac: Float): FloatArray {
        val m = FloatArray(16)
        Matrix.setIdentityM(m, 0)
        val rot = src.rotation
        val coded = src.codedW.toFloat() / src.codedH
        val disp = if (rot == 90 || rot == 270) 1f / coded else coded
        val f = if (c.fill) max(1f, outAspect / disp) else min(1f, outAspect / disp)
        var z = c.zoom
        var tx = 0f
        if (c.kenBurns) { z *= 1f + 0.12f * frac; tx = -0.03f * frac * outAspect }
        Matrix.scaleM(m, 0, 1f / outAspect, 1f, 1f)
        Matrix.scaleM(m, 0, f, f, 1f)
        Matrix.translateM(m, 0, tx, 0f, 0f)
        Matrix.scaleM(m, 0, z, z, 1f)
        Matrix.rotateM(m, 0, -c.rotation, 0f, 0f, 1f)
        if (c.mirror) Matrix.scaleM(m, 0, -1f, 1f, 1f)
        Matrix.rotateM(m, 0, -rot.toFloat(), 0f, 0f, 1f)
        Matrix.scaleM(m, 0, coded, 1f, 1f)
        return m
    }

    // ------------------------------------------------------------------ переходы

    private fun trProg(id: String): TrProg? {
        if (trCache.containsKey(id)) return trCache[id]
        val r = buildTrProg(id)
        trCache[id] = r   // null тоже кэшируем, чтобы не компилировать сломанный шейдер каждый кадр
        return r
    }

    private fun buildTrProg(id: String): TrProg? {
        val def = Library.transition(id) ?: return null
        if (def.glsl.isBlank()) return null
        return try {
            val defaults = ArrayList<Triple<String, String, FloatArray>>()
            val re = Regex("uniform\\s+(float|int|bool|vec2|vec3|vec4)\\s+(\\w+)\\s*;\\s*//\\s*=\\s*(.+)")
            for (line in def.glsl.lines()) {
                val m = re.find(line) ?: continue
                val nums = Regex("-?\\d+(\\.\\d+)?").findAll(m.groupValues[3]).map { it.value.toFloat() }.toList().toFloatArray()
                val v = if (nums.isEmpty() && m.groupValues[3].contains("true")) floatArrayOf(1f) else nums
                defaults += Triple(m.groupValues[2], m.groupValues[1], v)
            }
            TrProg(Prog(Transitions.VERT, Transitions.fragment(def.glsl)), defaults)
        } catch (e: Throwable) {
            android.util.Log.e("CutFlow", "Переход $id не скомпилировался: ${e.message}")
            null
        }
    }

    private fun transitionPass(tp: TrProg, progress: Float, w: Int, h: Int) {
        val pr = tp.prog
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(pr.id)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[0])
        GLES20.glUniform1i(pr.u("uFrom"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[1])
        GLES20.glUniform1i(pr.u("uTo"), 1)
        GLES20.glUniform1f(pr.u("progress"), progress)
        GLES20.glUniform1f(pr.u("ratio"), w.toFloat() / h)
        for ((name, type, v) in tp.defaults) {
            val loc = pr.u(name)
            if (loc < 0 || v.isEmpty()) continue
            when (type) {
                "float" -> GLES20.glUniform1f(loc, v[0])
                "int", "bool" -> GLES20.glUniform1i(loc, v[0].toInt())
                "vec2" -> GLES20.glUniform2f(loc, v[0], v.getOrElse(1) { v[0] })
                "vec3" -> GLES20.glUniform3f(loc, v[0], v.getOrElse(1) { v[0] }, v.getOrElse(2) { v[0] })
                "vec4" -> GLES20.glUniform4f(loc, v[0], v.getOrElse(1) { v[0] }, v.getOrElse(2) { v[0] }, v.getOrElse(3) { 1f })
            }
        }
        drawQuad(pr)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    // ------------------------------------------------------------------ текст

    private fun textTex(tx: TextItem, W: Int, H: Int): TextTex {
        val key = "${tx.text}|${tx.font}|${tx.sizeFrac}|${tx.color}|${tx.strokeColor}|${tx.stroke}|${tx.bold}|${tx.shadow}|${tx.bgColor}|$W|$H"
        textCache[key]?.let { return it }
        if (textCache.size > 40) { textCache.values.forEach { GLES20.glDeleteTextures(1, intArrayOf(it.tex), 0) }; textCache.clear() }
        val bmp = makeTextBitmap(tx, W, H)
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        val r = TextTex(t[0], bmp.width, bmp.height)
        bmp.recycle()
        textCache[key] = r
        return r
    }

    private fun makeTextBitmap(tx: TextItem, W: Int, H: Int): Bitmap {
        val px = (tx.sizeFrac * H).coerceAtLeast(8f)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = px
            typeface = Library.typeface(tx.font, tx.bold)
        }
        val maxW = (W * 0.9f).toInt().coerceAtLeast(64)
        val layout = StaticLayout.Builder.obtain(tx.text, 0, tx.text.length, tp, maxW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        var lw = 0f
        for (i in 0 until layout.lineCount) lw = max(lw, layout.getLineWidth(i))
        val pad = px * 0.3f
        val bw = ceil(lw + 2 * pad).toInt().coerceIn(8, 4096)
        val bh = (layout.height + 2 * pad).toInt().coerceIn(8, 4096)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if ((tx.bgColor ushr 24) != 0) {
            val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tx.bgColor }
            c.drawRoundRect(RectF(pad * 0.4f, pad * 0.4f, bw - pad * 0.4f, bh - pad * 0.4f), px * 0.25f, px * 0.25f, bp)
        }
        c.translate(pad - (maxW - lw) / 2f, pad)
        if (tx.stroke) {
            tp.style = Paint.Style.STROKE
            tp.strokeWidth = px * 0.14f
            tp.strokeJoin = Paint.Join.ROUND
            tp.color = tx.strokeColor
            layout.draw(c)
        }
        tp.style = Paint.Style.FILL
        tp.color = tx.color
        if (tx.shadow) tp.setShadowLayer(px * 0.08f, 0f, px * 0.05f, 0xAA000000.toInt())
        layout.draw(c)
        return bmp
    }

    private fun easeOut(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)
    private fun easeOutBack(t: Float): Float { val c1 = 1.70158f; val c3 = c1 + 1f; val x = t - 1f; return 1f + c3 * x * x * x + c1 * x * x }

    private fun drawTexts(p: Project, t: Long, W: Int, H: Int) {
        var any = false
        for (tx in p.texts) {
            if (tx.text.isBlank() || t < tx.startUs || t >= tx.startUs + tx.durUs) continue
            if (!any) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(0, 0, W, H)
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                GLES20.glUseProgram(overlay.id)
                any = true
            }
            val tt = textTex(tx, W, H)
            val local = (t - tx.startUs) / 1e6f
            val total = tx.durUs / 1e6f
            val inT = min(local / 0.4f, 1f).coerceIn(0f, 1f)
            val outT = min((total - local) / 0.3f, 1f).coerceIn(0f, 1f)
            var alpha = 1f; var sc = 1f; var ox = 0f; var oy = 0f; var reveal = 1f
            when (tx.anim) {
                "fade" -> alpha = inT * outT
                "pop" -> { sc = easeOutBack(inT); alpha = min(1f, inT * 3f) * outT }
                "slide_up" -> { oy = -(1f - easeOut(inT)) * 0.2f; alpha = inT * outT }
                "slide_left" -> { ox = (1f - easeOut(inT)) * 0.5f; alpha = inT * outT }
                "reveal" -> { reveal = inT.let { min(local / 0.8f, 1f) }; alpha = outT }
                "bounce" -> { oy = abs(sin(local * 6f)) * 0.04f; alpha = inT * outT }
            }
            val m = FloatArray(16)
            Matrix.setIdentityM(m, 0)
            Matrix.translateM(m, 0, tx.x * 2f - 1f + ox, 1f - tx.y * 2f + oy, 0f)
            Matrix.scaleM(m, 0, tt.w.toFloat() / W * sc, tt.h.toFloat() / H * sc, 1f)
            GLES20.glUniformMatrix4fv(overlay.u("uMvp"), 1, false, m, 0)
            GLES20.glUniformMatrix4fv(overlay.u("uTexM"), 1, false, FLIPV, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tt.tex)
            GLES20.glUniform1i(overlay.u("uTex"), 0)
            GLES20.glUniform1f(overlay.u("uAlpha"), alpha)
            GLES20.glUniform1f(overlay.u("uReveal"), reveal)
            drawQuad(overlay)
        }
        if (any) GLES20.glDisable(GLES20.GL_BLEND)
    }

    fun release() {
        runCatching {
            if (fbo[0] != 0) { GLES20.glDeleteFramebuffers(2, fbo, 0); GLES20.glDeleteTextures(2, fboTex, 0) }
            textCache.values.forEach { GLES20.glDeleteTextures(1, intArrayOf(it.tex), 0) }
            textCache.clear()
        }
    }

    companion object {
        private val FLIPV = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)

        private const val LAYER_VS = """
attribute vec2 aPos;
attribute vec2 aUv;
uniform mat4 uMvp;
uniform mat4 uTexM;
varying vec2 vUv;
varying vec2 vQ;
void main() {
    gl_Position = uMvp * vec4(aPos, 0.0, 1.0);
    vUv = (uTexM * vec4(aUv, 0.0, 1.0)).xy;
    vQ = aUv;
}
"""

        private fun layerFs(oes: Boolean): String {
            val head = if (oes) "#extension GL_OES_EGL_image_external : require\n" else ""
            val sampler = if (oes) "samplerExternalOES" else "sampler2D"
            return head + """
precision highp float;
varying vec2 vUv;
varying vec2 vQ;
uniform $sampler uTex;
uniform float uBri;
uniform float uCon;
uniform float uSat;
uniform int uFilter;
void main() {
    vec3 c = texture2D(uTex, vUv).rgb;
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    if (uFilter == 1) { c = vec3(l); }
    else if (uFilter == 2) { c = vec3(dot(c, vec3(0.393, 0.769, 0.189)), dot(c, vec3(0.349, 0.686, 0.168)), dot(c, vec3(0.272, 0.534, 0.131))); }
    else if (uFilter == 3) { c = mix(vec3(l), c, 1.5); c = (c - 0.5) * 1.1 + 0.5; }
    else if (uFilter == 4) { c *= vec3(1.1, 1.0, 0.88); }
    else if (uFilter == 5) { c *= vec3(0.9, 1.0, 1.12); }
    else if (uFilter == 6) { vec2 d = vQ - 0.5; c *= 1.0 - dot(d, d) * 1.6; }
    else if (uFilter == 7) { c = 1.0 - c; }
    else if (uFilter == 8) { c = mix(c, vec3(0.5), 0.2) + 0.05; }
    else if (uFilter == 9) { c = mix(vec3(l), c, 1.1); c *= mix(vec3(0.85, 1.0, 1.1), vec3(1.1, 0.97, 0.85), l); c = (c - 0.5) * 1.12 + 0.5; }
    else if (uFilter == 10) { c = (vec3(l) - 0.5) * 1.5 + 0.5; }
    else if (uFilter == 11) { c = vec3(c.r * 1.1, c.g * 0.95, c.b * 0.8); c = mix(c, vec3(0.5), 0.15); }
    else if (uFilter == 12) { c = c * 1.1 + vec3(0.05, 0.03, 0.08); c = mix(vec3(l), c, 0.9); }
    c += uBri;
    c = (c - 0.5) * uCon + 0.5;
    float l2 = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(l2), c, uSat);
    gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""
        }

        private const val OVERLAY_FS = """
precision mediump float;
varying vec2 vUv;
varying vec2 vQ;
uniform sampler2D uTex;
uniform float uAlpha;
uniform float uReveal;
void main() {
    if (vQ.x > uReveal) discard;
    gl_FragColor = texture2D(uTex, vUv) * uAlpha;
}
"""
    }
}
