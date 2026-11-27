package com.cutflow.app

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

data class TransitionDef(val id: String, val name: String, val glsl: String, val pack: String?)
data class FontDef(val id: String, val name: String, val pack: String?)
data class SoundDef(val id: String, val name: String, val cat: String, val uri: Uri?, val pack: String?)
data class TemplateDef(val id: String, val name: String, val json: String, val pack: String?)

/**
 * Библиотека: встроенные + установленные из zip-паков переходы / шрифты / звуки / шаблоны.
 *
 * Формат zip-пака (все папки необязательны):
 *   pack.json                 {"name": "Мой пак", "author": "..."}
 *   transitions/<id>.glsl     GLSL в формате gl-transitions (+ необязательно <id>.json {"name": "..."})
 *   fonts/*.ttf | *.otf
 *   sounds/*.mp3 | *.wav | *.ogg | *.m4a | *.aac | *.flac
 *   templates/*.json          см. README
 */
object Library {
    var version by mutableIntStateOf(0)

    val transitions = ArrayList<TransitionDef>()
    val fonts = ArrayList<FontDef>()
    val sounds = ArrayList<SoundDef>()
    val templates = ArrayList<TemplateDef>()

    private val typefaces = HashMap<String, Typeface>()
    private var appCtx: Context? = null

    private val SYSTEM_FONTS = listOf(
        "sans-serif" to "Обычный", "sans-serif-medium" to "Средний", "sans-serif-light" to "Тонкий",
        "sans-serif-black" to "Жирный", "sans-serif-condensed" to "Узкий", "serif" to "С засечками",
        "monospace" to "Моно", "serif-monospace" to "Моно с засечками", "cursive" to "Курсив", "casual" to "Casual",
    )

    private fun packsDir(): File? = appCtx?.let { File(it.filesDir, "packs").apply { mkdirs() } }

    val packs: List<String>
        get() = packsDir()?.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.map { it.name }?.sorted()
            ?: emptyList()

    @Synchronized
    fun load(ctx: Context) {
        appCtx = ctx.applicationContext
        transitions.clear(); fonts.clear(); sounds.clear(); templates.clear(); typefaces.clear()

        transitions += TransitionDef("none", "Без перехода", "", null)
        Transitions.all.forEach { transitions += TransitionDef(it.id, it.name, it.body, null) }

        SYSTEM_FONTS.forEach { fonts += FontDef(it.first, it.second, null) }
        runCatching {
            ctx.assets.list("fonts")?.sorted()?.forEach { f ->
                if (f.endsWith(".ttf", true) || f.endsWith(".otf", true)) fonts += FontDef("asset:$f", prettyName(f), null)
            }
        }

        Sfx.all.forEach { sounds += SoundDef(it.id, it.name, it.cat, null, null) }
        BuiltinTemplates.list.forEach { templates += it }

        packs.forEach { loadPack(it) }
        version++
    }

    private fun prettyName(file: String) =
        file.substringBeforeLast('.').removeSuffix("-Regular").replace('_', ' ').replace('-', ' ')

    private fun loadPack(name: String) {
        val dir = File(packsDir() ?: return, name)
        File(dir, "transitions").listFiles { f -> f.extension.equals("glsl", true) }?.sortedBy { it.name }?.forEach { f ->
            val base = f.nameWithoutExtension
            val nm = readJsonName(File(f.parentFile, "$base.json")) ?: base
            runCatching { transitions += TransitionDef("pack:$name/$base", nm, f.readText(), name) }
        }
        File(dir, "fonts").listFiles { f -> f.extension.lowercase() in setOf("ttf", "otf") }?.sortedBy { it.name }?.forEach { f ->
            fonts += FontDef("file:${f.absolutePath}", prettyName(f.name), name)
        }
        File(dir, "sounds").listFiles { f -> f.extension.lowercase() in AUDIO_EXT }?.sortedBy { it.name }?.forEach { f ->
            sounds += SoundDef("file:${f.absolutePath}", prettyName(f.name), name, Uri.fromFile(f), name)
        }
        File(dir, "templates").listFiles { f -> f.extension.equals("json", true) }?.sortedBy { it.name }?.forEach { f ->
            runCatching {
                val txt = f.readText()
                val nm = JSONObject(txt).optString("name", f.nameWithoutExtension)
                templates += TemplateDef("pack:$name/${f.nameWithoutExtension}", nm, txt, name)
            }
        }
    }

