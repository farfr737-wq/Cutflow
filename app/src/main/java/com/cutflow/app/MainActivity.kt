@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.cutflow.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Library.transitions.isEmpty()) Library.load(applicationContext)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF38E8C6), onPrimary = Color(0xFF00201A),
                    background = Color(0xFF101114), surface = Color(0xFF16181C),
                    surfaceVariant = Color(0xFF23262C), secondaryContainer = Color(0xFF1E5C52),
                ),
            ) {
                Surface(Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.background) {
                    EditorScreen(vm)
                }
            }
        }
    }
}

private fun fmtTime(us: Long): String {
    val s = us / 1_000_000.0
    val m = (s / 60).toInt()
    return String.format(Locale.US, "%02d:%04.1f", m, s - m * 60)
}

private val TABS = listOf("Клип", "Текст", "Переходы", "Звук", "Шаблоны", "Фильтры", "Холст", "Паки")
private val COLORS = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFFF3B30, 0xFFFF9500, 0xFFFFD400, 0xFF34C759,
    0xFF38E8C6, 0xFF0A84FF, 0xFF5E5CE6, 0xFFFF2D92, 0xFFB0B0B0, 0xFF8B5E3C,
).map { it.toInt() }

@Composable
fun EditorScreen(vm: EditorViewModel) {
    val ctx = LocalContext.current
    val view = LocalView.current
    var tab by remember { mutableIntStateOf(0) }
    var showExport by remember { mutableStateOf(false) }

    DisposableEffect(vm.exporting) { view.keepScreenOn = vm.exporting; onDispose { view.keepScreenOn = false } }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { vm.addMedia(it) }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.addSoundFile(it, "Музыка", true) }
    }
    val pickPack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.installPack(it) } }

    Column(Modifier.fillMaxSize()) {
        // ---- верхняя панель
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("CutFlow", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.undo() }, enabled = vm.canUndo) { Text("↶") }
            TextButton(onClick = { vm.redo() }, enabled = vm.canRedo) { Text("↷") }
            Button(onClick = { showExport = true }, enabled = vm.project.clips.isNotEmpty()) { Text("Экспорт") }
        }

        // ---- превью
        Box(Modifier.fillMaxWidth().heightIn(max = 280.dp), contentAlignment = Alignment.Center) {
            val bmp = vm.preview
            if (bmp != null && vm.project.clips.isNotEmpty()) {
                Image(
                    bmp, null,
                    Modifier.aspectRatio(vm.project.aspect).heightIn(max = 280.dp).clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text("Добавьте видео или фото →  вкладка «Клип»", color = Color.Gray)
            }
        }

        // ---- управление воспроизведением
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.togglePlay() }) { Text(if (vm.playing) "⏸" else "▶", fontSize = 22.sp) }
            Text(fmtTime(vm.playhead) + " / " + fmtTime(vm.total), fontSize = 12.sp)
            Slider(
                value = vm.playhead.toFloat(),
                onValueChange = { vm.stopPlay(); vm.seek(it.toLong()) },
                valueRange = 0f..vm.total.coerceAtLeast(1L).toFloat(),
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
        }

        Timeline(vm, onTransitionTap = { tab = 2 })

        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 4.dp, containerColor = MaterialTheme.colorScheme.surface) {
            TABS.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 13.sp) }) }
        }

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
            when (tab) {
                0 -> ClipPanel(vm) { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
                1 -> TextPanel(vm)
                2 -> TransitionPanel(vm)
                3 -> SoundPanel(vm) { pickAudio.launch(arrayOf("audio/*")) }
                4 -> TemplatePanel(vm)
                5 -> FilterPanel(vm)
                6 -> CanvasPanel(vm)
                7 -> PackPanel(vm) { pickPack.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
            }
        }
    }

    vm.busy?.let { b ->
        AlertDialog(onDismissRequest = {}, confirmButton = {}, text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(Modifier.width(60.dp)); Spacer(Modifier.width(12.dp)); Text(b)
            }
        })
    }
    vm.message?.let { m ->
        AlertDialog(onDismissRequest = { vm.message = null }, confirmButton = { TextButton({ vm.message = null }) { Text("OK") } }, text = { Text(m) })
    }
    if (showExport) ExportDialog(vm, onClose = { showExport = false })
    if (vm.exporting) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Экспорт…") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { vm.exportProgress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.size(8.dp))
                    Text("${(vm.exportProgress * 100).toInt()}%  — не сворачивайте приложение")
                }
            },
            confirmButton = { TextButton({ vm.cancelExport() }) { Text("Отмена") } },
        )
    }
    vm.exportDone?.let { r ->
        AlertDialog(
            onDismissRequest = { vm.exportDone = null },
            title = { Text("Готово ✔") },
            text = { Text("Сохранено в Movies/CutFlow\n${r.summary}") },
            confirmButton = {
                TextButton({
                    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(r.uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    vm.exportDone = null
                }) { Text("Открыть") }
            },
            dismissButton = { TextButton({ vm.exportDone = null }) { Text("Закрыть") } },
        )
    }
    vm.exportError?.let { e ->
        AlertDialog(onDismissRequest = { vm.exportError = null }, confirmButton = { TextButton({ vm.exportError = null }) { Text("OK") } },
            title = { Text("Ошибка экспорта") }, text = { Text(e) })
    }
}

