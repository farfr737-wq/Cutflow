package com.cutflow.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.min

private data class PreviewReq(val project: Project, val t: Long)

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    var project by mutableStateOf(Project()); private set
    var playhead by mutableLongStateOf(0L); private set
    var playing by mutableStateOf(false); private set
    var selClip by mutableStateOf<Long?>(null)
    var selText by mutableStateOf<Long?>(null)
    var selAudio by mutableStateOf<Long?>(null)
    var preview by mutableStateOf<ImageBitmap?>(null); private set
    var busy by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null)

    // экспорт
    var exporting by mutableStateOf(false); private set
    var exportProgress by mutableStateOf(0f); private set
    var exportDone by mutableStateOf<Exporter.Result?>(null)
    var exportError by mutableStateOf<String?>(null)
    @Volatile private var cancelExport = false

    val thumbs = mutableStateMapOf<Long, ImageBitmap>()

    private val undo = ArrayDeque<Project>()
    private val redo = ArrayDeque<Project>()
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    private var lastKey: String? = null
    private var lastKeyAt = 0L

    private val engine = PreviewEngine(app)
    private val audioPreview = AudioPreview()
    private val req = MutableStateFlow(PreviewReq(Project(), 0L))
    private var playJob: Job? = null
    private var idCounter = System.currentTimeMillis()
    private fun nextId() = idCounter++

    init {
        viewModelScope.launch {
            req.collectLatest { r ->
                val bmp = withContext(engine.dispatcher) {
                    runCatching { engine.render(r.project, r.t, 540) }
                        .onFailure { android.util.Log.e("CutFlow", "preview", it) }.getOrNull()
                }
                if (bmp != null) preview = bmp.asImageBitmap()
            }
        }
    }

    override fun onCleared() {
        playing = false
        audioPreview.stop()
        engine.shutdown()
    }

    val total: Long get() = project.totalUs
    val selectedClip: Clip? get() = project.clips.firstOrNull { it.id == selClip }
    val selectedText: TextItem? get() = project.texts.firstOrNull { it.id == selText }
    val selectedAudio: AudioItem? get() = project.audios.firstOrNull { it.id == selAudio }

    fun msg(s: String) { message = s }

    // ------------------------------------------------------------ состояние / undo

    /** key != null — слияние подряд идущих правок (ползунки) в один шаг отмены. */
    fun update(key: String? = null, f: (Project) -> Project) {
        val now = System.currentTimeMillis()
        val merge = key != null && key == lastKey && now - lastKeyAt < 1500
        if (!merge) {
            undo.addLast(project)
            if (undo.size > 60) undo.removeFirst()
            redo.clear()
        }
        lastKey = key; lastKeyAt = now
        project = f(project)
        playhead = playhead.coerceIn(0L, project.totalUs)
        canUndo = undo.isNotEmpty(); canRedo = redo.isNotEmpty()
        refresh()
    }

    fun undo() {
        val p = undo.removeLastOrNull() ?: return
        redo.addLast(project); project = p; lastKey = null
        canUndo = undo.isNotEmpty(); canRedo = redo.isNotEmpty(); refresh()
    }

    fun redo() {
        val p = redo.removeLastOrNull() ?: return
        undo.addLast(project); project = p; lastKey = null
        canUndo = undo.isNotEmpty(); canRedo = redo.isNotEmpty(); refresh()
    }

    private fun refresh() { req.value = PreviewReq(project, playhead) }

    fun seek(t: Long) { playhead = t.coerceIn(0L, project.totalUs); refresh() }

    // ------------------------------------------------------------ импорт

    private class Probe(val clip: Clip, val aspect: Float)

    private fun probe(uri: Uri): Probe? {
        val mime = ctx.contentResolver.getType(uri) ?: ""
        return try {
            if (mime.startsWith("image")) {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                val asp = if (o.outHeight > 0) o.outWidth.toFloat() / o.outHeight else 16f / 9f
                Probe(Clip(nextId(), uri, true, 60_000_000L, outUs = 4_000_000L), asp)
            } else {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, uri)
                    val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() * 1000L
                    val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toFloat()
                    val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toFloat()
                    val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    val asp = if (rot == 90 || rot == 270) h / w else w / h
                    Probe(Clip(nextId(), uri, false, dur), asp)
                } finally { r.release() }
            }
        } catch (e: Throwable) { null }
    }

    fun addMedia(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            busy = "Импорт…"
            val probes = withContext(Dispatchers.IO) { uris.mapNotNull { probe(it) } }
            busy = null
            if (probes.isEmpty()) { msg("Не удалось открыть файл"); return@launch }
            update { p ->
                val asp = if (p.clips.isEmpty()) {
                    val a = probes[0].aspect
                    when { a > 1.2f -> 16f / 9f; a < 0.85f -> 9f / 16f; else -> 1f }
                } else p.aspect
                p.copy(clips = p.clips + probes.map { it.clip }, aspect = asp)
            }
            selClip = probes.last().clip.id
        }
    }

    fun thumb(c: Clip) {
        if (thumbs.containsKey(c.id)) return
        viewModelScope.launch(Dispatchers.IO) {
            val bmp: Bitmap? = try {
                if (c.isImage) {
                    val o = BitmapFactory.Options().apply { inSampleSize = 8 }
                    ctx.contentResolver.openInputStream(c.uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                } else {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(ctx, c.uri)
                        r.getScaledFrameAtTime(c.inUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160)
                    } finally { r.release() }
                }
            } catch (e: Throwable) { null }
            if (bmp != null) withContext(Dispatchers.Main) { thumbs[c.id] = bmp.asImageBitmap() }
        }
    }

    // ------------------------------------------------------------ клипы

    fun updateClip(id: Long, key: String? = null, f: (Clip) -> Clip) =
        update(key?.let { "$it$id" }) { p -> p.copy(clips = p.clips.map { if (it.id == id) f(it) else it }) }

    fun splitAtPlayhead() {
        val p = project
        val st = p.starts()
        val t = playhead
        val i = p.clips.indices.lastOrNull { st[it] <= t && t < st[it] + p.clips[it].durationUs } ?: return
        val c = p.clips[i]
        val local = t - st[i]
        if (local < 200_000 || c.durationUs - local < 200_000) { msg("Поставьте маркер внутри клипа"); return }
        val srcSplit = c.inUs + (local * c.speed).toLong()
        val a = c.copy(outUs = srcSplit)
        val b = c.copy(id = nextId(), inUs = srcSplit, transition = "none")
        update { it.copy(clips = it.clips.toMutableList().apply { set(i, a); add(i + 1, b) }) }
        selClip = b.id
    }

    fun deleteSelectedClip() {
        val id = selClip ?: return
        update { it.copy(clips = it.clips.filter { c -> c.id != id }) }
        selClip = null
    }

    fun duplicateSelectedClip() {
        val c = selectedClip ?: return
        val i = project.clips.indexOf(c)
        val n = c.copy(id = nextId(), transition = "none")
        update { it.copy(clips = it.clips.toMutableList().apply { add(i + 1, n) }) }
        selClip = n.id
    }

    fun moveSelectedClip(dir: Int) {
        val c = selectedClip ?: return
        val i = project.clips.indexOf(c)
        val j = i + dir
        if (j !in project.clips.indices) return
        update { it.copy(clips = it.clips.toMutableList().apply { removeAt(i); add(j, c) }) }
    }

    fun setAspect(a: Float) = update { it.copy(aspect = a) }

    // ------------------------------------------------------------ текст

    fun addText() {
        val t = TextItem(nextId(), "Текст", startUs = playhead, durUs = 3_000_000L)
        update { it.copy(texts = it.texts + t) }
        selText = t.id; selClip = null; selAudio = null
    }

    fun updateText(id: Long, key: String? = null, f: (TextItem) -> TextItem) =
        update(key?.let { "$it$id" }) { p -> p.copy(texts = p.texts.map { if (it.id == id) f(it) else it }) }

    fun deleteText(id: Long) { update { it.copy(texts = it.texts.filter { t -> t.id != id }) }; selText = null }

    // ------------------------------------------------------------ звук

    fun addSfx(id: String, name: String) {
        val a = AudioItem(nextId(), name, null, id, playhead, durUs = Sfx.durationUs(id), music = false)
        update { it.copy(audios = it.audios + a) }
        selAudio = a.id
    }

    fun addSoundFile(uri: Uri, name: String, music: Boolean) {
        viewModelScope.launch {
            val dur = withContext(Dispatchers.IO) {
                runCatching {
                    val r = MediaMetadataRetriever()
                    try { r.setDataSource(ctx, uri); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() * 1000L }
                    finally { r.release() }
                }.getOrNull()
            }
            if (dur == null) { msg("Не удалось прочитать аудио"); return@launch }
            val a = AudioItem(nextId(), name, uri, null, playhead, durUs = dur, music = music)
            update { it.copy(audios = it.audios + a) }
            selAudio = a.id
        }
    }

    fun updateAudio(id: Long, key: String? = null, f: (AudioItem) -> AudioItem) =
        update(key?.let { "$it$id" }) { p -> p.copy(audios = p.audios.map { if (it.id == id) f(it) else it }) }

    fun deleteAudio(id: Long) { update { it.copy(audios = it.audios.filter { a -> a.id != id }) }; selAudio = null }

    fun audition(sound: SoundDef) {
        viewModelScope.launch(Dispatchers.IO) {
            if (sound.uri == null) audioPreview.audition(Sfx.generate(sound.id))
            else {
                // файл из пака: короткое превью через общий декодер
                AudioMixer.decode(ctx, sound.uri, 0, 8_000_000L)?.let { pcm ->
                    val mono = ShortArray(pcm.data.size / pcm.ch) { pcm.data[it * pcm.ch] }
                    if (pcm.rate == AudioMixer.SR) audioPreview.audition(mono)
                    else msg("Превью доступно для файлов 44.1 кГц; добавьте звук на дорожку")
                }
            }
        }
    }

    // ------------------------------------------------------------ переходы / шаблоны

    fun setTransition(clipId: Long, id: String) {
        updateClip(clipId) { it.copy(transition = id) }
        val i = project.clips.indexOfFirst { it.id == clipId }
        if (i > 0 && id != "none") {
            val ov = project.overlap(i)
            seek(project.starts()[i] + ov / 4)
        }
    }

    fun applyTemplate(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: run { msg("Битый шаблон"); return }
        update { p ->
            var clips = p.clips
            val filter = o.optString("filter", "")
            if (filter.isNotEmpty()) clips = clips.map { it.copy(filter = filter) }
            val tr = o.optString("transition", "")
            if (tr.isNotEmpty()) {
                val ms = o.optLong("transitionMs", 700)
                clips = clips.mapIndexed { i, c -> if (i == 0) c else c.copy(transition = tr, transitionUs = ms * 1000) }
            }
            val maxMs = o.optLong("clipMaxMs", 0)
            if (maxMs > 0) clips = clips.map { c ->
                if (c.isImage) c else c.copy(outUs = min(c.outUs, c.inUs + (maxMs * 1000 * c.speed).toLong()))
            }
            val imgMs = o.optLong("imageMs", 0)
            if (imgMs > 0) clips = clips.map { c -> if (c.isImage) c.copy(outUs = c.inUs + imgMs * 1000) else c }
            if (o.has("kenBurns")) { val kb = o.optBoolean("kenBurns"); clips = clips.map { it.copy(kenBurns = kb) } }

            var aspect = p.aspect
            o.optString("aspect", "").takeIf { it.contains(":") }?.let { s ->
                val (a, b) = s.split(":").map { it.trim().toFloatOrNull() ?: 0f }
                if (a > 0 && b > 0) aspect = a / b
            }
            val np = p.copy(clips = clips, aspect = aspect)
            val starts = np.starts()

            val texts = ArrayList<TextItem>()
            o.optJSONArray("texts")?.let { arr ->
                for (k in 0 until arr.length()) {
                    val j = arr.getJSONObject(k)
                    texts += TextItem(
                        id = nextId(), text = j.optString("text", "Текст"),
                        font = j.optString("font", "sans-serif"),
                        sizeFrac = j.optDouble("size", 0.08).toFloat(),
                        color = parseColor(j.optString("color", "#FFFFFF")),
                        stroke = j.optBoolean("stroke", true), bold = j.optBoolean("bold", false),
                        startUs = playhead + j.optLong("startMs", 0) * 1000, durUs = j.optLong("durMs", 3000) * 1000,
                        x = j.optDouble("x", 0.5).toFloat(), y = j.optDouble("y", 0.5).toFloat(),
                        anim = j.optString("anim", "fade"),
                    )
                }
            }
            val sfx = ArrayList<AudioItem>()
            o.optJSONArray("sfx")?.let { arr ->
                for (k in 0 until arr.length()) {
                    val j = arr.getJSONObject(k)
                    val id = j.optString("id")
                    if (id.isNotEmpty() && Sfx.generate(id).isNotEmpty())
                        sfx += AudioItem(nextId(), Sfx.all.firstOrNull { it.id == id }?.name ?: id, null, id,
                            playhead + j.optLong("atMs", 0) * 1000, durUs = Sfx.durationUs(id), music = false)
                }
            }
            val onCut = o.optString("sfxOnCut", "")
            if (onCut.isNotEmpty() && Sfx.generate(onCut).isNotEmpty()) {
                for (i in 1 until np.clips.size) {
                    sfx += AudioItem(nextId(), onCut, null, onCut, (starts[i] - 100_000).coerceAtLeast(0), durUs = Sfx.durationUs(onCut), music = false)
                }
            }
            np.copy(texts = np.texts + texts, audios = np.audios + sfx)
        }
        msg("Шаблон «${o.optString("name", "")}» применён")
    }

    private fun parseColor(s: String): Int = runCatching { Color.parseColor(s) }.getOrDefault(Color.WHITE)

    // ------------------------------------------------------------ паки

    fun installPack(uri: Uri) {
        viewModelScope.launch {
            busy = "Устанавливаю пак…"
            val r = withContext(Dispatchers.IO) { runCatching { Library.installPack(ctx, uri) } }
            busy = null
            msg(r.getOrElse { "Ошибка пака: ${it.message}" })
        }
    }

    fun removePack(name: String) {
        viewModelScope.launch(Dispatchers.IO) { Library.removePack(ctx, name) }
    }

    // ------------------------------------------------------------ воспроизведение

    fun togglePlay() { if (playing) stopPlay() else startPlay() }

    fun stopPlay() {
        playing = false
        playJob?.cancel()
        audioPreview.stop()
    }

    private fun startPlay() {
        if (project.clips.isEmpty()) return
        if (playhead >= project.totalUs - 50_000) seek(0)
        playing = true
        playJob = viewModelScope.launch {
            val snap = project
            busy = "Готовлю звук…"
            val pcm = withContext(Dispatchers.Default) { runCatching { AudioMixer.mix(ctx, snap) }.getOrNull() }
            busy = null
            if (!playing) return@launch
            val startT = playhead
            val hasAudio = pcm != null
            if (pcm != null) audioPreview.start(pcm, startT)
            val t0 = System.nanoTime()
            while (playing) {
                val t = if (hasAudio) audioPreview.positionUs() else startT + (System.nanoTime() - t0) / 1000
                if (t >= project.totalUs) { playhead = project.totalUs; refresh(); break }
                playhead = t
                refresh()
                delay(10)
            }
            stopPlay()
        }
    }

    // ------------------------------------------------------------ экспорт

    fun startExport(s: Exporter.Settings) {
        if (project.clips.isEmpty()) { msg("Добавьте хотя бы один клип"); return }
        stopPlay()
        val snap = project
        exporting = true; exportProgress = 0f; cancelExport = false
        exportDone = null; exportError = null
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { Exporter(ctx).export(snap, s, { exportProgress = it }, { cancelExport }) }
            }
            exporting = false
            r.onSuccess { exportDone = it }
            r.onFailure { if (it !is Exporter.Cancelled) { exportError = it.message ?: it.toString(); android.util.Log.e("CutFlow", "export", it) } }
        }
    }

    fun cancelExport() { cancelExport = true }
}
