package com.lucrativeworm.aigallery.ml

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lucrativeworm.aigallery.data.IndexState
import com.lucrativeworm.aigallery.data.MomentKind
import com.lucrativeworm.aigallery.data.Photo
import com.lucrativeworm.aigallery.data.PhotoVector
import com.lucrativeworm.aigallery.data.VideoMoment
import com.lucrativeworm.aigallery.testing.TestDb
import com.lucrativeworm.aigallery.testing.axis
import io.mockk.every
import io.mockk.mockk
import io.objectbox.kotlin.boxFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** The indexer's bookkeeping against a real ObjectBox store, with the model faked. */
@RunWith(RobolectricTestRunner::class)
class IndexerIntegrationTest {

    @get:Rule val db = TestDb()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val embedder = mockk<Embedder>(relaxed = true)
    private var soundsWanted = false

    private val photos get() = db.store.boxFor<Photo>()
    private val vectors get() = db.store.boxFor<PhotoVector>()
    private val moments get() = db.store.boxFor<VideoMoment>()

    @After fun tearDown() = scope.cancel()

    private fun indexer() = Indexer(context, embedder, scope, soundsWanted = { soundsWanted })

    @Test
    fun `pending count covers pictures, and soundtracks only when sound is on and possible`() {
        photos.put(
            listOf(
                Photo(uri = "p1"),
                Photo(uri = "p2", indexState = IndexState.DONE),
                Photo(uri = "v1", isVideo = true),
                Photo(uri = "v2", isVideo = true, indexState = IndexState.DONE, soundState = IndexState.PENDING),
                Photo(uri = "v3", isVideo = true, indexState = IndexState.FAILED, soundState = IndexState.PENDING),
            )
        )
        val idx = indexer()
        assertEquals(2, idx.pendingCount()) // p1, v1

        soundsWanted = true
        every { embedder.supportsAudio } returns false
        assertEquals("the model can't hear", 2, idx.pendingCount())

        every { embedder.supportsAudio } returns true
        assertEquals("plus soundtracks of v1, v2 (v3 failed)", 4, idx.pendingCount())

        idx.refreshCounts()
        val p = idx.progress.value
        assertEquals(5 + 3, p.total)
        assertEquals(8 - 4, p.indexed)
    }

    @Test
    fun `switching models drops foreign vectors and requeues their photos`() {
        val kept = photos.put(Photo(uri = "kept", indexState = IndexState.DONE))
        val stale = photos.put(Photo(uri = "stale", indexState = IndexState.DONE))
        val video = photos.put(
            Photo(uri = "video", isVideo = true, indexState = IndexState.DONE, framesDoneMs = 9_000,
                soundState = IndexState.DONE, soundDoneMs = 9_000)
        )
        vectors.put(
            listOf(
                PhotoVector(photoId = kept, vector = axis(0), model = "new"),
                PhotoVector(photoId = stale, vector = axis(1), model = "old"),
                PhotoVector(photoId = video, vector = axis(2), model = "old"),
            )
        )
        moments.put(VideoMoment(photoId = video, kind = MomentKind.FRAME, vector = axis(2), model = "old"))

        val requeued = indexer().invalidateOtherModels("new")

        assertEquals(2, requeued)
        assertEquals(listOf(kept), vectors.all.map { it.photoId })
        assertEquals(0, moments.count())
        assertEquals(IndexState.DONE, photos.get(kept).indexState)
        assertEquals(IndexState.PENDING, photos.get(stale).indexState)
        with(photos.get(video)) {
            assertEquals(IndexState.PENDING, indexState)
            assertEquals(0L, framesDoneMs)
            assertEquals(IndexState.PENDING, soundState)
        }
        assertEquals("nothing left to invalidate", 0, indexer().invalidateOtherModels("new"))
    }

    @Test
    fun `turning sound off forgets soundtrack vectors only`() {
        val video = photos.put(Photo(isVideo = true, indexState = IndexState.DONE, soundState = IndexState.DONE, soundDoneMs = 5_000))
        moments.put(
            listOf(
                VideoMoment(photoId = video, kind = MomentKind.FRAME, vector = axis(0), model = "m"),
                VideoMoment(photoId = video, kind = MomentKind.SOUND, vector = axis(1), model = "m"),
            )
        )
        indexer().forgetSounds()
        assertEquals(listOf(MomentKind.FRAME), moments.all.map { it.kind })
        with(photos.get(video)) {
            assertEquals(IndexState.PENDING, soundState)
            assertEquals(0L, soundDoneMs)
            assertEquals("pictures stay indexed", IndexState.DONE, indexState)
        }
    }

    @Test
    fun `pause and schedule survive a restart`() {
        val first = indexer()
        assertNull(first.schedule.value)
        assertFalse(first.paused.value)
        first.setSchedule(IndexSchedule.WHEN_IDLE)
        first.setPaused(true)

        val second = indexer()
        assertEquals(IndexSchedule.WHEN_IDLE, second.schedule.value)
        assertTrue(second.paused.value)
        second.setPaused(false)
        assertFalse(indexer().paused.value)
    }

    @Test
    fun `nothing runs without a model or while paused`() = runBlocking {
        photos.put(Photo(uri = "p"))
        every { embedder.isReady } returns false
        val idx = indexer()
        idx.start()
        assertFalse(idx.progress.value.running)

        every { embedder.isReady } returns true
        idx.setPaused(true)
        idx.start()
        assertFalse(idx.progress.value.running)
        assertEquals(IndexState.PENDING, photos.all.single().indexState)
    }

    @Test
    fun `a pass marks unreadable photos failed and finishes`() = runBlocking {
        photos.put(List(3) { Photo(uri = "content://nowhere/$it", dateTaken = it.toLong()) })
        every { embedder.isReady } returns true
        // The pass waits until the user has stopped touching the app for a moment.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        val idx = indexer()

        withTimeout(10_000) { idx.runUntilDone() }

        assertTrue(photos.all.all { it.indexState == IndexState.FAILED })
        assertEquals(0, vectors.count())
        assertEquals(0, idx.pendingCount())
        with(idx.progress.value) {
            assertFalse(running)
            assertEquals(3, total)
            assertEquals(3, indexed)
        }
    }

    @Test
    fun `unticked kinds wait, and the choice survives a restart`() {
        photos.put(
            listOf(
                Photo(uri = "p1"),
                Photo(uri = "p2", indexState = IndexState.DONE),
                Photo(uri = "v1", isVideo = true),
                Photo(uri = "v2", isVideo = true, indexState = IndexState.DONE, soundState = IndexState.PENDING),
            )
        )
        soundsWanted = true
        every { embedder.supportsAudio } returns true
        val idx = indexer()
        assertEquals("p1, v1, and both soundtracks", 4, idx.pendingCount())

        idx.setKinds(IndexKinds(photos = true, videos = false))
        assertEquals("only p1", 1, idx.pendingCount())
        idx.refreshCounts()
        assertEquals("only photos count towards progress", 2, idx.progress.value.total)

        idx.setKinds(IndexKinds(photos = false, videos = true))
        assertEquals("v1 and both soundtracks", 3, idx.pendingCount())

        idx.setKinds(IndexKinds(photos = false, videos = false))
        assertEquals(0, idx.pendingCount())
        assertEquals(IndexKinds(photos = false, videos = false), indexer().kinds.value)
    }
}
