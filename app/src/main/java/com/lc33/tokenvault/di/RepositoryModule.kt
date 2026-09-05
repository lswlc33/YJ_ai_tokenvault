package com.lc33.tokenvault.di

import com.lc33.tokenvault.data.repo.RoomApiKeyRepository
import com.lc33.tokenvault.data.repo.RoomGroupRepository
import com.lc33.tokenvault.data.repo.RoomProviderRepository
import com.lc33.tokenvault.data.repo.RoomTransactionRunner
import com.lc33.tokenvault.data.repo.TransactionRunner
import com.lc33.tokenvault.domain.repo.ApiKeyRepository
import com.lc33.tokenvault.domain.repo.GroupRepository
import com.lc33.tokenvault.domain.repo.ProviderRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 仓库绑定。
 *
 * 用 `@Binds` 而不是 `@Provides`：这样 ViewModel 注入的类型只能是 `domain/repo/` 里的接口，
 * Room 实现类的名字在 ViewModel 那一侧连出现的机会都没有（CLAUDE.md：ViewModel 只依赖接口）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindTransactionRunner(impl: RoomTransactionRunner): TransactionRunner

    @Binds
    @Singleton
    abstract fun bindGroupRepository(impl: RoomGroupRepository): GroupRepository

    @Binds
    @Singleton
    abstract fun bindProviderRepository(impl: RoomProviderRepository): ProviderRepository

    @Binds
    @Singleton
    abstract fun bindApiKeyRepository(impl: RoomApiKeyRepository): ApiKeyRepository
}
