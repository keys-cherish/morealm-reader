package com.morealm.app.ui.reader.annotation

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import com.morealm.app.domain.annotation.HandwrittenNote
import com.morealm.app.domain.annotation.InkStroke

/** Keep unsaved ink and its page through configuration changes without large saved-state bundles. */
internal class EditableNote(val note: HandwrittenNote, val page: Bitmap, val isNew: Boolean) {
    val strokes = mutableStateOf(note.strokes)
    val undo = mutableStateListOf<List<InkStroke>>()
    val redo = mutableStateListOf<List<InkStroke>>()
}

internal class HandwritingSessionViewModel : ViewModel() {
    val editor = mutableStateOf<EditableNote?>(null)
    val notes = mutableStateOf<List<HandwrittenNote>?>(null)
}
