package com.lucrativeworm.seekframe.ml

import com.lucrativeworm.seekframe.data.AudioText
import com.lucrativeworm.seekframe.data.MomentKind
import com.lucrativeworm.seekframe.data.Photo
import com.lucrativeworm.seekframe.data.PhotoVector
import com.lucrativeworm.seekframe.data.VideoMoment
import com.lucrativeworm.seekframe.data.removeVectorsOf
import com.lucrativeworm.seekframe.testing.TestDb
import com.lucrativeworm.seekframe.testing.axis
import com.lucrativeworm.seekframe.testing.mix
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.objectbox.kotlin.boxFor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Search against a real ObjectBox HNSW index with hand-made vectors, so the ranking, banding,
 * video-moment and sound logic run exactly as on the phone; only the model is faked.
 *
 * Runs under Robolectric only so it shares one classloader with the other ObjectBox tests: the
 * JVM refuses to load the same native library into two classloaders.
 */
@RunWith(RobolectricTestRunner::class)
class SearchEngineIntegrationTest {

    @get:Rule val db = TestDb()

    private val embedder = mockk<Embedder>()
    private lateinit var engine: SearchEngine

    // Library: A along axis 0, B close to A, C unrelated, D halfway between A and C, V a video.
    private var a = 0L; private var b = 0L; private var c = 0L; private var d = 0L; private var v = 0L

    @Before
    fun seed() {
        val photos = db.store.boxFor<Photo>()
        a = photos.put(Photo(uri = "content://a", dateTaken = 5))
        b = photos.put(Photo(uri = "content://b", dateTaken = 4))
        c = photos.put(Photo(uri = "content://c", dateTaken = 3))
        d = photos.put(Photo(uri = "content://d", dateTaken = 2))
        v = photos.put(Photo(uri = "content://v", dateTaken = 1, isVideo = true, durationMs = 20_000))

        db.store.boxFor<PhotoVector>().put(
            listOf(
                PhotoVector(photoId = a, vector = axis(0), model = "m"),
                PhotoVector(photoId = b, vector = mix(0 to 0.9f, 1 to 0.436f), model = "m"),
                PhotoVector(photoId = c, vector = axis(1), model = "m"),
                PhotoVector(photoId = d, vector = mix(0 to 1f, 1 to 1f), model = "m"),
                PhotoVector(photoId = v, vector = axis(3), model = "m"), // poster
            )
        )
        db.store.boxFor<VideoMoment>().put(
            listOf(
                VideoMoment(photoId = v, kind = MomentKind.FRAME, startMs = 4_000, endMs = 6_000, vector = axis(2), model = "m"),
                VideoMoment(photoId = v, kind = MomentKind.FRAME, startMs = 12_000, endMs = 14_000, vector = mix(2 to 0.95f, 5 to 0.31f), model = "m"),
                VideoMoment(photoId = v, kind = MomentKind.SOUND, startMs = 8_000, endMs = 13_000, vector = axis(4), model = "m"),
            )
        )
        engine = SearchEngine(db.store, embedder)
    }

    private fun List<Hit>.ids() = map { it.photo.id }

    @Test
    fun `text search keeps only matches within the band of the best`() = runTest {
        coEvery { embedder.embedText("dog") } returns axis(0)
        val hits = engine.search("  dog  ")
        // A is exact (1.0); B is 0.9, inside the 0.11 text band; D (0.71) and C (0) are not.
        assertEquals(listOf(a, b), hits.ids())
        assertEquals(1f, hits[0].similarity, 1e-3f)
        assertNull(hits[0].atMs)
        coVerify { embedder.embedText("dog") }
    }

    @Test
    fun `similar photos exclude the photo itself`() = runTest {
        val hits = engine.similarTo(a)
        assertFalse(a in hits.ids())
        assertEquals(b, hits.first().photo.id)
        assertTrue("C is unrelated", c !in hits.ids())
    }