// ---------------------------------------------------------------------------- таймлайн

@Composable
private fun Timeline(vm: EditorViewModel, onTransitionTap: () -> Unit) {
    val p = vm.project
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(vertical = 6.dp)) {
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            items(p.clips, key = { it.id }) { c ->
                LaunchedEffect(c.id) { vm.thumb(c) }
                val idx = p.clips.indexOf(c)
                if (idx > 0) {
                    Box(
                        Modifier.padding(horizontal = 2.dp).size(26.dp).clip(RoundedCornerShape(13.dp))
                            .background(if (c.transition != "none") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { vm.selClip = c.id; vm.selText = null; vm.selAudio = null; onTransitionTap() },
                        contentAlignment = Alignment.Center,
                    ) { Text("◇", fontSize = 12.sp, color = if (c.transition != "none") Color.Black else Color.White) }
                }
                val w = (c.durationUs / 1e6f * 36f).coerceIn(64f, 200f).dp
                val sel = vm.selClip == c.id
                Box(
                    Modifier.width(w).height(56.dp).clip(RoundedCornerShape(6.dp))
                        .border(if (sel) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                        .background(Color(0xFF2A2D34))
                        .clickable { vm.selClip = c.id; vm.selText = null; vm.selAudio = null },
                ) {
                    vm.thumbs[c.id]?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    Text(fmtTime(c.durationUs).drop(3) + "s", fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(horizontal = 3.dp))
                    if (c.speed != 1f) Text("×${c.speed}", fontSize = 10.sp, modifier = Modifier.align(Alignment.TopEnd).background(Color(0x99000000)).padding(horizontal = 3.dp))
                }
            }
        }
        if (p.texts.isNotEmpty() || p.audios.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                p.texts.forEach { t ->
                    ItemChip("T  " + t.text.take(14), vm.selText == t.id) { vm.selText = t.id; vm.selClip = null; vm.selAudio = null; vm.seek(t.startUs) }
                }
                p.audios.forEach { a ->
                    ItemChip((if (a.music) "♪  " else "🔊  ") + a.name.take(14), vm.selAudio == a.id) { vm.selAudio = a.id; vm.selClip = null; vm.selText = null; vm.seek(a.startUs) }
                }
            }
        }
    }
}

@Composable
private fun ItemChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .border(BorderStroke(if (selected) 1.dp else 0.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp),
    ) { Text(text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

// ---------------------------------------------------------------------------- общие виджеты

@Composable
private fun Section(title: String) { Text(title, fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) }

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, modifier = Modifier.width(96.dp))
        Slider(value.coerceIn(range.start, range.endInclusive), onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(valueText, fontSize = 12.sp, modifier = Modifier.width(54.dp))
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked, onChange)
    }
}

