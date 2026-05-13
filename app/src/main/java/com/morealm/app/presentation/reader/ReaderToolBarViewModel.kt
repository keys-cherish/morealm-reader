package com.morealm.app.presentation.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.entity.ReaderTool
import com.morealm.app.domain.entity.ReaderToolLayout
import com.morealm.app.domain.entity.ReaderToolZone
import com.morealm.app.domain.preference.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReaderToolBarViewModel @Inject constructor(
    private val prefs: AppPreferences,
) : ViewModel() {

    private val _layout = MutableStateFlow(ReaderToolLayout.Default)
    val layout: StateFlow<ReaderToolLayout> = _layout.asStateFlow()

    private val _editing = MutableStateFlow(false)
    val editing: StateFlow<Boolean> = _editing.asStateFlow()

    private val _markedHide = MutableStateFlow<Set<ReaderTool>>(emptySet())
    val markedHide: StateFlow<Set<ReaderTool>> = _markedHide.asStateFlow()

    val guideSeen: StateFlow<Boolean> = prefs.readerToolbarEditGuideSeen
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        viewModelScope.launch {
            val json = prefs.readerToolbarLayout.first()
            _layout.value = ReaderToolLayout.fromJson(json)
        }
    }

    fun enterEditMode() {
        _markedHide.value = emptySet()
        _editing.value = true
    }

    fun exitEditMode() {
        val hidden = _markedHide.value
        if (hidden.isNotEmpty()) {
            val current = _layout.value
            val newZones = current.zones.toMutableMap()
            hidden.forEach { tool -> newZones[tool] = ReaderToolZone.Hidden }
            _layout.value = current.copy(zones = newZones)
        }
        _markedHide.value = emptySet()
        _editing.value = false
        persist()
    }

    fun markGuideSeen() {
        viewModelScope.launch { prefs.setReaderToolbarEditGuideSeen(true) }
    }

    fun moveTool(tool: ReaderTool, toZone: ReaderToolZone) {
        if (!tool.removable && toZone == ReaderToolZone.Hidden) return
        val current = _layout.value
        val newZones = current.zones.toMutableMap().apply { this[tool] = toZone }
        _layout.value = current.copy(zones = newZones)
    }

    fun reorder(zone: ReaderToolZone, fromIndex: Int, toIndex: Int) {
        val current = _layout.value
        val zoneTools = current.toolsIn(zone).toMutableList()
        if (fromIndex !in zoneTools.indices || toIndex !in zoneTools.indices) return
        val moved = zoneTools.removeAt(fromIndex)
        zoneTools.add(toIndex, moved)

        val otherTools = current.order.filter { current.zones[it] != zone }
        val newOrder = when (zone) {
            ReaderToolZone.Top -> zoneTools + otherTools
            ReaderToolZone.Bottom -> {
                val topTools = current.order.filter { current.zones[it] == ReaderToolZone.Top }
                val hiddenTools = current.order.filter { current.zones[it] == ReaderToolZone.Hidden }
                topTools + zoneTools + hiddenTools
            }
            ReaderToolZone.Hidden -> {
                val topTools = current.order.filter { current.zones[it] == ReaderToolZone.Top }
                val bottomTools = current.order.filter { current.zones[it] == ReaderToolZone.Bottom }
                topTools + bottomTools + zoneTools
            }
        }
        _layout.value = current.copy(order = newOrder)
    }

    fun toggleToolVisibility(tool: ReaderTool) {
        if (!tool.removable) return
        val currentZone = _layout.value.zones[tool] ?: tool.defaultZone
        if (currentZone == ReaderToolZone.Hidden) {
            // Hidden 的工具点加号 → 直接移回 Bottom
            moveTool(tool, ReaderToolZone.Bottom)
        } else {
            // Bottom 的工具点减号/加号 → 切换 markedHide
            val current = _markedHide.value.toMutableSet()
            if (tool in current) {
                current.remove(tool)
            } else {
                current.add(tool)
            }
            _markedHide.value = current
        }
    }

    fun resetToDefault() {
        _layout.value = ReaderToolLayout.Default
        persist()
    }

    private fun persist() {
        viewModelScope.launch {
            prefs.setReaderToolbarLayout(_layout.value.toJson())
        }
    }
}
