package com.lucrativeworm.aigallery.testing

import com.lucrativeworm.aigallery.data.Db
import com.lucrativeworm.aigallery.data.EMBEDDING_DIMS
import com.lucrativeworm.aigallery.data.MyObjectBox
import io.objectbox.BoxStore
import org.junit.rules.ExternalResource
import java.io.File
import java.nio.file.Files
import kotlin.math.sqrt

/**
 * A real ObjectBox store (desktop native library, same HNSW index as on the phone) in a temp
 * directory, installed as [Db.store] for the duration of one test.
 */
class TestDb : ExternalResource() {
    lateinit var store: BoxStore
        private set
    private lateinit var dir: File

    override fun before() {
        dir = Files.createTempDirectory("objectbox-test").toFile()
        store = MyObjectBox.builder().baseDirectory(dir).build()
        Db.useForTests(store)
    }

    override fun after() {
        store.close()
        BoxStore.deleteAllFiles(dir)
        dir.deleteRecursively()
    }
}

/** Unit vector along axis [i]. */
fun axis(i: Int): FloatArray = FloatArray(EMBEDDING_DIMS).also { it[i] = 1f }

/** Normalized weighted sum of axes, e.g. mix(0 to 0.9f, 1 to 0.436f). */
fun mix(vararg parts: Pair<Int, Float>): FloatArray {
    val v = FloatArray(EMBEDDING_DIMS)
    for ((i, w) in parts) v[i] += w
    val len = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
    for (k in v.indices) v[k] /= len
    return v
}