@Composable
private fun ColorRow(selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        COLORS.forEach { c ->
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(15.dp)).background(Color(c))
                    .border(if (c == selected) 3.dp else 1.dp, if (c == selected) MaterialTheme.colorScheme.primary else Color.Gray, RoundedCornerShape(15.dp))
                    .clickable { onPick(c) },
            )
        }
    }
}

@Composable
private fun Hint(s: String) { Text(s, color = Color.Gray, fontSize = 13.sp) }

// ---------------------------------------------------------------------------- вкладки

@Composable
private fun ClipPanel(vm: EditorViewModel, onAdd: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        Button(onClick = onAdd) { Text("＋ Видео/Фото") }
        OutlinedButton(onClick = { vm.splitAtPlayhead() }) { Text("✂ Разрезать") }
        OutlinedButton(onClick = { vm.duplicateSelectedClip() }, enabled = vm.selectedClip != null) { Text("⧉") }
        OutlinedButton(onClick = { vm.moveSelectedClip(-1) }, enabled = vm.selectedClip != null) { Text("◀") }
        OutlinedButton(onClick = { vm.moveSelectedClip(1) }, enabled = vm.selectedClip != null) { Text("▶") }
        OutlinedButton(onClick = { vm.deleteSelectedClip() }, enabled = vm.selectedClip != null,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF6B6B))) { Text("🗑") }
    }
    val c = vm.selectedClip
    if (c == null) { Spacer(Modifier.size(8.dp)); Hint("Выберите клип на таймлайне."); return }

    Section("Обрезка")
    if (c.isImage) {
        LabeledSlider("Длительность", (c.outUs - c.inUs) / 1e6f, 0.5f..15f, String.format(Locale.US, "%.1f с", (c.outUs - c.inUs) / 1e6f)) {
            vm.updateClip(c.id, "dur") { x -> x.copy(outUs = x.inUs + (it * 1e6f).toLong()) }
        }
    } else {
        RangeSlider(
            value = (c.inUs.toFloat() / 1e6f)..(c.outUs.toFloat() / 1e6f),
            onValueChange = { r ->
                if (r.endInclusive - r.start >= 0.3f)
                    vm.updateClip(c.id, "trim") { x -> x.copy(inUs = (r.start * 1e6f).toLong(), outUs = (r.endInclusive * 1e6f).toLong()) }
            },
            valueRange = 0f..(c.sourceUs / 1e6f).coerceAtLeast(0.5f),
        )
        Text("${fmtTime(c.inUs)} — ${fmtTime(c.outUs)}", fontSize = 12.sp, color = Color.Gray)
    }
    LabeledSlider("Скорость", c.speed, 0.25f..4f, "×" + String.format(Locale.US, "%.2f", c.speed)) { vm.updateClip(c.id, "speed") { x -> x.copy(speed = it) } }
    if (!c.isImage) LabeledSlider("Громкость", c.volume, 0f..2f, "${(c.volume * 100).toInt()}%") { vm.updateClip(c.id, "vol") { x -> x.copy(volume = it) } }

    Section("Кадр")
    LabeledSlider("Масштаб", c.zoom, 0.5f..3f, String.format(Locale.US, "%.2f", c.zoom)) { vm.updateClip(c.id, "zoom") { x -> x.copy(zoom = it) } }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton({ vm.updateClip(c.id) { x -> x.copy(rotation = (x.rotation + 90f) % 360f) } }) { Text("⟳ 90°") }
        Text("${c.rotation.toInt()}°", fontSize = 12.sp)
    }
    SwitchRow("Зеркало", c.mirror) { v -> vm.updateClip(c.id) { x -> x.copy(mirror = v) } }
    SwitchRow("Заполнить кадр (обрезать края)", c.fill) { v -> vm.updateClip(c.id) { x -> x.copy(fill = v) } }
    SwitchRow("Плавный наезд (Ken Burns)", c.kenBurns) { v -> vm.updateClip(c.id) { x -> x.copy(kenBurns = v) } }

    Section("Цвет")
    LabeledSlider("Яркость", c.brightness, -0.5f..0.5f, String.format(Locale.US, "%.2f", c.brightness)) { vm.updateClip(c.id, "bri") { x -> x.copy(brightness = it) } }
    LabeledSlider("Контраст", c.contrast, 0.5f..1.5f, String.format(Locale.US, "%.2f", c.contrast)) { vm.updateClip(c.id, "con") { x -> x.copy(contrast = it) } }
    LabeledSlider("Насыщенность", c.saturation, 0f..2f, String.format(Locale.US, "%.2f", c.saturation)) { vm.updateClip(c.id, "sat") { x -> x.copy(saturation = it) } }
}