    private fun readJsonName(f: File): String? =
        if (f.exists()) runCatching { JSONObject(f.readText()).optString("name").ifBlank { null } }.getOrNull() else null

    fun transition(id: String): TransitionDef? = transitions.firstOrNull { it.id == id }

    fun typeface(id: String, bold: Boolean = false): Typeface {
        val base = synchronized(this) {
            typefaces.getOrPut(id) {
                runCatching {
                    when {
                        id.startsWith("asset:") -> Typeface.createFromAsset(appCtx!!.assets, "fonts/" + id.removePrefix("asset:"))
                        id.startsWith("file:") -> Typeface.createFromFile(id.removePrefix("file:"))
                        else -> Typeface.create(id, Typeface.NORMAL)
                    }
                }.getOrDefault(Typeface.DEFAULT)
            }
        }
        return if (bold) Typeface.create(base, Typeface.BOLD) else base
    }

    fun soundUri(id: String): Uri? = sounds.firstOrNull { it.id == id }?.uri

    // ---------------------------------------------------------------- установка паков

    /** Возвращает человекочитаемый результат. Бросает исключение при ошибке. */
    fun installPack(ctx: Context, uri: Uri): String {
        val packsRoot = packsDir() ?: error("Нет доступа к хранилищу")
        val tmp = File(packsRoot, ".tmp_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            var entries = 0
            var bytes = 0L
            val canonTmp = tmp.canonicalPath + File.separator
            ctx.contentResolver.openInputStream(uri)!!.use { ins ->
                ZipInputStream(ins.buffered()).use { zin ->
                    while (true) {
                        val e = zin.nextEntry ?: break
                        if (e.isDirectory) continue
                        val ext = e.name.substringAfterLast('.', "").lowercase()
                        if (ext !in ALLOWED_EXT) continue
                        if (++entries > 2000) error("Слишком много файлов в паке")
                        val out = File(tmp, e.name).canonicalFile
                        if (!out.path.startsWith(canonTmp)) continue // защита от zip-slip
                        out.parentFile?.mkdirs()
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = zin.read(buf)
                                if (n < 0) break
                                bytes += n
                                if (bytes > 400L * 1024 * 1024) error("Пак больше 400 МБ")
                                o.write(buf, 0, n)
                            }
                        }
                    }
                }
            }
            // Если всё лежит внутри единственной корневой папки — поднимаем её содержимое
            var root = tmp
            val top = tmp.listFiles()?.toList() ?: emptyList()
            if (top.size == 1 && top[0].isDirectory && !File(tmp, "pack.json").exists()) root = top[0]

            var packName = readJsonName(File(root, "pack.json"))
            if (packName == null) {
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0) packName = c.getString(i)?.substringBeforeLast('.')
                    }
                }
            }
            val safe = (packName ?: "pack").replace(Regex("[^\\p{L}\\p{N}_\\- ]"), "_").trim().ifEmpty { "pack" }
            val dest = File(packsRoot, safe)
            if (dest.exists()) dest.deleteRecursively()
            if (!root.renameTo(dest)) { root.copyRecursively(dest, true) }

            load(ctx)
            fun count(sub: String, exts: Set<String>) =
                File(dest, sub).listFiles()?.count { it.isFile && it.extension.lowercase() in exts } ?: 0
            return "Пак «$safe» установлен: переходов ${count("transitions", setOf("glsl"))}, " +
                "шрифтов ${count("fonts", setOf("ttf", "otf"))}, звуков ${count("sounds", AUDIO_EXT)}, " +
                "шаблонов ${count("templates", setOf("json"))}"
        } finally {
            tmp.deleteRecursively()
        }
    }

    fun removePack(ctx: Context, name: String) {
        File(packsDir() ?: return, name).deleteRecursively()
        load(ctx)
    }

    private val AUDIO_EXT = setOf("mp3", "wav", "ogg", "m4a", "aac", "flac")
    private val ALLOWED_EXT = AUDIO_EXT + setOf("glsl", "json", "ttf", "otf")
}

