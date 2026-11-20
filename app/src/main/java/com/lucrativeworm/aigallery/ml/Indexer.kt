package com.lucrativeworm.aigallery.ml

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Size
import com.lucrativeworm.aigallery.data.AudioText
import com.lucrativeworm.aigallery.data.AudioText_
import com.lucrativeworm.aigallery.data.Db
import com.lucrativeworm.aigallery.data.IndexState
import com.lucrativeworm.aigallery.data.MomentKind
import com.lucrativeworm.aigallery.data.Photo
import com.lucrativeworm.aigallery.data.VideoMoment
import com.lucrativeworm.aigallery.data.VideoMoment_
import com.lucrativeworm.aigallery.data.Videos
import com.lucrativeworm.aigallery.data.requeue
import com.lucrativeworm.aigallery.data.PhotoVector
import com.lucrativeworm.aigallery.data.PhotoVector_
import com.lucrativeworm.aigallery.data.Photo_
import io.objectbox.kotlin.boxFor
import io.objectbox.query.QueryBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** When the user wants indexing to run. Asked once, before the first pass; changeable in Settings. */
enum class IndexSchedule {
    /** Start right away and keep going in the background, easing off while the phone is in use. */
    ANYTIME,
    /** Only while the phone is charging and idle (screen off, not in use), e.g. overnight. */
    WHEN_IDLE,
}

/**
 * Which kinds of items the user wants prepared right now. Unticking one doesn't forget anything
 * already indexed; it just stops new work on that kind until it's ticked again, so a big library
 * can be done photos first and videos later (or the other way round).
 */
data class IndexKinds(val photos: Boolean = true, val videos: Boolean = true)

/**
 * Walks every [IndexState.PENDING] photo, newest first (so what you just shot is searchable
 * first), embeds a small thumbnail with EmbeddingGemma 2 and stores the vector in ObjectBox.
 * Then videos, which cost far more: their frames become [VideoMoment]s, and when [soundsWanted]
 * and the model can hear, so do their soundtracks. With sounds on and the speech model downloaded,
 * what's said in each video is also written down as [AudioText] for word-for-word search.
 */