@Composable
private fun TextPanel(vm: EditorViewModel) {
    Button(onClick = { vm.addText() }) { Text("＋ Добавить текст в позиции маркера") }
    val t = vm.selectedText
    if (t == null) { Spacer(Modifier.size(8.dp)); Hint("Выберите текстовый элемент (T) на таймлайне."); return }
    OutlinedTextField(t.text, { v -> vm.updateText(t.id, "txt") { it.copy(text = v) } }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Текст") })

    Section("Шрифт (${Library.fonts.size})")
    Library.version // подписка на обновления библиотеки
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Library.fonts.forEach { f ->
            val tf = remember(f.id) { Library.typeface(f.id) }
            FilterChip(selected = t.font == f.id, onClick = { vm.updateText(t.id) { it.copy(font = f.id) } },
                label = { Text(f.name, fontFamily = FontFamily(tf)) })
        }
    }
    Section("Цвет текста")
    ColorRow(t.color) { c -> vm.updateText(t.id) { it.copy(color = c) } }
    Section("Цвет обводки")
    ColorRow(t.strokeColor) { c -> vm.updateText(t.id) { it.copy(strokeColor = c) } }
    Section("Фон плашки")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ vm.updateText(t.id) { it.copy(bgColor = 0) } }) { Text("Нет") }
        COLORS.forEach { c ->
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).background(Color(c)).clickable { vm.updateText(t.id) { it.copy(bgColor = (c and 0x00FFFFFF) or 0xAA000000.toInt()) } })
        }
    }
    LabeledSlider("Размер", t.sizeFrac, 0.02f..0.3f, "${(t.sizeFrac * 100).toInt()}") { vm.updateText(t.id, "size") { x -> x.copy(sizeFrac = it) } }
    SwitchRow("Обводка", t.stroke) { v -> vm.updateText(t.id) { it.copy(stroke = v) } }
    SwitchRow("Жирный", t.bold) { v -> vm.updateText(t.id) { it.copy(bold = v) } }
    SwitchRow("Тень", t.shadow) { v -> vm.updateText(t.id) { it.copy(shadow = v) } }
    LabeledSlider("Позиция X", t.x, 0f..1f, String.format(Locale.US, "%.2f", t.x)) { vm.updateText(t.id, "x") { x -> x.copy(x = it) } }
    LabeledSlider("Позиция Y", t.y, 0f..1f, String.format(Locale.US, "%.2f", t.y)) { vm.updateText(t.id, "y") { x -> x.copy(y = it) } }
    Section("Анимация")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TEXT_ANIMS.forEach { (id, name) -> FilterChip(selected = t.anim == id, onClick = { vm.updateText(t.id) { it.copy(anim = id) } }, label = { Text(name) }) }
    }
    val tot = vm.total.coerceAtLeast(1_000_000L)
    LabeledSlider("Начало", t.startUs / 1e6f, 0f..(tot / 1e6f), fmtTime(t.startUs).drop(3)) { vm.updateText(t.id, "st") { x -> x.copy(startUs = (it * 1e6f).toLong()) }; }
    LabeledSlider("Длительность", t.durUs / 1e6f, 0.3f..30f, String.format(Locale.US, "%.1f с", t.durUs / 1e6f)) { vm.updateText(t.id, "du") { x -> x.copy(durUs = (it * 1e6f).toLong()) } }
    OutlinedButton({ vm.deleteText(t.id) }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF6B6B))) { Text("Удалить текст") }
}

