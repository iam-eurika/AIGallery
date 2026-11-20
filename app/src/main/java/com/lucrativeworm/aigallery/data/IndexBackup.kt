package com.lucrativeworm.aigallery.data

import io.objectbox.kotlin.boxFor
import io.objectbox.query.QueryBuilder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The search index (photo vectors and video moments) as a portable file.
 *
 * Vectors are keyed by ObjectBox ids, which mean nothing on another phone or after a reinstall,
 * so each entry carries what identifies the file itself: its storage path (e.g.
 * "DCIM/Camera/IMG_1.jpg") and modification time, with the content URI as a fallback for files
 * that have no path. On restore, entries are matched to the photos this phone has now.
 *
 * Layout (gzip): header, then entries each preceded by a 1 byte, then a 0 byte.
 */
object IndexBackup {

    const val EXTENSION = ".aigi"
    private const val MAGIC = 0x41494749 // "AIGI"
    private const val VERSION = 1
    private const val PAGE = 200L

    data class Header(
        val createdAt: Long,
        val photos: Int,
        val videos: Int,
        /** Model ids the vectors were made with; normally one. */
        val models: List<String>,
    )

    data class RestoreResult(
        /** Photos/videos that got their vectors from the backup. */
        val restored: Int = 0,
        /** Already indexed here with the same model; left as they were. */
        val alreadyIndexed: Int = 0,
        /** Not on this phone (deleted, or changed since the backup). */
        val notFound: Int = 0,
        /** Made by a different model than the one search uses now, so they can't mix. */
        val otherModel: Int = 0,
    )

    /** Writes every indexed photo and video. Read-only on the database. */
    fun export(out: OutputStream, now: Long = System.currentTimeMillis()): Header {
        val photos = Db.store.boxFor<Photo>()
        val vectors = Db.store.boxFor<PhotoVector>()
        val moments = Db.store.boxFor<VideoMoment>()
        val done = Photo_.indexState.equal(IndexState.DONE.toLong())
        val header = Header(
            createdAt = now,
            photos = photos.query(done.and(Videos.notVideo())).build().use { it.count().toInt() },
            videos = photos.query(done.and(Videos.video())).build().use { it.count().toInt() },
            models = vectors.query().build().use { q ->
                q.property(PhotoVector_.model).distinct().findStrings()?.toList().orEmpty()
            },
        )
        val gzip = GZIPOutputStream(out, 1 shl 16)
        val data = DataOutputStream(gzip.buffered(1 shl 16))
        writeHeader(data, header)
        photos.query(done).order(Photo_.id).build().use { q ->
            var offset = 0L
            while (true) {
                val page = q.find(offset, PAGE)
                if (page.isEmpty()) break
                offset += page.size
                for (photo in page) {
                    val vector = vectors.query(PhotoVector_.photoId.equal(photo.id)).build().use { it.findFirst() }
                        ?: continue
                    val own = if (photo.isVideo) {
                        moments.query(VideoMoment_.photoId.equal(photo.id))
                            .order(VideoMoment_.startMs).build().use { it.find() }
                    } else emptyList()
                    data.writeByte(1)
                    writeEntry(data, photo, vector, own)
                }
            }
        }
        data.writeByte(0)
        data.flush()
        gzip.finish()
        return header
    }

    /** Reads just the header, for listing backups without unpacking them. */
    fun readHeader(input: InputStream): Header =
        DataInputStream(GZIPInputStream(input.buffered())).use { readHeader(it) }

    /**
     * Gives matching photos their vectors back and marks them indexed, so the indexer skips them.
     * Photos already indexed here by the same model are left alone, so a restore never undoes
     * newer work. Call with indexing stopped and the library synced.
     *
     * [activeModel] is the model search runs on now; entries from another model are skipped.
     * Null (no model loaded yet) restores everything: a mismatch is then cleared when the model
     * loads. [keepSounds] false drops soundtrack moments, matching "Pictures only".
     */
    fun restore(input: InputStream, activeModel: String?, keepSounds: Boolean): RestoreResult {
        val photos = Db.store.boxFor<Photo>()
        val vectors = Db.store.boxFor<PhotoVector>()
        val moments = Db.store.boxFor<VideoMoment>()

        val all = photos.all
        val byPath = all.filter { !it.path.isNullOrEmpty() }.groupBy { it.path!! }
        val byUri = all.associateBy { it.uri }

        var result = RestoreResult()
        val pendingPhotos = ArrayList<Photo>()
        val pendingVectors = ArrayList<PhotoVector>()
        val pendingMoments = ArrayList<VideoMoment>()
        val replaced = ArrayList<Long>()
        val taken = HashSet<Long>()

        fun flush() {
            if (pendingPhotos.isEmpty()) return
            Db.store.runInTx {
                removeVectorsOf(replaced.toLongArray())
                vectors.put(pendingVectors)
                moments.put(pendingMoments)
                photos.put(pendingPhotos)
            }
            pendingPhotos.clear(); pendingVectors.clear(); pendingMoments.clear(); replaced.clear()
        }

        DataInputStream(GZIPInputStream(input.buffered(1 shl 16))).use { data ->
            readHeader(data)
            while (data.readByte().toInt() == 1) {
                val e = readEntry(data)
                val target = match(e, byPath, byUri)
                when {
                    target == null || !taken.add(target.id) -> result = result.copy(notFound = result.notFound + 1)
                    activeModel != null && e.model != activeModel ->
                        result = result.copy(otherModel = result.otherModel + 1)
                    target.indexState == IndexState.DONE && vectors.query(
                        PhotoVector_.photoId.equal(target.id)
                            .and(PhotoVector_.model.equal(e.model, QueryBuilder.StringOrder.CASE_SENSITIVE))
                    ).build().use { it.count() > 0 } ->
                        result = result.copy(alreadyIndexed = result.alreadyIndexed + 1)
                    else -> {
                        replaced += target.id
                        pendingVectors += PhotoVector(photoId = target.id, vector = e.vector, model = e.model)
                        val keep = e.moments.filter { keepSounds || it.kind != MomentKind.SOUND }
                        keep.forEach { it.photoId = target.id }
                        pendingMoments += keep
                        target.indexState = IndexState.DONE
                        if (e.tint != 0) target.tint = e.tint
                        if (target.isVideo) {
                            target.framesDoneMs = e.framesDoneMs
                            val soundsKept = keepSounds && e.soundState == IndexState.DONE
                            target.soundState = if (soundsKept) IndexState.DONE else IndexState.PENDING
                            target.soundDoneMs = if (soundsKept) e.soundDoneMs else 0
                        }
                        pendingPhotos += target
                        result = result.copy(restored = result.restored + 1)
                        if (pendingPhotos.size >= PAGE) flush()
                    }
                }
            }
        }
        flush()
        return result
    }

