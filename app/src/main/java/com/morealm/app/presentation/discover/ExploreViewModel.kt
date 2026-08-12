package com.morealm.app.presentation.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.db.ExploreSourcePart
import com.morealm.app.domain.entity.rule.ExploreKind
import com.morealm.app.domain.repository.SourceRepository
import com.morealm.app.domain.source.clearExploreKindsCache
import com.morealm.app.domain.source.exploreKinds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 发现页「分类浏览」视图模型（对照参照实现 ExploreFragment + ExploreAdapter 逻辑）。
 *
 * 数据流：
 *  - [sources]：启用 + 有发现规则的书源投影，按 customOrder，叠加分组 / 关键字过滤；
 *  - [groups]：从书源 bookSourceGroup 拆分出的去重分组 chips；
 *  - [expanded]：当前展开的源及其分类列表（懒加载，见 [toggleExpand]）。
 */
@HiltViewModel
class ExploreViewModel @Inject constructor(
    private val sourceRepository: SourceRepository,
) : ViewModel() {

    /** 展开源的分类加载状态。 */
    data class ExpandedSource(
        val sourceUrl: String,
        val kinds: List<ExploreKind> = emptyList(),
        val loading: Boolean = true,
    )

    private val groupSplitRegex = Regex("[,;，；]")

    private val allSources: StateFlow<List<ExploreSourcePart>> =
        sourceRepository.observeExploreSources()
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedGroup = MutableStateFlow<String?>(null)
    val selectedGroup: StateFlow<String?> = _selectedGroup.asStateFlow()

    private val _expanded = MutableStateFlow<ExpandedSource?>(null)
    val expanded: StateFlow<ExpandedSource?> = _expanded.asStateFlow()

    val groups: StateFlow<List<String>> = allSources
        .map { sources ->
            sources.asSequence()
                .mapNotNull { it.bookSourceGroup }
                .flatMap { it.split(groupSplitRegex) }
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .toList()
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sources: StateFlow<List<ExploreSourcePart>> =
        combine(allSources, _searchQuery, _selectedGroup) { sources, query, group ->
            sources.filter { part ->
                val groupMatch = group == null || part.bookSourceGroup
                    ?.split(groupSplitRegex)
                    ?.any { it.trim() == group } == true
                val queryMatch = query.isBlank() ||
                    part.bookSourceName.contains(query, ignoreCase = true) ||
                    part.bookSourceGroup?.contains(query, ignoreCase = true) == true
                groupMatch && queryMatch
            }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var kindsJob: Job? = null

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectGroup(group: String?) {
        _selectedGroup.value = group
    }

    /** 点击源行：展开（懒加载分类）或收起。 */
    fun toggleExpand(sourceUrl: String) {
        if (_expanded.value?.sourceUrl == sourceUrl) {
            collapse()
            return
        }
        loadKinds(sourceUrl)
    }

    fun collapse() {
        kindsJob?.cancel()
        _expanded.value = null
    }

    /** 长按菜单「刷新分类」：清缓存后重新解析（JS 源会重新求值）。 */
    fun refreshKinds(sourceUrl: String) {
        loadKinds(sourceUrl, clearCacheFirst = true)
    }

    /** 长按菜单「置顶」。 */
    fun moveToTop(sourceUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            sourceRepository.moveSourceToTop(sourceUrl)
        }
    }

    /** 长按菜单「从发现隐藏」：仅关 enabledExplore，源在搜索里仍然可用。 */
    fun hideFromExplore(sourceUrl: String) {
        if (_expanded.value?.sourceUrl == sourceUrl) collapse()
        viewModelScope.launch(Dispatchers.IO) {
            sourceRepository.disableExplore(sourceUrl)
        }
    }

    private fun loadKinds(sourceUrl: String, clearCacheFirst: Boolean = false) {
        kindsJob?.cancel()
        _expanded.value = ExpandedSource(sourceUrl = sourceUrl, loading = true)
        kindsJob = viewModelScope.launch(Dispatchers.IO) {
            val kinds = try {
                val source = sourceRepository.getByUrl(sourceUrl)
                if (source == null) {
                    emptyList()
                } else {
                    if (clearCacheFirst) source.clearExploreKindsCache()
                    source.exploreKinds()
                }
            } catch (e: Exception) {
                AppLog.warn("Explore", "load kinds failed for $sourceUrl: ${e.message}")
                emptyList()
            }
            // 用户可能在加载期间切换到别的源；只有仍展开同一源时才回填。
            if (_expanded.value?.sourceUrl == sourceUrl) {
                _expanded.value = ExpandedSource(sourceUrl = sourceUrl, kinds = kinds, loading = false)
            }
        }
    }
}
