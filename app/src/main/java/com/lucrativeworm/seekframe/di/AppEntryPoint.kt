package com.lucrativeworm.seekframe.di

import com.lucrativeworm.seekframe.ml.Indexer
import com.lucrativeworm.seekframe.ml.ModelStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * For the few places Hilt can't inject into directly: a manifest-declared BroadcastReceiver
 * written in Kotlin, and instrumented tests that inspect the real app's graph.
 * Use with `EntryPointAccessors.fromApplication(context, AppEntryPoint::class.java)`.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppEntryPoint {
    fun indexer(): Indexer
    fun models(): ModelStore
}