@Composable
private fun TransitionPanel(vm: EditorViewModel) {
    Library.version
    val c = vm.selectedClip
    val idx = if (c == null) -1 else vm.project.clips.indexOf(c)
    if (c == null || idx <= 0) { Hint("Выберите клип (не первый) — переход применится между ним и предыдущим. Можно нажать ◇ на таймлайне."); return }
    Text("Переход перед выбранным клипом", fontSize = 13.sp)
    LabeledSlider("Длительность", c.transitionUs / 1e6f, 0.2f..2.5f, String.format(Locale.US, "%.1f с", c.transitionUs / 1e6f)) { vm.updateClip(c.id, "tdur") { x -> x.copy(transitionUs = (it * 1e6f).toLong()) } }
    OutlinedButton({
        vm.update { p -> p.copy(clips = p.clips.mapIndexed { i, x -> if (i == 0) x else x.copy(transition = c.transition, transitionUs = c.transitionUs) }) }
    }) { Text("Применить ко всем стыкам") }
    Section("Переходы (${Library.transitions.size - 1})")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Library.transitions.forEach { t ->
            FilterChip(selected = c.transition == t.id, onClick = { vm.setTransition(c.id, t.id) },
                label = { Text(t.name + if (t.pack != null) " ·${t.pack}" else "") })
        }
    }
}

@Composable
private fun SoundPanel(vm: EditorViewModel, onPickMusic: () -> Unit) {
    Library.version
    var picked by remember { mutableStateOf<SoundDef?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = onPickMusic) { Text("♪ Музыка из файла") }
        OutlinedButton(onClick = {
            val s = picked
            if (s == null) vm.msg("Сначала выберите звук из списка")
            else if (s.uri == null) vm.addSfx(s.id, s.name) else vm.addSoundFile(s.uri, s.name, false)
        }) { Text("＋ Звук в позицию маркера") }
    }
    Hint("Нажмите на эффект, чтобы прослушать и выбрать.")
    Library.sounds.groupBy { it.pack ?: it.cat }.forEach { (cat, list) ->
        Section(cat)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            list.forEach { s -> FilterChip(selected = picked?.id == s.id, onClick = { picked = s; vm.audition(s) }, label = { Text(s.name) }) }
        }
    }
    val a = vm.selectedAudio
    if (a != null) {
        Section("Выбранная дорожка: ${a.name}")
        LabeledSlider("Громкость", a.volume, 0f..2f, "${(a.volume * 100).toInt()}%") { vm.updateAudio(a.id, "av") { x -> x.copy(volume = it) } }
        LabeledSlider("Начало", a.startUs / 1e6f, 0f..(vm.total.coerceAtLeast(1_000_000L) / 1e6f), fmtTime(a.startUs).drop(3)) { vm.updateAudio(a.id, "as") { x -> x.copy(startUs = (it * 1e6f).toLong()) } }
        if (a.music) LabeledSlider("Длина", a.durUs / 1e6f, 0.5f..600f, fmtTime(a.durUs).drop(3)) { vm.updateAudio(a.id, "ad") { x -> x.copy(durUs = (it * 1e6f).toLong()) } }
        OutlinedButton({ vm.deleteAudio(a.id) }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF6B6B))) { Text("Удалить дорожку") }
    }
}

@Composable
private fun TemplatePanel(vm: EditorViewModel) {
    Library.version
    Hint("Шаблон применяется к клипам проекта: фильтр, переходы, тайминг, тексты, звуки. Отмена — ↶.")
    Spacer(Modifier.size(8.dp))
    Library.templates.forEach { t ->
        Card(
            Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { vm.applyTemplate(t.json) },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) { Text(t.name + if (t.pack != null) "   ·${t.pack}" else "", Modifier.padding(14.dp)) }
    }
}

