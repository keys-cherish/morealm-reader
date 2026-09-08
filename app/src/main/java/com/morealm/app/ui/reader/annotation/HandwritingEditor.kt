package com.morealm.app.ui.reader.annotation

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.morealm.app.domain.annotation.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HandwritingEditor(
    document: EditableNote,
    onSave: suspend (HandwrittenNote) -> Unit,
    onDismiss: () -> Unit,
) {
    val note = document.note
    val page = document.page
    var strokes by document.strokes
    var pending by remember { mutableStateOf<List<InkPoint>>(emptyList()) }
    val undo = document.undo
    val redo = document.redo
    var erasing by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var color by remember { mutableIntStateOf(0xFFD32F2F.toInt()) }
    var saving by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var activeWidth by remember { mutableFloatStateOf(0.004f) }
    val penWidth = with(LocalDensity.current) { 2.dp.toPx() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(erasing, color, moving) { pending = emptyList() }
    val save: () -> Unit = {
        if (!saving) scope.launch {
            saving = true
            try { onSave(note.copy(strokes = strokes)); onDismiss() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "保存失败，请重试" }
            finally { saving = false }
        }
    }
    val close = { if (strokes != note.strokes) confirmLeave = true else onDismiss() }
    Dialog(onDismissRequest = { if (!saving) close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                TopAppBar(
                    title = { Text("手写批注", maxLines = 1) },
                    navigationIcon = { IconButton(onClick = close, enabled = !saving) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                    actions = { TextButton(onClick = save, enabled = !saving && pending.isEmpty()) { Text(if (saving) "保存中" else "保存") } },
                )
                Text(note.chapterTitle, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp), maxLines = 1)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = !erasing && !moving, onClick = { erasing = false; moving = false }, label = { Text("画笔") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = erasing && !moving, onClick = { erasing = true; moving = false }, label = { Text("橡皮") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = moving, onClick = { moving = true }, label = { Text("移动/缩放") })
                    if (moving) {
                        IconButton(onClick = { zoom = (zoom * 1.5f).coerceAtMost(8f); pan = Offset.Zero }) {
                            Icon(Icons.Default.ZoomIn, "放大批注页面")
                        }
                        IconButton(onClick = { zoom = (zoom / 1.5f).coerceAtLeast(1f); pan = Offset.Zero }) {
                            Icon(Icons.Default.ZoomOut, "缩小批注页面")
                        }
                        IconButton(onClick = { zoom = 1f; pan = Offset.Zero }) {
                            Icon(Icons.Default.ZoomOutMap, "适合页面")
                        }
                    }
                    IconButton(enabled = undo.isNotEmpty() && !saving, onClick = {
                        redo.add(strokes); strokes = undo.removeAt(undo.lastIndex)
                    }) { Icon(Icons.Default.Undo, "撤销") }
                    IconButton(enabled = redo.isNotEmpty() && !saving, onClick = {
                        undo.add(strokes); strokes = redo.removeAt(redo.lastIndex)
                    }) { Icon(Icons.Default.Redo, "重做") }
                    listOf("红色" to 0xFFD32F2F.toInt(), "蓝色" to 0xFF1565C0.toInt(),
                        "绿色" to 0xFF2E7D32.toInt(), "黑色" to 0xFF212121.toInt()).forEach { (name, argb) ->
                        Box(Modifier.padding(6.dp).size(28.dp).background(Color(argb), CircleShape)
                            .border(if (color == argb) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .clickable { color = argb; erasing = false; moving = false }
                            .semantics { contentDescription = "${name}画笔" })
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceContainer).clipToBounds(),
                    contentAlignment = Alignment.Center) {
                    val ratio = page.width.toFloat() / page.height
                    val canvasWidth = minOf(maxWidth, maxHeight * ratio)
                    val canvasHeight = canvasWidth / ratio
                    Box(Modifier.fillMaxSize().pointerInput(moving, canvasWidth, canvasHeight) {
                        if (moving) detectTransformGestures { centroid, delta, scale, _ ->
                            val targetZoom = (zoom * scale).coerceIn(1f, 8f)
                            val anchor = centroid - Offset(size.width / 2f, size.height / 2f)
                            val moved = (pan - anchor) * (targetZoom / zoom) + anchor + delta
                            val limitX = ((canvasWidth.toPx() * targetZoom - size.width) / 2f).coerceAtLeast(0f)
                            val limitY = ((canvasHeight.toPx() * targetZoom - size.height) / 2f).coerceAtLeast(0f)
                            pan = Offset(moved.x.coerceIn(-limitX, limitX), moved.y.coerceIn(-limitY, limitY))
                            zoom = targetZoom
                        }
                    }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(canvasWidth, canvasHeight).graphicsLayer {
                        scaleX = zoom; scaleY = zoom
                        translationX = pan.x; translationY = pan.y
                    }) {
                        Image(page.asImageBitmap(), "批注时的阅读页面", Modifier.fillMaxSize())
                        Canvas(Modifier.fillMaxSize().pointerInput(erasing, color, saving, moving) {
                            if (saving || moving) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val before = strokes
                                activeWidth = penWidth / size.width.coerceAtLeast(1)
                                fun point(position: Offset) = InkPoint(
                                    (position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                                    (position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                                )
                                fun add(position: Offset) {
                                    val p = point(position)
                                    if (erasing) strokes = strokes.filterNot { it.touches(p, 0.025f) }
                                    else pending = pending + p
                                }
                                add(down.position)
                                down.consume()
                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    add(change.position)
                                    change.consume()
                                } while (change.pressed)
                                if (!erasing && pending.isNotEmpty()) strokes = strokes + InkStroke(pending, color, activeWidth)
                                pending = emptyList()
                                if (before != strokes) {
                                    undo.add(before)
                                    if (undo.size > 40) undo.removeAt(0)
                                    redo.clear()
                                }
                            }
                        }) {
                            val all = if (pending.isEmpty()) strokes else strokes + InkStroke(pending, color, activeWidth)
                            all.forEach { stroke ->
                                val points = stroke.points
                                if (points.isEmpty()) return@forEach
                                val width = (stroke.width * size.width).coerceAtLeast(1f)
                                if (points.size == 1) {
                                    drawCircle(Color(stroke.color), width / 2, Offset(points[0].x * size.width, points[0].y * size.height))
                                } else {
                                    val path = Path().apply {
                                        moveTo(points[0].x * size.width, points[0].y * size.height)
                                        points.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) }
                                    }
                                    drawPath(path, Color(stroke.color), style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
        if (confirmLeave) AlertDialog(
            onDismissRequest = { confirmLeave = false }, title = { Text("保存批注？") },
            text = { Text("这页有尚未保存的笔迹") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; save() }) { Text("保存") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("放弃更改") } },
        )
    }
}
