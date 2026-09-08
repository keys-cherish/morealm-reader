package com.morealm.app.ui.reader.annotation

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.morealm.app.domain.annotation.HandwrittenNote
import com.morealm.app.domain.storage.HandwritingRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class HandwritingActions(val create: () -> Unit, val library: () -> Unit)

/** A frozen page prevents saved handwriting from moving onto different words after reflow. */
@Composable
internal fun rememberHandwritingFeature(
    bookId: String,
    chapterIndex: Int,
    chapterId: String,
    chapterPosition: Int,
    chapterTitle: String,
    hideControls: () -> Unit,
    onOpenAnchor: (HandwrittenNote) -> Unit,
): HandwritingActions {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val repository = remember { HandwritingRepository(File(context.filesDir, "handwriting")) }
    val session = androidx.lifecycle.viewmodel.compose.viewModel<HandwritingSessionViewModel>(key = "handwriting:$bookId")
    var editor by session.editor
    var notes by session.notes
    var deleting by remember { mutableStateOf<HandwrittenNote?>(null) }
    var opening by remember { mutableStateOf(false) }
    fun reportFailure(e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Toast.makeText(context, e.message ?: "无法打开批注", Toast.LENGTH_LONG).show() }

    editor?.let { document ->
        HandwritingEditor(document,
            onSave = { repository.save(it, if (document.isNew) document.page else null) },
            onDismiss = { editor = null })
    }
    notes?.let { saved ->
        AlertDialog(onDismissRequest = { notes = null }, title = { Text("手写批注 · ${saved.size}") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    if (saved.isEmpty()) item { Text("在阅读页的更多菜单中选择“手写批注”，即可圈画和记录") }
                    items(saved, key = { it.id }) { note ->
                        ListItem(headlineContent = { Text(note.chapterTitle) },
                            supportingContent = { Text("${note.strokes.size} 笔 · 点击查看或编辑") },
                            trailingContent = { Row {
                                TextButton(onClick = { notes = null; onOpenAnchor(note) }) { Text("原文") }
                                IconButton(onClick = { deleting = note }) { Icon(Icons.Default.Delete, "删除批注") }
                            } },
                            modifier = Modifier.clickable {
                                scope.launch {
                                    try {
                                        val page = repository.page(note) ?: error("批注页面文件不存在")
                                        notes = null
                                        editor = EditableNote(note, page, false)
                                    } catch (e: Exception) { reportFailure(e) }
                                }
                            })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { notes = null }) { Text("关闭") } },
        )
    }
    deleting?.let { note ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这条批注？") },
            confirmButton = { TextButton(onClick = {
                scope.launch {
                    try { repository.delete(note); notes = repository.list(bookId); deleting = null }
                    catch (e: Exception) { reportFailure(e) }
                }
            }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
    return HandwritingActions(
        create = {
            if (!opening) {
                opening = true
                hideControls()
                scope.launch {
                    try {
                        delay(300) // Wait for the reading controls' exit animation before capturing.
                        val bitmap = capturePage(view)
                        val note = HandwrittenNote(UUID.randomUUID().toString(), bookId, chapterIndex,
                            chapterId, chapterPosition.coerceAtLeast(0), chapterTitle, System.currentTimeMillis())
                        editor = EditableNote(note, bitmap, true)
                    } catch (e: Exception) { reportFailure(e) }
                    finally { opening = false }
                }
            }
        },
        library = { hideControls(); scope.launch {
            try { notes = repository.list(bookId) } catch (e: Exception) { reportFailure(e) }
        } },
    )
}

private suspend fun capturePage(view: View): Bitmap {
    val activity = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>().firstOrNull() ?: error("暂时无法获取阅读页面")
    val decor = activity.window.decorView
    check(decor.width > 0 && decor.height > 0) { "请等待页面显示后再批注" }
    val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
        decor.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }
    return suspendCancellableCoroutine { continuation ->
        PixelCopy.request(activity.window, bitmap, { result ->
            if (result == PixelCopy.SUCCESS && continuation.isActive) continuation.resume(bitmap)
            else {
                bitmap.recycle()
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("页面截图失败，请重试"))
            }
        }, Handler(Looper.getMainLooper()))
    }
}
