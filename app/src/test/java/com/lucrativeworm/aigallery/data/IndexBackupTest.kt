package com.lucrativeworm.aigallery.data

import com.lucrativeworm.aigallery.testing.TestDb
import com.lucrativeworm.aigallery.testing.axis
import io.objectbox.kotlin.boxFor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Export on one "phone", wipe, restore onto rows with new ids, as after a reinstall. */
@RunWith(RobolectricTestRunner::class)
class IndexBackupTest {

    @get:Rule val db = TestDb()

    private val photos get() = db.store.boxFor<Photo>()
    private val vectors get() = db.store.boxFor<PhotoVector>()
    private val moments get() = db.store.boxFor<VideoMoment>()

    private fun seed(): ByteArray {
        val a = Photo(uri = "content://media/1", path = "DCIM/Camera/a.jpg", dateModified = 1_700_000_000, indexState = IndexState.DONE, tint = 7)
        val b = Photo(uri = "content://media/2", path = "DCIM/Camera/b.jpg", dateModified = 1_700_000_100, indexState = IndexState.DONE)
        val v = Photo(
            uri = "content://media/3", path = "DCIM/Camera/v.mp4", dateModified = 1_700_000_200, isVideo = true,
            indexState = IndexState.DONE, framesDoneMs = 9_000, soundState = IndexState.DONE, soundDoneMs = 9_000,
        )
        val pending = Photo(uri = "content://media/4", path = "DCIM/Camera/c.jpg", dateModified = 1)
        photos.put(listOf(a, b, v, pending))
        vectors.put(
            listOf(
                PhotoVector(photoId = a.id, vector = axis(0), model = "m1"),
                PhotoVector(photoId = b.id, vector = axis(1), model = "m1"),
                PhotoVector(photoId = v.id, vector = axis(2), model = "m1"),
            )
        )
        moments.put(
            listOf(
                VideoMoment(photoId = v.id, kind = MomentKind.FRAME, startMs = 0, endMs = 4_000, vector = axis(2), model = "m1"),
                VideoMoment(photoId = v.id, kind = MomentKind.SOUND, startMs = 0, endMs = 5_000, vector = axis(3), model = "m1"),
            )
        )
        val out = ByteArrayOutputStream()
        val header = IndexBackup.export(out, now = 42)
        assertEquals(IndexBackup.Header(42, photos = 2, videos = 1, models = listOf("m1")), header)
        assertEquals(header, IndexBackup.readHeader(ByteArrayInputStream(out.toByteArray())))
        return out.toByteArray()
    }

    /** A fresh install: same files, new ids, nothing indexed. Picked-folder times are in ms. */
    private fun reinstall() {
        db.store.removeAllObjects()
        photos.put(
            listOf(
                Photo(uri = "content://media/90", path = "DCIM/Camera/b.jpg", dateModified = 1_700_000_100),
                Photo(uri = "content://tree/a", path = "DCIM/Camera/a.jpg", dateModified = 1_700_000_000_123, source = Source.FOLDER),
                Photo(uri = "content://media/92", path = "DCIM/Camera/v.mp4", dateModified = 1_700_000_200, isVideo = true),
                // Edited since the backup: must be indexed again, not given a stale vector.
                Photo(uri = "content://media/93", path = "DCIM/Camera/c.jpg", dateModified = 5),
            )
        )
    }

    private fun byPath(path: String) = photos.query(Photo_.path.equal(path)).build().use { it.findFirst()!! }
    private fun vectorOf(p: Photo) = vectors.query(PhotoVector_.photoId.equal(p.id)).build().use { it.findFirst() }

    @Test
    fun `restore matches by path and time onto new ids`() {
        val backup = seed()
        reinstall()

        val result = IndexBackup.restore(ByteArrayInputStream(backup), activeModel = "m1", keepSounds = true)

        assertEquals(IndexBackup.RestoreResult(restored = 3), result)
        val a = byPath("DCIM/Camera/a.jpg")
        assertEquals(IndexState.DONE, a.indexState)
        assertEquals(7, a.tint)
        assertArrayEquals(axis(0), vectorOf(a)!!.vector, 0f)
        assertArrayEquals(axis(1), vectorOf(byPath("DCIM/Camera/b.jpg"))!!.vector, 0f)
        val v = byPath("DCIM/Camera/v.mp4")
        assertEquals(IndexState.DONE, v.soundState)
        assertEquals(9_000, v.framesDoneMs)
        assertEquals(2, moments.query(VideoMoment_.photoId.equal(v.id)).build().use { it.count() })
        assertEquals(IndexState.PENDING, byPath("DCIM/Camera/c.jpg").indexState)
    }

    @Test
    fun `pictures-only drops sound moments and queues soundtracks`() {
        val backup = seed()
        reinstall()

        IndexBackup.restore(ByteArrayInputStream(backup), activeModel = "m1", keepSounds = false)

        val v = byPath("DCIM/Camera/v.mp4")
        assertEquals(IndexState.PENDING, v.soundState)
        assertEquals(0, moments.query(VideoMoment_.kind.equal(MomentKind.SOUND.toLong())).build().use { it.count() })
    }

    @Test
    fun `another model is skipped and existing work is kept`() {
        val backup = seed()
        reinstall()
        val b = byPath("DCIM/Camera/b.jpg").apply { indexState = IndexState.DONE }
        photos.put(b)
        vectors.put(PhotoVector(photoId = b.id, vector = axis(9), model = "m1"))

        val same = IndexBackup.restore(ByteArrayInputStream(backup), activeModel = "m1", keepSounds = true)
        assertEquals(IndexBackup.RestoreResult(restored = 2, alreadyIndexed = 1), same)
        assertArrayEquals(axis(9), vectorOf(b)!!.vector, 0f)

        reinstall()
        val other = IndexBackup.restore(ByteArrayInputStream(backup), activeModel = "m2", keepSounds = true)
        assertEquals(IndexBackup.RestoreResult(otherModel = 3), other)
        assertEquals(0, vectors.count())
    }
}
