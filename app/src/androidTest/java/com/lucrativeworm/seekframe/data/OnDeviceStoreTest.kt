package com.lucrativeworm.seekframe.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lucrativeworm.seekframe.ml.Embedder
import com.lucrativeworm.seekframe.ml.SearchEngine
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Android ObjectBox build (the one that ships) with the HNSW vector index, live queries,
 * and image-to-image search, in a throwaway store so the app's own library is untouched.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dir: File
    private lateinit var store: BoxStore
    private var appStore: BoxStore? = null

    @Before
    fun open() {
        // The app process already opened its real store; keep it to put back afterwards.
        appStore = runCatching { Db.store }.getOrNull()
        dir = File(context.cacheDir, "objectbox-test-${System.nanoTime()}")
        store = MyObjectBox.builder().androidContext(context).baseDirectory(dir).build()
        Db.useForTests(store)
    }

    @After
    fun close() {
        appStore?.let(Db::useForTests)
        store.close()
        BoxStore.deleteAllFiles(dir)
    }

    private fun vec(vararg parts: Pair<Int, Float>) = FloatArray(EMBEDDING_DIMS).also { v ->
        parts.forEach { (i, w) -> v[i] = w }
        val len = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        for (k in v.indices) v[k] /= len
    }

    @Test
    fun similarPhotosComeFromTheVectorIndex() = runBlocking {
        val photos = store.boxFor<Photo>()
        val beach = photos.put(Photo(uri = "content://beach"))
        val beach2 = photos.put(Photo(uri = "content://beach2"))
        val receipt = photos.put(Photo(uri = "content://receipt"))
        store.boxFor<PhotoVector>().put(
            listOf(
                PhotoVector(photoId = beach, vector = vec(0 to 1f), model = "m"),
                PhotoVector(photoId = beach2, vector = vec(0 to 0.95f, 1 to 0.3f), model = "m"),
                PhotoVector(photoId = receipt, vector = vec(7 to 1f), model = "m"),
            )
        )
        // The embedder isn't touched by "similar", so no model has to be downloaded.
        val hits = SearchEngine(Embedder(context)).similarTo(beach)
        assertEquals(listOf(beach2), hits.map { it.photo.id })
    }

    @Test
    fun liveQueriesEmitAfterEveryWrite() = runBlocking {
        val photos = store.boxFor<Photo>()
        val flow = photos.query(Photo_.indexState.equal(IndexState.PENDING.toLong())).build().asFlow()
        assertTrue(flow.first().isEmpty())
        photos.put(Photo(uri = "content://new"))
        val seen = withTimeout(5_000) { flow.first { it.isNotEmpty() } }
        assertEquals("content://new", seen.single().uri)
    }

    @Test
    fun legacyNullColumnsStillMatchTheirDefaults() {
        val photos = store.boxFor<Photo>()
        photos.put(Photo(uri = "photo"), Photo(uri = "video", isVideo = true, source = Source.FOLDER))
        val stills = photos.query(Videos.notVideo()).build().use { it.find() }.map { it.uri }
        val library = photos.query(Source.isMediaStore()).build().use { it.find() }.map { it.uri }
        assertEquals(listOf("photo"), stills)
        assertEquals(listOf("photo"), library)
    }
}
