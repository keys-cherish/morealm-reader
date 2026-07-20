package com.morealm.app.ui.profile

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.morealm.app.ui.navigation.FeatureEntry
import com.morealm.app.ui.navigation.FeatureNav
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject

/** 更新日志页注册（FeatureEntry 样板 1：纯叶子路由）。 */
class ChangelogFeature @Inject constructor() : FeatureEntry {
    override fun NavGraphBuilder.register(nav: FeatureNav) {
        composable("changelog") {
            ChangelogScreen(onBack = { nav.back() })
        }
    }
}

/** 贡献者页注册（样板 2）。 */
class ContributorsFeature @Inject constructor() : FeatureEntry {
    override fun NavGraphBuilder.register(nav: FeatureNav) {
        composable("contributors") {
            ContributorsScreen(onBack = { nav.back() })
        }
    }
}

/** 捐赠页注册（样板 3）。 */
class DonateFeature @Inject constructor() : FeatureEntry {
    override fun NavGraphBuilder.register(nav: FeatureNav) {
        composable("donate") {
            DonateScreen(onBack = { nav.back() })
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ProfileFeatureModule {
    @Binds @IntoSet abstract fun changelog(impl: ChangelogFeature): FeatureEntry
    @Binds @IntoSet abstract fun contributors(impl: ContributorsFeature): FeatureEntry
    @Binds @IntoSet abstract fun donate(impl: DonateFeature): FeatureEntry
}