@Composable
private fun FilterPanel(vm: EditorViewModel) {
    val c = vm.selectedClip
    if (c == null) { Hint("Выберите клип."); return }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Filters.list.forEach { (id, name) -> FilterChip(selected = c.filter == id, onClick = { vm.updateClip(c.id) { it.copy(filter = id) } }, label = { Text(name) }) }
    }
    Spacer(Modifier.size(8.dp))
    OutlinedButton({ vm.update { p -> p.copy(clips = p.clips.map { it.copy(filter = c.filter) }) } }) { Text("Применить ко всем клипам") }
}

@Composable
private fun CanvasPanel(vm: EditorViewModel) {
    Text("Соотношение сторон", fontSize = 13.sp)
    val opts = listOf("16:9" to 16f / 9f, "9:16" to 9f / 16f, "1:1" to 1f, "4:5" to 0.8f, "3:4" to 0.75f, "4:3" to 4f / 3f, "21:9" to 21f / 9f)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        opts.forEach { (n, a) -> FilterChip(selected = kotlin.math.abs(vm.project.aspect - a) < 0.01f, onClick = { vm.setAspect(a) }, label = { Text(n) }) }
    }
    Spacer(Modifier.size(8.dp))
    Hint("Для каждого клипа можно выбрать «Вписать» или «Заполнить кадр» на вкладке «Клип».")
}

@Composable
private fun PackPanel(vm: EditorViewModel, onPick: () -> Unit) {
    Library.version
    Button(onClick = onPick) { Text("＋ Установить пак (.zip)") }
    Spacer(Modifier.size(6.dp))
    Hint("Пак может содержать переходы (.glsl), шрифты (.ttf/.otf), звуки, шаблоны (.json). Формат описан в README.md проекта.")
    Section("Установленные паки")
    val packs = Library.packs
    if (packs.isEmpty()) Hint("Пока нет.")
    packs.forEach { n ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(n, Modifier.weight(1f))
            TextButton({ vm.removePack(n) }) { Text("Удалить", color = Color(0xFFFF6B6B)) }
        }
    }
    Section("Библиотека")
    Text("Переходов: ${Library.transitions.size - 1}   Шрифтов: ${Library.fonts.size}   Звуков: ${Library.sounds.size}   Шаблонов: ${Library.templates.size}", fontSize = 13.sp)
}

// ---------------------------------------------------------------------------- экспорт

@Composable
private fun ExportDialog(vm: EditorViewModel, onClose: () -> Unit) {
    var res by remember { mutableIntStateOf(1080) }
    var fps by remember { mutableIntStateOf(60) }
    var hevc by remember { mutableStateOf(false) }
    var quality by remember { mutableIntStateOf(1) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Экспорт видео") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Разрешение", fontSize = 13.sp, color = Color.Gray)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(720 to "720p", 1080 to "1080p", 1440 to "2K", 2160 to "4K").forEach { (v, n) ->
                        FilterChip(res == v, { res = v }, { Text(n) })
                    }
                }
                Text("Кадров в секунду", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(24, 30, 48, 60, 90, 120).forEach { v -> FilterChip(fps == v, { fps = v }, { Text("$v") }) }
                }
                Text("Кодек", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(!hevc, { hevc = false }, { Text("H.264 (совместимо)") })
                    FilterChip(hevc, { hevc = true }, { Text("H.265 / HEVC (меньше размер)") })
                }
                Text("Качество", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Обычное", "Высокое", "Максимум").forEachIndexed { i, n -> FilterChip(quality == i, { quality = i }, { Text(n) }) }
                }
                Text("Если телефон не умеет выбранные параметры, приложение автоматически подберёт ближайшие и покажет итог.",
                    fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 10.dp))
            }
        },
        confirmButton = { TextButton({ onClose(); vm.startExport(Exporter.Settings(res, fps, hevc, quality)) }) { Text("Экспортировать") } },
        dismissButton = { TextButton(onClose) { Text("Отмена") } },
    )
}
