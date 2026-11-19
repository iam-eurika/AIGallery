package com.lucrativeworm.aigallery.ml

import com.lucrativeworm.aigallery.data.Db
import com.lucrativeworm.aigallery.data.MomentKind
import com.lucrativeworm.aigallery.data.Photo
import com.lucrativeworm.aigallery.data.PhotoVector
import com.lucrativeworm.aigallery.data.PhotoVector_
import com.lucrativeworm.aigallery.data.VideoMoment
import com.lucrativeworm.aigallery.data.VideoMoment_
import io.objectbox.kotlin.boxFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One search result. For a video, [atMs] is where in it the best match is (null for photos), and
 * [marks] every moment that matched, for ticks on the player's timeline. [heard] says the best
 * match was in the soundtrack rather than the picture.
 */
data class Hit(
    val photo: Photo,
    val similarity: Float,
    val atMs: Long? = null,
    val marks: List<Long> = emptyList(),
    val heard: Boolean = false,
)

/**
 * Semantic search over the ObjectBox HNSW index. Text and images share EmbeddingGemma 2's vector
 * space, so the same nearest-neighbour query serves typed searches and "more like this".
 */
class SearchEngine(private val embedder: Embedder) {

    private val photos = Db.store.boxFor<Photo>()
    private val vectors = Db.store.boxFor<PhotoVector>()
    private val moments = Db.store.boxFor<VideoMoment>()

    suspend fun search(query: String): List<Hit> {
        val vector = embedder.embedText(query.trim())
        return nearest(vector, exclude = emptySet(), band = TEXT_BAND, sounds = true)
    }

    suspend fun similarTo(photoId: Long): List<Hit> {
        val vector = storedVector(photoId) ?: return emptyList()
        return nearest(vector, exclude = setOf(photoId), band = IMAGE_BAND).take(24)
    }

    /** The indexed vector for a library photo, or null if it hasn't been indexed yet. */
    suspend fun storedVector(photoId: Long): FloatArray? = withContext(Dispatchers.IO) {
        vectors.query(PhotoVector_.photoId.equal(photoId)).build().use { it.findFirst() }?.vector
    }

    /**
     * A search built from photos the user attached plus optional words: "this dog, but at the beach".
     * [images] are image vectors; [exclude] keeps the attached library photos out of the results.
     */
    suspend fun searchMixed(images: List<FloatArray>, text: String, exclude: Set<Long>): List<Hit> {
        val words = text.trim()
        val image = images.takeIf { it.isNotEmpty() }?.let(::meanOf)
        val textVector = if (words.isNotEmpty()) embedder.embedText(words) else null
        return when {
            image == null && textVector == null -> emptyList()
            image == null -> nearest(textVector!!, exclude, TEXT_BAND, sounds = true)
            textVector == null -> nearest(image, exclude, IMAGE_BAND).take(36)
            else -> fused(image, textVector, exclude)
        }
    }

    /**
     * Ranks by both the look of the attached photos and the meaning of the words. Image-to-image
     * and text-to-image similarities live on very different scales, so each is measured in units of
     * its own band below its best match, and photos must sit near the top of both on average.
     */
    private suspend fun fused(image: FloatArray, text: FloatArray, exclude: Set<Long>): List<Hit> =
        withContext(Dispatchers.IO) {
            val pool = HashMap<Long, FloatArray>()
            for (q in listOf(image, text)) {
                vectors.query(PhotoVector_.vector.nearestNeighbors(q, CANDIDATES)).build()
                    .use { it.find() }
                    .forEach { v -> if (v.photoId !in exclude) v.vector?.let { pool[v.photoId] = it } }
            }
            if (pool.isEmpty()) return@withContext emptyList()
            val iq = normalized(image)
            val tq = normalized(text)
            val scored = pool.map { (id, v) -> val n = normalized(v); Triple(id, dot(iq, n), dot(tq, n)) }
            val bestImage = scored.maxOf { it.second }
            val bestText = scored.maxOf { it.third }
            val ranked = scored
                .map { (id, si, st) -> Triple(id, 0.5f * ((si - bestImage) / IMAGE_BAND + (st - bestText) / TEXT_BAND), (si + st) / 2f) }
                .filter { it.second >= -1f }
                .sortedByDescending { it.second }
                .take(60)
            val byId = photos.get(ranked.map { it.first }).filterNotNull().associateBy { it.id }
            ranked.mapNotNull { (id, _, sim) -> byId[id]?.let { Hit(it, sim) } }
        }

    private fun meanOf(list: List<FloatArray>): FloatArray {
        if (list.size == 1) return list.first()
        val out = FloatArray(list.first().size)
        for (v in list) { val n = normalized(v); for (k in out.indices) out[k] += n[k] }
        return out
    }