    private class Entry(
        val path: String,
        val uri: String,
        val dateModified: Long,
        val isVideo: Boolean,
        val tint: Int,
        val framesDoneMs: Long,
        val soundState: Int,
        val soundDoneMs: Long,
        val model: String,
        val vector: FloatArray,
        val moments: List<VideoMoment>,
    )

    /**
     * Same file: same storage path (or URI when there is none), same kind, and not modified
     * since. MediaStore reports modification time in seconds and picked folders in milliseconds,
     * so both are compared in seconds.
     */
    private fun match(e: Entry, byPath: Map<String, List<Photo>>, byUri: Map<String, Photo>): Photo? {
        val candidates = (if (e.path.isNotEmpty()) byPath[e.path].orEmpty() else emptyList()) +
            listOfNotNull(byUri[e.uri])
        return candidates.firstOrNull { it.isVideo == e.isVideo && seconds(it.dateModified) == seconds(e.dateModified) }
    }

    internal fun seconds(t: Long) = if (t > 100_000_000_000L) t / 1000 else t

    private fun writeHeader(out: DataOutputStream, h: Header) {
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeLong(h.createdAt)
        out.writeInt(h.photos)
        out.writeInt(h.videos)
        out.writeInt(h.models.size)
        h.models.forEach { out.writeUTF(it) }
    }

    private fun readHeader(input: DataInputStream): Header {
        if (input.readInt() != MAGIC) throw IOException("Not an AI Gallery backup")
        val version = input.readInt()
        if (version != VERSION) throw IOException("Backup is from a newer version of the app ($version)")
        val created = input.readLong()
        val photos = input.readInt()
        val videos = input.readInt()
        val models = List(input.readInt()) { input.readUTF() }
        return Header(created, photos, videos, models)
    }

    private fun writeEntry(out: DataOutputStream, p: Photo, v: PhotoVector, moments: List<VideoMoment>) {
        out.writeUTF(p.path.orEmpty())
        out.writeUTF(p.uri)
        out.writeLong(p.dateModified)
        out.writeBoolean(p.isVideo)
        out.writeInt(p.tint)
        out.writeLong(p.framesDoneMs)
        out.writeInt(p.soundState)
        out.writeLong(p.soundDoneMs)
        out.writeUTF(v.model)
        writeVector(out, v.vector)
        out.writeInt(moments.size)
        for (m in moments) {
            out.writeInt(m.kind)
            out.writeLong(m.startMs)
            out.writeLong(m.endMs)
            out.writeUTF(m.model)
            writeVector(out, m.vector)
        }
    }

    private fun readEntry(input: DataInputStream): Entry {
        val path = input.readUTF()
        val uri = input.readUTF()
        val modified = input.readLong()
        val isVideo = input.readBoolean()
        val tint = input.readInt()
        val framesDone = input.readLong()
        val soundState = input.readInt()
        val soundDone = input.readLong()
        val model = input.readUTF()
        val vector = readVector(input)
        val moments = List(input.readInt()) {
            val kind = input.readInt()
            val start = input.readLong()
            val end = input.readLong()
            val m = input.readUTF()
            VideoMoment(kind = kind, startMs = start, endMs = end, model = m, vector = readVector(input))
        }
        return Entry(path, uri, modified, isVideo, tint, framesDone, soundState, soundDone, model, vector, moments)
    }

    /** Lossless float32, little-endian, length-prefixed. */
    private fun writeVector(out: DataOutputStream, v: FloatArray?) {
        val vector = v ?: FloatArray(0)
        out.writeInt(vector.size)
        val bytes = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(vector)
        out.write(bytes.array())
    }

    private fun readVector(input: DataInputStream): FloatArray {
        val n = input.readInt()
        if (n < 0 || n > 16_384) throw IOException("Damaged backup")
        val bytes = ByteArray(n * 4)
        try {
            input.readFully(bytes)
        } catch (e: EOFException) {
            throw IOException("Backup is incomplete", e)
        }
        return FloatArray(n).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
    }
}
