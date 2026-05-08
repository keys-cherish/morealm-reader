package com.morealm.app.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.contributor.Contributor
import com.morealm.app.domain.contributor.ContributorRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 贡献者屏 ViewModel。
 *
 * 数据源：[ContributorRepository.load]，进程级单例缓存，无需手动刷新。
 *
 * 排序策略：按 [Contributor.joinedAt] 升序（先加入的在前）。joinedAt 留空或格式异常
 * 的条目排到最后 —— 不影响展示，但提示维护者补字段。
 *
 * UI 状态：[contributors] 只暴露列表本身；空列表 = "还没人加入"，UI 显示占位文案。
 */
@HiltViewModel
class ContributorsViewModel @Inject constructor(
    private val repo: ContributorRepository,
) : ViewModel() {

    private val _contributors = MutableStateFlow<List<Contributor>>(emptyList())
    val contributors: StateFlow<List<Contributor>> = _contributors.asStateFlow()

    init {
        viewModelScope.launch {
            _contributors.value = repo.load().sortedWith(joinedAtComparator)
        }
    }

    private companion object {
        // 空 / 非法 joinedAt 排到尾部，避免它们因字典序意外冒头。
        val joinedAtComparator = compareBy<Contributor> { it.joinedAt.isBlank() }
            .thenBy { it.joinedAt }
    }
}
