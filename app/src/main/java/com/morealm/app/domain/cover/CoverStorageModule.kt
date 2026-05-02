package com.morealm.app.domain.cover

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 把 [CoverStorage] 接口绑到 [DefaultCoverStorage] 实现。
 * 业务层通过构造注入 `CoverStorage` 即可，不依赖具体实现类。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CoverStorageModule {
    @Binds
    @Singleton
    abstract fun bindCoverStorage(impl: DefaultCoverStorage): CoverStorage
}
