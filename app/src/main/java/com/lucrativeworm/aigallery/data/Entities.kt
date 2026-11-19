package com.lucrativeworm.aigallery.data

import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.Unique
import io.objectbox.annotation.VectorDistanceType

/**
 * One row per image in MediaStore. Kept deliberately small (no vector) so the whole
 * library can be loaded for the grid without dragging 3 KB embeddings along per photo.
 */
@Entity
data class Photo(
    @Id var id: Long = 0,
    /** MediaStore _ID for [Source.MEDIA_STORE] photos; 0 for photos from a picked folder. */
    @Index var mediaId: Long = 0,
    /** content:// URI: a MediaStore URI or a Storage Access Framework document URI. */
    @Index var uri: String = "",
    /** See [Source]. */
    var source: Int = Source.MEDIA_STORE,
    /** The [Folder] this came from, for [Source.FOLDER] photos. */
    @Index var folderId: Long = 0,
    /**
     * Storage-relative path such as "DCIM/Camera/IMG_1.jpg" when known. Used to avoid showing
     * the same file twice when a picked folder overlaps the media library. Nullable because rows
     * written before this field existed load with no value, and ObjectBox passes null through the
     * constructor for them.
     */
    @Index var path: String? = null,
    @Index var dateTaken: Long = 0,
    var dateModified: Long = 0,
    var width: Int = 0,
    var height: Int = 0,
    var album: String = "",
    var mimeType: String = "",
    /** Average colour of the image, used as an instant placeholder while the thumbnail decodes. */
    var tint: Int = 0,
    /** See [IndexState]. Indexed so the indexer can cheaply find pending work. */
    @Index var indexState: Int = IndexState.PENDING,
    /** True for videos. Rows written before videos existed load as null/false: all images. */
    @Index var isVideo: Boolean = false,
    /** Video length; 0 for photos. */
    var durationMs: Long = 0,
    /** How far into the video frames have been indexed, so a killed pass resumes mid-video. */
    var framesDoneMs: Long = 0,
    /** See [IndexState]; only meaningful for videos, and only while sound search is on. */
    @Index var soundState: Int = IndexState.PENDING,
    /** How far into the soundtrack sound has been indexed. */
    var soundDoneMs: Long = 0,
)

/** Queues this item to be indexed from scratch (new, edited, or indexed by another model). */
fun Photo.requeue() {
    indexState = IndexState.PENDING
    framesDoneMs = 0
    soundState = IndexState.PENDING
    soundDoneMs = 0
}

object Videos {
    /** Rows written before [Photo.isVideo] existed store null, which `equal(false)` never matches. */
    fun notVideo() = Photo_.isVideo.isNull.or(Photo_.isVideo.equal(false))
    fun video() = Photo_.isVideo.equal(true)
}

object Source {
    const val MEDIA_STORE = 0
    const val FOLDER = 1

    /**
     * Matches media-library photos. Rows written before [Photo.source] existed store it as null,
     * and ObjectBox's `equal(0)` never matches null, so both must be accepted.
     */
    fun isMediaStore() = Photo_.source.isNull.or(Photo_.source.equal(MEDIA_STORE.toLong()))
}

/**
 * A folder the user granted us read access to through the system folder picker. The grant is
 * persisted, so it survives reboots until the user removes the folder.
 */
@Entity
data class Folder(
    @Id var id: Long = 0,
    @Unique var treeUri: String = "",
    var name: String = "",
    var addedAt: Long = 0,
    var photoCount: Int = 0,
    var lastScan: Long = 0,
)

object IndexState {
    const val PENDING = 0
    const val DONE = 1
    const val FAILED = 2
}

/**
 * The EmbeddingGemma 2 vector for a [Photo], in its own box so that vectors are only touched
 * by search. Text queries are embedded into the same space, so a single HNSW index answers both
 * "photos of my dog on the beach" and "more like this photo".
 */
@Entity
data class PhotoVector(
    @Id var id: Long = 0,
    @Unique @Index var photoId: Long = 0,
    @HnswIndex(dimensions = 768, distanceType = VectorDistanceType.COSINE)
    var vector: FloatArray? = null,
    var model: String = "",
) {
    override fun equals(other: Any?) = other is PhotoVector && other.id == id
    override fun hashCode() = id.hashCode()
}

/**
 * One moment inside a video: a stretch of similar-looking frames, or a few seconds of its
 * soundtrack, with the EmbeddingGemma 2 vector for it. Text queries land in the same space, so a
 * search can point at the exact time something was seen or heard.
 */
@Entity
data class VideoMoment(
    @Id var id: Long = 0,
    @Index var photoId: Long = 0,
    /** See [MomentKind]. */
    var kind: Int = MomentKind.FRAME,
    var startMs: Long = 0,
    var endMs: Long = 0,
    @HnswIndex(dimensions = 768, distanceType = VectorDistanceType.COSINE)
    var vector: FloatArray? = null,
    var model: String = "",
) {
    override fun equals(other: Any?) = other is VideoMoment && other.id == id
    override fun hashCode() = id.hashCode()
}

object MomentKind {
    const val FRAME = 0
    const val SOUND = 1
}

/** Forgets everything search knows about these photos/videos. Call inside a transaction. */
fun removeVectorsOf(ids: LongArray) {
    if (ids.isEmpty()) return
    Db.store.boxFor(PhotoVector::class.java).query(PhotoVector_.photoId.oneOf(ids)).build().use { it.remove() }
    Db.store.boxFor(VideoMoment::class.java).query(VideoMoment_.photoId.oneOf(ids)).build().use { it.remove() }
}

const val EMBEDDING_DIMS = 768