    @Test
    fun `similar to an unindexed photo is empty`() = runTest {
        val fresh = db.store.boxFor<Photo>().put(Photo(uri = "content://new"))
        assertTrue(engine.similarTo(fresh).isEmpty())
        assertNull(engine.storedVector(fresh))
        assertArrayEquals(axis(0), engine.storedVector(a), 0f)
    }

    @Test
    fun `a match inside a video points at the moment it was seen`() = runTest {
        coEvery { embedder.embedText(any()) } returns axis(2)
        val hit = engine.search("sunset").single()
        assertEquals(v, hit.photo.id)
        assertEquals(4_000L, hit.atMs)
        assertEquals(listOf(4_000L, 12_000L), hit.marks)
        assertFalse(hit.heard)
    }

    @Test
    fun `a sound match is reported as heard at its time`() = runTest {
        coEvery { embedder.embedText(any()) } returns mix(4 to 1f, 0 to 0.5f)
        val hits = engine.search("applause")
        val video = hits.single { it.photo.id == v }
        assertTrue(video.heard)
        assertEquals(8_000L, video.atMs)
        assertEquals(listOf(8_000L), video.marks)
        assertTrue(c !in hits.ids())
    }

    @Test
    fun `image searches never include sounds`() = runTest {
        // Searching by the sound vector as an image: only pictures compete, and none match well,
        // so whatever comes back must not claim to be heard.
        val hits = engine.searchMixed(listOf(axis(4)), "", exclude = emptySet())
        assertTrue(hits.none { it.heard })
    }

    @Test
    fun `mixed search with nothing is empty and never embeds`() = runTest {
        assertTrue(engine.searchMixed(emptyList(), "   ", emptySet()).isEmpty())
        coVerify(exactly = 0) { embedder.embedText(any()) }
    }

    @Test
    fun `mixed search with only photos excludes the attached ones`() = runTest {
        val hits = engine.searchMixed(listOf(axis(0)), "", exclude = setOf(a))
        assertEquals(b, hits.first().photo.id)
        assertFalse(a in hits.ids())
    }

    @Test
    fun `photo plus words finds what is near both`() = runTest {
        coEvery { embedder.embedText("at the beach") } returns axis(1)
        // A and C are each perfect for one half only and are attached/excluded; D sits between.
        val hits = engine.searchMixed(listOf(axis(0)), "at the beach", exclude = setOf(a, c))
        assertEquals(listOf(d), hits.ids())
        assertEquals(0.707f, hits[0].similarity, 1e-2f)
    }

    @Test
    fun `forgetting vectors removes a photo from search`() = runTest {
        db.store.runInTx { removeVectorsOf(longArrayOf(b, v)) }
        coEvery { embedder.embedText(any()) } returns axis(0)
        assertEquals(listOf(a), engine.search("dog").ids())
        assertEquals(0, db.store.boxFor<VideoMoment>().count())
        coEvery { embedder.embedText(any()) } returns axis(2)
        assertTrue(engine.search("sunset").none { it.photo.id == v })
    }

    @Test
    fun `words said in a video rank first and point at when they were said`() = runTest {
        db.store.boxFor<AudioText>().put(
            listOf(
                AudioText(photoId = v, startMs = 15_000, endMs = 18_000, text = "Happy birthday to you!"),
                AudioText(photoId = c, startMs = 0, endMs = 2_000, text = "the birthday was happy"),
                AudioText(photoId = d, startMs = 0, endMs = 2_000, text = "happily catching birthdays"),
            )
        )
        coEvery { embedder.embedText(any()) } returns axis(0)
        val hits = engine.search("happy birthday")
        // Exact phrase first, then all words in any order; A (best by meaning) follows; D only
        // contains the words inside longer ones, so it's not a spoken match.
        assertEquals(listOf(v, c, a), hits.ids().take(3))
        assertEquals(15_000L, hits.first().atMs)
        assertTrue(hits.first().heard)
        assertFalse(hits.take(3).any { it.photo.id == d })
    }
}