    private fun normalized(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val len = kotlin.math.sqrt(sum)
        return if (len == 0f) v else FloatArray(v.size) { v[it] / len }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (k in a.indices) s += a[k] * b[k]
        return s
    }

    /** Photos that look like [image]: a lifted object, a snapped photo, anything. */
    suspend fun searchByImage(image: android.graphics.Bitmap): List<Hit> {
        val vector = embedder.embedImage(image)
        return nearest(vector, exclude = emptySet(), band = IMAGE_BAND).take(36)
    }

    /** A scored candidate: a photo/video's own vector ([atMs] null) or one moment inside a video. */
    private class Match(val photoId: Long, val sim: Float, val atMs: Long?, val heard: Boolean)

    /**
     * Nearest photos and video moments to [vector]. Photos and video frames are all pictures, so
     * they're ranked on one similarity scale, kept within [band] of the best. With [sounds] (text
     * queries), soundtrack moments join too: text-to-audio scores sit on their own scale, so they
     * get their own floor and band, and are merged by how close each is to the best of its kind.
     */
    private suspend fun nearest(vector: FloatArray, exclude: Set<Long>, band: Float, sounds: Boolean = false): List<Hit> =
        withContext(Dispatchers.IO) {
            val pictures = ArrayList<Match>()
            vectors.query(PhotoVector_.vector.nearestNeighbors(vector, CANDIDATES)).build()
                .use { it.findWithScores() }
                .forEach { if (it.get().photoId !in exclude) pictures += Match(it.get().photoId, 1f - it.score.toFloat(), null, false) }
            pictures += momentMatches(vector, MomentKind.FRAME, exclude)
            val heard = if (sounds) momentMatches(vector, MomentKind.SOUND, exclude) else emptyList()

            // Cosine distance -> similarity. Cross-modal scores are low in absolute terms, so we
            // keep a band relative to the best match instead of a fixed cut-off.
            val scored = ArrayList<Pair<Match, Float>>()
            pictures.maxOfOrNull { it.sim }?.let { best ->
                pictures.forEach { m -> if (m.sim >= best - band) scored += m to (m.sim - best) / band }
            }
            heard.filter { it.sim >= SOUND_FLOOR }.maxOfOrNull { it.sim }?.let { best ->
                heard.forEach { m ->
                    if (m.sim >= SOUND_FLOOR && m.sim >= best - SOUND_BAND) scored += m to (m.sim - best) / SOUND_BAND
                }
            }
            if (scored.isEmpty()) return@withContext emptyList()

            // One hit per photo/video, at its best match, carrying every matching moment.
            val byItem = scored.groupBy { it.first.photoId }
            val items = photos.get(byItem.keys.toList()).filterNotNull().associateBy { it.id }
            byItem.mapNotNull { (id, list) ->
                val photo = items[id] ?: return@mapNotNull null
                val bestOwn = list.maxBy { it.second }
                // For a video, a moment beats its poster vector at the same score: it knows the time.
                val bestMoment = list.filter { it.first.atMs != null }.maxByOrNull { it.second }
                val top = if (bestMoment != null && bestMoment.second >= bestOwn.second - 0.01f) bestMoment else bestOwn
                val marks = list.mapNotNull { it.first.atMs }.distinct().sorted()
                Triple(photo, top, marks)
            }
                .sortedByDescending { it.second.second }
                .map { (photo, top, marks) ->
                    Hit(
                        photo, top.first.sim,
                        atMs = top.first.atMs ?: if (photo.isVideo) marks.firstOrNull() else null,
                        marks = marks,
                        heard = top.first.heard,
                    )
                }
        }

    private fun momentMatches(vector: FloatArray, kind: Int, exclude: Set<Long>): List<Match> =
        moments.query(
            VideoMoment_.vector.nearestNeighbors(vector, CANDIDATES)
                .and(VideoMoment_.kind.equal(kind.toLong()))
        ).build()
            .use { it.findWithScores() }
            .filter { it.get().photoId !in exclude }
            .map { Match(it.get().photoId, 1f - it.score.toFloat(), it.get().startMs, kind == MomentKind.SOUND) }

    private companion object {
        // Upper bound only: the similarity band decides how many actually show.
        const val CANDIDATES = 1000
        const val TEXT_BAND = 0.11f
        const val IMAGE_BAND = 0.25f
        // Text-to-audio similarities aren't calibrated against text-to-image ones. The floor keeps
        // the best sound from always surfacing when nothing audible matches. Both need tuning on
        // real libraries.
        const val SOUND_FLOOR = 0.22f
        const val SOUND_BAND = 0.08f
    }
}