/** Встроенные шаблоны — тот же JSON-формат, что и в паках. */
object BuiltinTemplates {
    private fun t(id: String, name: String, json: String) = TemplateDef("builtin:$id", name, json, null)

    val list = listOf(
        t("vlog", "Влог-интро", """{"name":"Влог-интро","aspect":"9:16","filter":"warm","transition":"zoomin","transitionMs":500,"clipMaxMs":3000,
            "texts":[{"text":"МОЙ ВЛОГ","font":"sans-serif-black","size":0.09,"color":"#FFFFFF","startMs":300,"durMs":2400,"x":0.5,"y":0.3,"anim":"pop"}],
            "sfx":[{"id":"whoosh","atMs":0}]}"""),
        t("cinema", "Кино", """{"name":"Кино","aspect":"21:9","filter":"cinema","transition":"fadeblack","transitionMs":900,
            "texts":[{"text":"РЕЖИССЁР · НАЗВАНИЕ","font":"serif","size":0.06,"color":"#E8E0D0","startMs":500,"durMs":3500,"x":0.5,"y":0.85,"anim":"fade","stroke":false}]}"""),
        t("dynamic", "Динамичный клип", """{"name":"Динамичный клип","filter":"vivid","transition":"glitch","transitionMs":250,"clipMaxMs":1500,"sfxOnCut":"whoosh"}"""),
        t("retro", "Ретро VHS", """{"name":"Ретро VHS","filter":"retro","transition":"glitch","transitionMs":400,
            "texts":[{"text":"▶ PLAY","font":"monospace","size":0.05,"color":"#FFFFFF","startMs":0,"durMs":3000,"x":0.12,"y":0.08,"anim":"none"}],"sfx":[{"id":"glitch","atMs":0}]}"""),
        t("love", "Романтика", """{"name":"Романтика","filter":"dream","transition":"fade","transitionMs":1000,"kenBurns":true,
            "texts":[{"text":"♥","font":"cursive","size":0.14,"color":"#FF5C8A","startMs":500,"durMs":3000,"x":0.5,"y":0.5,"anim":"pop"}]}"""),
        t("sport", "Спорт-хайлайт", """{"name":"Спорт-хайлайт","filter":"vivid","transition":"slideleft","transitionMs":300,"clipMaxMs":2000,"sfxOnCut":"swish",
            "texts":[{"text":"HIGHLIGHTS","font":"sans-serif-black","size":0.1,"color":"#FFD400","startMs":0,"durMs":2500,"x":0.5,"y":0.2,"anim":"slide_left"}]}"""),
        t("meme", "Мем", """{"name":"Мем","texts":[
            {"text":"ВЕРХНИЙ ТЕКСТ","font":"sans-serif-black","size":0.09,"color":"#FFFFFF","startMs":0,"durMs":4000,"x":0.5,"y":0.1,"anim":"none"},
            {"text":"НИЖНИЙ ТЕКСТ","font":"sans-serif-black","size":0.09,"color":"#FFFFFF","startMs":0,"durMs":4000,"x":0.5,"y":0.9,"anim":"none"}]}"""),
        t("travel", "Путешествие", """{"name":"Путешествие","filter":"warm","transition":"cube_left","transitionMs":800,"clipMaxMs":4000,"kenBurns":true,
            "texts":[{"text":"ПУТЕШЕСТВИЕ","font":"sans-serif-light","size":0.07,"color":"#FFFFFF","startMs":400,"durMs":3000,"x":0.5,"y":0.5,"anim":"reveal"}],"sfx":[{"id":"whoosh","atMs":0}]}"""),
        t("noir", "Нуар", """{"name":"Нуар","filter":"noir","transition":"fadeblack","transitionMs":800}"""),
        t("slideshow", "Слайд-шоу", """{"name":"Слайд-шоу","filter":"none","transition":"fade","transitionMs":900,"imageMs":3000,"kenBurns":true}"""),
        t("cubes", "3D-куб микс", """{"name":"3D-куб микс","transition":"cube_left","transitionMs":900,"clipMaxMs":3000,"sfxOnCut":"whoosh"}"""),
    )
}
