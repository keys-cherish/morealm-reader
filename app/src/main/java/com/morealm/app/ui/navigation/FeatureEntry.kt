package com.morealm.app.ui.navigation

import androidx.navigation.NavGraphBuilder
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Feature 提供给注册表的导航动作面板。
 *
 * 封装 AppNavHost 内部的 safeNavigate / safePopBackStack（含防抖与生命周期守护），
 * feature 侧不直接持有 NavController，避免绕过守护逻辑。
 */
interface FeatureNav {
    fun navigate(route: String)
    fun back()
}

/**
 * 「Feature 即插件」注册入口：每个 feature 自己声明路由挂载，App 壳层只遍历注册。
 *
 * 新增页面 = 实现本接口 + 一个 Hilt `@Binds @IntoSet` 绑定，不再改 AppNavHost；
 * 删除页面 = 删实现删绑定，编译器兜底找残留调用点。
 */
interface FeatureEntry {
    fun NavGraphBuilder.register(nav: FeatureNav)
}

/** Compose 侧无法构造注入，走 EntryPoint 从 application 组件取全部注册项。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface FeatureEntriesEntryPoint {
    fun featureEntries(): Set<@JvmSuppressWildcards FeatureEntry>
}
