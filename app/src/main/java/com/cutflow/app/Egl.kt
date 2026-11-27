package com.cutflow.app

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/** Минимальная обёртка над EGL14. recordable=true — для вывода в Surface видеокодера. */
class EglCore(recordable: Boolean) {
    val display: EGLDisplay
    val context: EGLContext
    private val config: EGLConfig

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "Нет EGL-дисплея" }
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize не удался" }
        val attrs = ArrayList<Int>()
        attrs.addAll(listOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
        ))
        if (recordable) {
            attrs.addAll(listOf(0x3142 /* EGL_RECORDABLE_ANDROID */, 1, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT))
        } else {
            attrs.addAll(listOf(EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT))
        }
        attrs.add(EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(display, attrs.toIntArray(), 0, configs, 0, 1, n, 0) && n[0] > 0) { "Нет подходящего EGL-конфига" }
        config = configs[0]!!
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "Не удалось создать GL-контекст" }
    }

    fun windowSurface(s: Surface): EGLSurface =
        EGL14.eglCreateWindowSurface(display, config, s, intArrayOf(EGL14.EGL_NONE), 0)

    fun pbuffer(w: Int, h: Int): EGLSurface =
        EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0)

    fun makeCurrent(s: EGLSurface) { EGL14.eglMakeCurrent(display, s, s, context) }
    fun swap(s: EGLSurface) { EGL14.eglSwapBuffers(display, s) }
    fun setPresentationTime(s: EGLSurface, ns: Long) { EGLExt.eglPresentationTimeANDROID(display, s, ns) }
    fun destroySurface(s: EGLSurface) { EGL14.eglDestroySurface(display, s) }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }
}