class Indexer(
    private val context: Context,
    private val embedder: Embedder,
    private val scope: CoroutineScope,
    /** The user's answer to "make sounds in videos searchable?" (null = not asked yet = no). */
    private val soundsWanted: () -> Boolean = { false },
    /** Speech-to-text for spoken words; does nothing until its model has been downloaded. */
    private val speech: Speech? = null,
) {
    data class Progress(val indexed: Int = 0, val total: Int = 0, val running: Boolean = false) {
        val fraction: Float get() = if (total == 0) 1f else indexed.toFloat() / total
        val remaining: Int get() = total - indexed
    }

    private val photos = Db.store.boxFor<Photo>()
    private val vectors = Db.store.boxFor<PhotoVector>()
    private val moments = Db.store.boxFor<VideoMoment>()
    private val audioTexts = Db.store.boxFor<AudioText>()
    private val videoIndexer = VideoIndexer(context)
    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()
    private var job: Job? = null

    private val power = context.getSystemService(PowerManager::class.java)
    private val battery = context.getSystemService(BatteryManager::class.java)

    @Volatile private var lastTouch = 0L

    /** Called on every touch in the app: indexing steps aside while the user is interacting. */
    fun nudge() {
        lastTouch = SystemClock.uptimeMillis()
    }

    private val prefs = context.getSharedPreferences("indexing", Context.MODE_PRIVATE)
    private val _paused = MutableStateFlow(prefs.getBoolean(KEY_PAUSED, false))
    /**
     * The user's own pause. Unlike a system stop it sticks: no in-app start, new-photo trigger or
     * charging job resumes indexing until the user does.
     */
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    fun setPaused(paused: Boolean) {
        prefs.edit().putBoolean(KEY_PAUSED, paused).apply()
        _paused.value = paused
        if (paused) stop()
    }

    private val _schedule = MutableStateFlow(
        prefs.getString(KEY_SCHEDULE, null)?.let { runCatching { IndexSchedule.valueOf(it) }.getOrNull() }
    )
    /** The user's choice of when to index; null until they've been asked. Nothing runs while null. */
    val schedule: StateFlow<IndexSchedule?> = _schedule.asStateFlow()

    fun setSchedule(schedule: IndexSchedule) {
        prefs.edit().putString(KEY_SCHEDULE, schedule.name).apply()
        _schedule.value = schedule
    }

    private val _kinds = MutableStateFlow(
        IndexKinds(photos = prefs.getBoolean(KEY_PHOTOS, true), videos = prefs.getBoolean(KEY_VIDEOS, true))
    )
    /** What the user wants prepared; see [IndexKinds]. A running pass follows changes item by item. */
    val kinds: StateFlow<IndexKinds> = _kinds.asStateFlow()

    fun setKinds(kinds: IndexKinds) {
        prefs.edit().putBoolean(KEY_PHOTOS, kinds.photos).putBoolean(KEY_VIDEOS, kinds.videos).apply()
        _kinds.value = kinds
        refreshCounts()
    }

    /** Sound indexing runs only when the user wants it and the loaded model has an audio encoder. */
    private fun soundsActive() = soundsWanted() && embedder.supportsAudio

    /** Spoken words are written down when the user wants sounds and the speech model is here. */
    private fun speechActive() = soundsWanted() && speech?.isAvailable == true

    fun refreshCounts() {
        val k = _kinds.value
        val stills = if (k.photos) photos.query(Videos.notVideo()).build().use { it.count().toInt() } else 0
        val allVideos = if (k.videos) photos.query(Videos.video()).build().use { it.count().toInt() } else 0
        val items = stills + allVideos
        // With sounds on, each video's soundtrack is a second piece of work.
        val videos = if (soundsActive() || speechActive()) allVideos else 0
        val sounds = if (soundsActive()) videos else 0
        // And, with the speech model here, so is writing down what's said in it.
        val spoken = if (speechActive()) videos else 0
        val total = items + sounds + spoken
        _progress.update { it.copy(indexed = total - pendingCount(), total = total) }
    }

    fun pendingCount(): Int {
        val k = _kinds.value
        if (!k.photos && !k.videos) return 0
        val pendingVisual = Photo_.indexState.equal(IndexState.PENDING.toLong())
        val visual = when {
            k.photos && k.videos -> photos.query(pendingVisual)
            k.photos -> photos.query(pendingVisual.and(Videos.notVideo()))
            else -> photos.query(pendingVisual.and(Videos.video()))
        }.build().use { it.count().toInt() }
        // Everything below is video work.
        if (!k.videos) return visual
        // Every soundtrack still to do, including those of videos whose pictures aren't done yet.
        val sound = if (soundsActive()) photos.query(
            Videos.video()
                .and(Photo_.soundState.equal(IndexState.PENDING.toLong()))
                .and(Photo_.indexState.notEqual(IndexState.FAILED.toLong()))
        ).build().use { it.count().toInt() } else 0
        val spoken = if (speechActive()) photos.query(
            Videos.video()
                .and(Videos.speechPending())
                .and(Photo_.indexState.notEqual(IndexState.FAILED.toLong()))
        ).build().use { it.count().toInt() } else 0
        return visual + sound + spoken
    }

    /**
     * Sound search was turned off: forget every soundtrack vector, and queue soundtracks again so
     * turning it back on later picks them up.
     */
    fun forgetSounds() {
        Db.store.runInTx {
            moments.query(VideoMoment_.kind.equal(MomentKind.SOUND.toLong())).build().use { it.remove() }
            audioTexts.removeAll()
            val videos = photos.query(Videos.video()).build().use { it.find() }
            videos.forEach {
                it.soundState = IndexState.PENDING; it.soundDoneMs = 0
                it.speechState = IndexState.PENDING; it.speechDoneMs = 0
            }
            photos.put(videos)
        }
        refreshCounts()
    }

    /**
     * Spoken words were turned off: forget every transcript, and queue videos again so turning it
     * back on later picks them up.
     */
    fun forgetSpeech() {
        Db.store.runInTx {
            audioTexts.removeAll()
            val videos = photos.query(Videos.video()).build().use { it.find() }
            videos.forEach { it.speechState = IndexState.PENDING; it.speechDoneMs = 0 }
            photos.put(videos)
        }
        refreshCounts()
    }

    /** Starts (or joins) the indexing pass and suspends until nothing is pending. */
    suspend fun runUntilDone() {
        start()
        job?.join()
    }

    fun stop() {
        job?.cancel()
    }

    /** Stops indexing and waits until no batch can still be written. */
    suspend fun stopAndJoin() {
        job?.cancel()
        job?.join()
    }

    /**
     * Vectors from different models live in different spaces and can't be compared. After a
     * model switch, drop every vector [modelId] didn't make and queue those photos again.
     * Returns how many photos need re-indexing.
     */
    fun invalidateOtherModels(modelId: String): Int {
        val stale = vectors.query(PhotoVector_.model.notEqual(modelId, QueryBuilder.StringOrder.CASE_SENSITIVE))
            .build().use { it.find() }
        val staleMoments = moments.query(VideoMoment_.model.notEqual(modelId, QueryBuilder.StringOrder.CASE_SENSITIVE))
            .build().use { it.find() }
        if (stale.isEmpty() && staleMoments.isEmpty()) return 0
        val ids = (stale.map { it.photoId } + staleMoments.map { it.photoId }).distinct()
        val affected = photos.get(ids).filterNotNull()
        affected.forEach { it.requeue() }
        Db.store.runInTx {
            vectors.remove(stale)
            // A video is redone whole: drop its moments from this model too, or they'd double up.
            moments.query(VideoMoment_.photoId.oneOf(affected.filter { it.isVideo }.map { it.id }.toLongArray()))
                .build().use { it.remove() }
            photos.put(affected)
        }
        Log.i(TAG, "Model is now $modelId: re-indexing ${affected.size} photos")
        refreshCounts()
        return affected.size
    }

    fun start() {
        if (job?.isActive == true || !embedder.isReady || _paused.value) return
        job = scope.launch(Dispatchers.IO) {
            refreshCounts()
            _progress.update { it.copy(running = true) }
            try {
                while (true) {
                    // Checked every round, so ticking or unticking a kind takes effect at once.
                    val kinds = _kinds.value
                    val batch = if (kinds.photos) pendingQuery().use { it.find(0, BATCH.toLong()) } else emptyList()
                    if (batch.isEmpty()) {
                        if (!kinds.videos) break
                        // Photos are done: then one video at a time, its pictures before any sound.
                        val video = pendingVideoQuery().use { it.findFirst() }
                        if (video != null) { indexVideoFrames(video); continue }
                        if (soundsActive()) {
                            val heard = pendingSoundQuery().use { it.findFirst() }
                            if (heard != null) { indexVideoSound(heard); continue }
                        }
                        if (speechActive()) {
                            val spoken = pendingSpeechQuery().use { it.findFirst() }
                            if (spoken != null) { transcribeVideo(spoken); continue }
                        }
                        break
                    }
                    val newVectors = ArrayList<PhotoVector>(batch.size)
                    for (photo in batch) {
                        try {
                            pace()
                            val started = SystemClock.uptimeMillis()
                            val thumb = loadThumbnail(photo)
                            photo.tint = averageColor(thumb)
                            val (vector, modelId) = embedder.embedImageTagged(thumb, background = true)
                            lastWorkMs = SystemClock.uptimeMillis() - started
                            val existing = vectors.query(PhotoVector_.photoId.equal(photo.id))
                                .build().use { it.findFirst() }
                            newVectors += (existing ?: PhotoVector(photoId = photo.id)).apply {
                                this.vector = vector
                                model = modelId
                            }
                            photo.indexState = IndexState.DONE
                            Log.d(TAG, "Indexed ${photo.id} (${embedder.backend}, ${lastWorkMs} ms, rest ${lastRestMs} ms)")
                        } catch (c: CancellationException) {
                            throw c
                        } catch (t: Throwable) {
                            Log.w(TAG, "Could not index ${photo.uri}", t)
                            photo.indexState = IndexState.FAILED
                        }
                        _progress.update { it.copy(indexed = (it.indexed + 1).coerceAtMost(it.total)) }
                    }
                    // One transaction per batch keeps the grid's live query from reloading per photo.
                    Db.store.runInTx {
                        vectors.put(newVectors)
                        photos.put(batch)
                    }
                }
            } finally {
                // The recogniser holds a lot of memory; let it go until there's speech to do again.
                speech?.close()
                _progress.update { it.copy(running = false) }
            }
        }
    }

    /** Frames of one video into moments, then its poster vector so "more like this" and photo search see it. */
    private suspend fun indexVideoFrames(video: Photo) {
        try {
            if (video.framesDoneMs == 0L) {
                // Fresh start (new, edited, or re-queued): nothing left over may mix in.
                moments.query(VideoMoment_.photoId.equal(video.id).and(VideoMoment_.kind.equal(MomentKind.FRAME.toLong())))
                    .build().use { it.remove() }
            }
            val resume = if (video.framesDoneMs > 0) lastMoment(video.id, MomentKind.FRAME) else null
            val ok = videoIndexer.indexFrames(
                video, resume,
                pace = ::pace,
                embed = { frame ->
                    val started = SystemClock.uptimeMillis()
                    if (video.tint == 0) video.tint = averageColor(frame)
                    embedder.embedImageTagged(frame, background = true)
                        .also { lastWorkMs = SystemClock.uptimeMillis() - started }
                },
                commit = { list, doneMs ->
                    video.framesDoneMs = doneMs
                    Db.store.runInTx {
                        moments.put(list)
                        photos.put(video)
                    }
                },
            )
            val poster = firstMoment(video.id, MomentKind.FRAME)
            if (!ok || poster == null) {
                video.indexState = IndexState.FAILED
            } else {
                val existing = vectors.query(PhotoVector_.photoId.equal(video.id)).build().use { it.findFirst() }
                vectors.put((existing ?: PhotoVector(photoId = video.id)).apply {
                    vector = poster.vector
                    model = poster.model
                })
                video.indexState = IndexState.DONE
            }
            Log.d(TAG, "Indexed video ${video.id} frames (${video.durationMs} ms, ok=$ok)")
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "Could not index video ${video.uri}", t)
            video.indexState = IndexState.FAILED
        }
        photos.put(video)
        _progress.update { it.copy(indexed = (it.indexed + 1).coerceAtMost(it.total)) }
    }

    /** One video's soundtrack into sound moments. */
    private suspend fun indexVideoSound(video: Photo) {
        try {
            if (video.soundDoneMs == 0L) {
                moments.query(VideoMoment_.photoId.equal(video.id).and(VideoMoment_.kind.equal(MomentKind.SOUND.toLong())))
                    .build().use { it.remove() }
            }
            val resume = if (video.soundDoneMs > 0) lastMoment(video.id, MomentKind.SOUND) else null
            videoIndexer.indexSound(
                video, resume,
                pace = ::pace,
                embed = { samples, rate ->
                    val started = SystemClock.uptimeMillis()
                    embedder.embedAudioTagged(samples, rate)
                        .also { lastWorkMs = SystemClock.uptimeMillis() - started }
                },
                commit = { list, doneMs ->
                    video.soundDoneMs = doneMs
                    Db.store.runInTx {
                        moments.put(list)
                        photos.put(video)
                    }
                },
            )
            // No audio track counts as done: there's simply nothing to hear.
            video.soundState = IndexState.DONE
            Log.d(TAG, "Indexed video ${video.id} sound")
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "Could not index the sound of ${video.uri}", t)
            video.soundState = IndexState.FAILED
        }
        photos.put(video)
        _progress.update { it.copy(indexed = (it.indexed + 1).coerceAtMost(it.total)) }
    }

    /** Writes down what's said in one video, an utterance at a time, as [AudioText] rows. */
    private suspend fun transcribeVideo(video: Photo) {
        val speech = speech ?: return
        try {
            if (video.speechDoneMs == 0L) {
                audioTexts.query(AudioText_.photoId.equal(video.id)).build().use { it.remove() }
            }
            val pending = ArrayList<AudioText>()
            fun commit(doneMs: Long) {
                video.speechDoneMs = doneMs
                Db.store.runInTx {
                    audioTexts.put(pending)
                    photos.put(video)
                }
                pending.clear()
            }
            var since = SystemClock.uptimeMillis()
            speech.transcribe(
                video, video.speechDoneMs.coerceAtLeast(0), videoIndexer,
                step = {
                    lastWorkMs = SystemClock.uptimeMillis() - since
                    pace()
                    since = SystemClock.uptimeMillis()
                },
                onUtterance = { text ->
                    pending += text
                    if (pending.size >= SPEECH_COMMIT_EVERY) commit(text.endMs)
                },
            )
            commit(maxOf(video.durationMs, pending.lastOrNull()?.endMs ?: 0L))
            // No audio track counts as done: nobody can have said anything.
            video.speechState = IndexState.DONE
            Log.d(TAG, "Transcribed video ${video.id}")
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "Could not transcribe ${video.uri}", t)
            video.speechState = IndexState.FAILED
        }
        photos.put(video)
        _progress.update { it.copy(indexed = (it.indexed + 1).coerceAtMost(it.total)) }
    }

    private fun lastMoment(videoId: Long, kind: Int): VideoMoment? =
        moments.query(VideoMoment_.photoId.equal(videoId).and(VideoMoment_.kind.equal(kind.toLong())))
            .orderDesc(VideoMoment_.startMs).build().use { it.findFirst() }

    private fun firstMoment(videoId: Long, kind: Int): VideoMoment? =
        moments.query(VideoMoment_.photoId.equal(videoId).and(VideoMoment_.kind.equal(kind.toLong())))
            .order(VideoMoment_.startMs).build().use { it.findFirst() }

    private var lastWorkMs = 0L
    private var lastRestMs = 0L

    /**
     * Keeps the phone usable while indexing. The model saturates the GPU (which also draws the
     * screen) and several CPU cores, so we never run it back to back while someone might be
     * looking at the phone:
     *  - while the user is touching the app, wait until they've been idle for a moment;
     *  - with the screen on, rest as long as the last photo took (about half duty);
     *  - with the screen off, go faster, and flat out when charging;
     *  - when the phone heats up, back off hard, and stop entirely until it cools if it's hot.
     */
    private suspend fun pace() {
        while (SystemClock.uptimeMillis() - lastTouch < TOUCH_QUIET_MS) delay(250)
        while (power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            Log.i(TAG, "Phone is hot, waiting for it to cool down")
            delay(30_000)
        }
        val heat = when (power.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_MODERATE -> 3f
            PowerManager.THERMAL_STATUS_LIGHT -> 1.5f
            else -> 1f
        }
        val duty = when {
            power.isInteractive -> 1f
            battery.isCharging -> 0f
            else -> 0.3f
        }
        lastRestMs = (lastWorkMs * duty * heat).toLong()
            .let { if (power.isInteractive) it.coerceAtLeast(MIN_REST_MS) else it }
        if (lastRestMs > 0) delay(lastRestMs)
    }

    private fun pendingQuery() = photos.query(Photo_.indexState.equal(IndexState.PENDING.toLong()).and(Videos.notVideo()))
        .order(Photo_.dateTaken, QueryBuilder.DESCENDING)
        .build()

    private fun pendingVideoQuery() = photos.query(Photo_.indexState.equal(IndexState.PENDING.toLong()).and(Videos.video()))
        .order(Photo_.dateTaken, QueryBuilder.DESCENDING)
        .build()

    /** Videos whose pictures are indexed but whose soundtrack isn't yet. */
    private fun pendingSoundQuery() = photos.query(
        Photo_.indexState.equal(IndexState.DONE.toLong())
            .and(Videos.video())
            .and(Photo_.soundState.equal(IndexState.PENDING.toLong()))
    ).order(Photo_.dateTaken, QueryBuilder.DESCENDING).build()

    /** Videos whose pictures are indexed but whose spoken words aren't written down yet. */
    private fun pendingSpeechQuery() = photos.query(
        Photo_.indexState.equal(IndexState.DONE.toLong())
            .and(Videos.video())
            .and(Videos.speechPending())
    ).order(Photo_.dateTaken, QueryBuilder.DESCENDING).build()

    private suspend fun loadThumbnail(photo: Photo): Bitmap = withContext(Dispatchers.IO) {
        context.contentResolver.loadThumbnail(Uri.parse(photo.uri), Size(THUMB, THUMB), null)
    }

    private fun averageColor(bitmap: Bitmap): Int =
        Bitmap.createScaledBitmap(bitmap, 1, 1, true).getPixel(0, 0)

    private companion object {
        const val TAG = "Indexer"
        const val KEY_PAUSED = "paused"
        const val KEY_SCHEDULE = "schedule"
        const val KEY_PHOTOS = "index_photos"
        const val KEY_VIDEOS = "index_videos"
        // Small batches: work done before the process is killed is only kept once committed.
        const val BATCH = 12
        const val SPEECH_COMMIT_EVERY = 4
        const val TOUCH_QUIET_MS = 1_500L
        const val MIN_REST_MS = 150L
        // EmbeddingGemma 2 patchifies to a fixed vision-token budget, so a larger input buys nothing.
        const val THUMB = 448
    }
}
