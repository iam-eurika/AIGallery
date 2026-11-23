package com.lucrativeworm.seekframe.data

import android.content.Context
import io.objectbox.BoxStore
import io.objectbox.query.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

object Db {
    lateinit var store: BoxStore
        private set

    fun init(context: Context) {
        if (::store.isInitialized) return
        store = MyObjectBox.builder().androidContext(context.applicationContext).build()
    }

    /** Tests swap in a throwaway store (on the JVM or in a temp dir on a device). */
    @androidx.annotation.VisibleForTesting
    fun useForTests(testStore: BoxStore) {
        store = testStore
    }
}

/** Emits the query's results now and again after every transaction that touches its box. */
fun <T> Query<T>.asFlow(): Flow<List<T>> = callbackFlow {
    val subscription = subscribe().observer { trySend(it) }
    awaitClose { subscription.cancel() }
}
