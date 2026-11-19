package com.lucrativeworm.aigallery.ml

import com.lucrativeworm.aigallery.data.IndexState
import com.lucrativeworm.aigallery.data.Photo
import com.lucrativeworm.aigallery.data.requeue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValueTypesTest {

    @Test
    fun `progress fraction and remaining`() {
        assertEquals(1f, Indexer.Progress(0, 0).fraction, 0f)
        assertEquals(0.25f, Indexer.Progress(1, 4).fraction, 1e-6f)
        assertEquals(3, Indexer.Progress(1, 4).remaining)
        assertEquals(0, Indexer.Progress(4, 4).remaining)
    }

    @Test
    fun `download fraction is clamped and safe with unknown total`() {
        assertEquals(0f, ModelStore.State.Downloading(10, 0).fraction, 0f)
        assertEquals(0.5f, ModelStore.State.Downloading(50, 100).fraction, 1e-6f)
        assertEquals(1f, ModelStore.State.Downloading(150, 100).fraction, 0f)
    }

    @Test
    fun `variants point at their hugging face files`() {
        for (v in Variant.entries) {
            assertTrue(v.url.startsWith("https://huggingface.co/litert-community/${v.repo}/"))
            assertTrue(v.url.endsWith("/${v.fileName}"))
            assertEquals(64, v.sha256.length)
        }
        assertTrue(Variant.FULL.audio)
        assertFalse(Variant.VISION.audio)
        assertTrue(Variant.FULL.approxMb > Variant.VISION.approxMb)
    }

    @Test
    fun `requeue resets every piece of index progress`() {
        val p = Photo(
            indexState = IndexState.DONE, framesDoneMs = 5_000,
            soundState = IndexState.FAILED, soundDoneMs = 9_000, isVideo = true,
        )
        p.requeue()
        assertEquals(IndexState.PENDING, p.indexState)
        assertEquals(0L, p.framesDoneMs)
        assertEquals(IndexState.PENDING, p.soundState)
        assertEquals(0L, p.soundDoneMs)
        assertTrue("other fields are untouched", p.isVideo)
    }

    @Test
    fun `video dot product rejects missing or mismatched vectors`() {
        assertEquals(-1f, VideoIndexer.dot(null, floatArrayOf(1f)), 0f)
        assertEquals(-1f, VideoIndexer.dot(floatArrayOf(1f, 2f), floatArrayOf(1f)), 0f)
        assertEquals(11f, VideoIndexer.dot(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f)), 0f)
    }
}
