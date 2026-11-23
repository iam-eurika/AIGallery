package com.lucrativeworm.seekframe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lucrativeworm.seekframe.data.Folder
import com.lucrativeworm.seekframe.data.Photo
import com.lucrativeworm.seekframe.ml.Indexer
import com.lucrativeworm.seekframe.ui.CircleSource
import com.lucrativeworm.seekframe.ui.CircleState
import com.lucrativeworm.seekframe.ui.CircleViewModel
import com.lucrativeworm.seekframe.ui.GalleryViewModel
import com.lucrativeworm.seekframe.ui.SearchInput
import com.lucrativeworm.seekframe.ui.SearchUi
import com.lucrativeworm.seekframe.ui.components.LocalNavScope
import com.lucrativeworm.seekframe.ui.components.LocalSharedScope
import com.lucrativeworm.seekframe.ui.screens.BackupActions
import com.lucrativeworm.seekframe.ui.screens.CircleActions
import com.lucrativeworm.seekframe.ui.screens.CircleSearchScreen
import com.lucrativeworm.seekframe.ui.screens.GalleryScreen
import com.lucrativeworm.seekframe.ui.screens.GalleryUiState
import com.lucrativeworm.seekframe.ui.screens.SettingsScreen
import com.lucrativeworm.seekframe.ui.screens.SettingsUiState
import com.lucrativeworm.seekframe.ui.screens.SourceActions
import com.lucrativeworm.seekframe.ui.screens.ViewerScreen
import com.lucrativeworm.seekframe.ui.screens.WelcomeScreen
import com.lucrativeworm.seekframe.ui.theme.GalleryTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var indexer: Indexer

    // Any touch tells the indexer someone is using the phone, so it yields the GPU for a moment.
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        indexer.nudge()
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GalleryTheme {
                Root()
            }
        }
    }
}

private val READ_PERMISSIONS: Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun hasPhotoAccess(context: android.content.Context) = READ_PERMISSIONS.any {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

/** Screens above the gallery, as a back stack. */
private sealed interface Dest { val token: Long }
private data class Viewing(
    val photos: List<Photo>,
    val index: Int,
    /** Video id -> where to start playing (the moment a search matched). */
    val seekTo: Map<Long, Long> = emptyMap(),
    /** Video id -> every moment that matched, for the timeline. */
    val marks: Map<Long, List<Long>> = emptyMap(),
    override val token: Long = System.nanoTime(),
) : Dest
private data class Circling(val source: CircleSource, override val token: Long = System.nanoTime()) : Dest
private data class Configuring(override val token: Long = System.nanoTime()) : Dest

/** A fresh file for the camera to write into; old snaps are pruned so the cache stays small. */
private fun newSnapUri(context: android.content.Context): Uri {
    val dir = java.io.File(context.cacheDir, "snaps").apply { mkdirs() }
    dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
    val file = java.io.File(dir, "snap-${System.currentTimeMillis()}.jpg")
    return androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Root(vm: GalleryViewModel = hiltViewModel(), circleVm: CircleViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasPhotoAccess(context)) }
    var denied by rememberSaveable { mutableStateOf(false) }
    // Folder-only users never grant the photo permission; their gallery is just their folders.
    var foldersChosen by rememberSaveable { mutableStateOf(vm.hasFolders()) }
    var askForFolder by rememberSaveable { mutableStateOf(false) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addFolder(uri)
    }
    val pickBackupFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setBackupFolder(uri)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.values.any { it }
        denied = !granted
        if (granted) vm.onMediaAccessChanged()
    }

    // Re-check when returning from Settings.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                val now = hasPhotoAccess(context)
                if (now != granted) vm.onMediaAccessChanged()
                granted = now
                vm.refreshFolders()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val requestMedia = {
        if (denied) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            )
        } else launcher.launch(READ_PERMISSIONS)
    }
    if (!granted && !foldersChosen) {
        WelcomeScreen(
            denied = denied,
            onContinue = requestMedia,
            onFolders = { foldersChosen = true; askForFolder = true },
        )
        return
    }
    LaunchedEffect(Unit) { vm.start() }

    // People who allowed photos before videos were supported get asked once for videos too.
    LaunchedEffect(granted) {
        if (!granted || Build.VERSION.SDK_INT < 33) return@LaunchedEffect
        val prefs = context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
        val videoOk = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        if (!videoOk && !prefs.getBoolean("asked_video", false)) {
            prefs.edit().putBoolean("asked_video", true).apply()
            launcher.launch(READ_PERMISSIONS)
        }
    }

    // Asked at the moment it makes sense: right as indexing is about to start, so the progress
    // notification can show after the user leaves. Declining changes nothing else.
    val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.downloadModel()
    }
    val startDownload = {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else vm.downloadModel()
    }

    // Sounds in videos: the answer picks which model to download, so it's settled first.
    val sounds by vm.sounds.collectAsStateWithLifecycle()
    var askSounds by rememberSaveable { mutableStateOf(false) }
    var downloadAfterSounds by rememberSaveable { mutableStateOf(false) }
    // "Not now" holds for this visit; an unanswered question comes back next time.
    var soundsDeferred by rememberSaveable { mutableStateOf(false) }
    val onDownload = {
        if (sounds == null) {
            downloadAfterSounds = true
            askSounds = true
        } else startDownload()
    }
    val onSoundsChosen = { on: Boolean ->
        askSounds = false
        vm.setSounds(on)
        if (downloadAfterSounds) {
            downloadAfterSounds = false
            startDownload()
        }
    }

    val sections by vm.sections.collectAsStateWithLifecycle()
    val unsynced by vm.unsynced.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val input by vm.input.collectAsStateWithLifecycle()
    val model by vm.modelState.collectAsStateWithLifecycle()
    val libraryLoading by vm.libraryLoading.collectAsStateWithLifecycle()
    val modelChecked by vm.modelChecked.collectAsStateWithLifecycle()
    val indexing by vm.indexing.collectAsStateWithLifecycle()
    val indexingPaused by vm.indexingPaused.collectAsStateWithLifecycle()
    val indexSchedule by vm.indexSchedule.collectAsStateWithLifecycle()
    val indexKinds by vm.indexKinds.collectAsStateWithLifecycle()
    val needsIndexSchedule by vm.needsIndexSchedule.collectAsStateWithLifecycle()
    // "Not now" on the question holds for this visit; it's asked again next time.
    var indexScheduleDeferred by rememberSaveable { mutableStateOf(false) }
    val folders by vm.folders.collectAsStateWithLifecycle()
    val scanning by vm.scanningFolders.collectAsStateWithLifecycle()
    val libraryGrid = rememberLazyGridState()
    var columns by rememberSaveable { mutableIntStateOf(3) }
    val stack = remember { mutableStateListOf<Dest>() }
    val top = stack.lastOrNull()
    val circle by circleVm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    fun push(d: Dest) {
        if (d is Circling) circleVm.open(d.source)
        stack += d
    }
    fun pop() {
        val gone = stack.removeLastOrNull()
        val next = stack.lastOrNull()
        if (next is Circling) circleVm.open(next.source)
        else if (gone is Circling && stack.none { it is Circling }) circleVm.close()
    }

    // Snap: the system camera writes straight into our cache (no camera permission needed),
    // then the shot opens ready to circle.
    var pendingSnap by rememberSaveable { mutableStateOf<String?>(null) }
    val snap = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingSnap?.let(Uri::parse)
        pendingSnap = null
        if (ok && uri != null) push(Circling(CircleSource.FromSnap(uri)))
    }
    val onSnap = {
        val uri = newSnapUri(context)
        pendingSnap = uri.toString()
        runCatching { snap.launch(uri) }.onFailure { pendingSnap = null }
        Unit
    }

    // Multi-select in the library and search results: share or delete many photos at once.
    var selection by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf<List<Photo>?>(null) }
    // Photos deleted this session, so an open viewer drops them right away.
    var deletedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var pendingTrash by remember { mutableStateOf<List<Photo>>(emptyList()) }
    // Media-library photos go to the system trash (recoverable), via Android's own confirmation.
    val trash = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) {
            selection = emptySet()
            deletedIds = deletedIds + pendingTrash.map { it.id }
        }
        pendingTrash = emptyList()
    }
    fun trashMedia(list: List<Photo>) {
        if (list.isEmpty()) return
        pendingTrash = list
        val uris = list.map { Uri.parse(it.uri) }
        if (Build.VERSION.SDK_INT >= 30) {
            val pending = android.provider.MediaStore.createTrashRequest(context.contentResolver, uris, true)
            trash.launch(androidx.activity.result.IntentSenderRequest.Builder(pending.intentSender).build())
        } else {
            // Android 10: delete what we may; ask about the first one we may not.
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                for ((i, uri) in uris.withIndex()) {
                    try {
                        context.contentResolver.delete(uri, null, null)
                        deletedIds = deletedIds + list[i].id
                    } catch (e: android.app.RecoverableSecurityException) {
                        pendingTrash = listOf(list[i])
                        trash.launch(androidx.activity.result.IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
                        break
                    }
                }
            }
        }
    }
    fun deletePhotos(list: List<Photo>) {
        val inFolders = list.filter { it.source == com.lucrativeworm.seekframe.data.Source.FOLDER }
        // Picked-folder files can't go to the system trash, so they get our own warning first.
        if (inFolders.isNotEmpty()) confirmDelete = list else trashMedia(list)
    }
    fun sharePhotos(list: List<Photo>) {
        if (list.isEmpty()) return
        val uris = ArrayList(list.map { Uri.parse(it.uri) })
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        send.type = list.map { it.mimeType }.distinct().singleOrNull()?.ifBlank { null } ?: "image/*"
        send.clipData = android.content.ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null))
    }
    // Photos that vanish (deleted here or elsewhere) drop out of the selection.
    LaunchedEffect(sections) {
        if (selection.isNotEmpty()) {
            val alive = sections.flatMap { s -> s.photos.map { it.id } }.toSet()
            selection = selection intersect alive
        }
    }

    // A selection belongs to what's on screen: starting, changing or leaving a search drops it.
    LaunchedEffect(input) { selection = emptySet() }

    BackHandler(enabled = top != null || input !is SearchInput.None || selection.isNotEmpty()) {
        when {
            top != null -> pop()
            selection.isNotEmpty() -> selection = emptySet()
            else -> vm.clearSearch()
        }
    }

    SharedTransitionLayout {
        AnimatedContent(
            targetState = top,
            contentKey = { it?.token },
            transitionSpec = { fadeIn(tween(220)).togetherWith(fadeOut(tween(220))) },
            label = "nav",
        ) { target ->
            CompositionLocalProvider(LocalSharedScope provides this@SharedTransitionLayout, LocalNavScope provides this) {
                if (target is Circling) {
                    CircleSearchScreen(
                        state = if (circle.source == target.source) circle else CircleState(source = target.source),
                        actions = CircleActions(
                            onBack = { pop() },
                            onCircle = circleVm::circle,
                            onSearchWhole = circleVm::searchWhole,
                            onClearLift = circleVm::clearLift,
                            onCopy = {
                                val systemShown = circleVm.copyLift()
                                if (systemShown == false) {
                                    android.widget.Toast.makeText(context, "Copied. Paste it anywhere", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                systemShown
                            },
                            onShare = circleVm::shareLift,
                            onOpen = { list, index -> push(Viewing(list, index)) },
                        ),
                    )
                } else if (target == null) {
                    GalleryScreen(
                        state = GalleryUiState(
                            sections, search, input, model, indexing, granted, folders, scanning, indexingPaused, indexSchedule,
                            loading = libraryLoading, modelChecked = modelChecked, filter = filter, unsynced = unsynced,
                        ),
                        libraryGrid = libraryGrid,
                        columns = columns,
                        onColumns = { columns = it },
                        onOpen = { list, index ->
                            // From search results, videos open at the moment that matched.
                            val hits = (search as? SearchUi.Results)?.takeIf { input !is SearchInput.None }?.hits.orEmpty()
                            val seek = hits.mapNotNull { h -> h.atMs?.let { h.photo.id to it } }.toMap()
                            val marks = hits.filter { it.marks.isNotEmpty() }.associate { it.photo.id to it.marks }
                            push(Viewing(list, index, seek, marks))
                        },
                        onLikePhoto = vm::likePhoto,
                        onType = vm::type,
                        onClearSearch = vm::clearSearch,
                        onAttachPhotos = vm::attachPhotos,
                        onDetachPhoto = vm::detachPhoto,
                        onDownloadModel = onDownload,
                        onOpenSettings = { push(Configuring()) },
                        sources = SourceActions(
                            onAllowMedia = requestMedia,
                            onPickFolder = { pickFolder.launch(null) },
                            onRemoveFolder = vm::removeFolder,
                            onPauseIndexing = { paused ->
                                vm.setIndexingPaused(paused)
                                // Nothing can run until the user says when: ask again rather
                                // than leave Resume doing nothing (e.g. after a reinstall).
                                if (!paused && indexSchedule == null) indexScheduleDeferred = false
                            },
                        ),
                        askForFolder = askForFolder,
                        onAskedForFolder = { askForFolder = false },
                        onSnap = onSnap,
                        selection = selection,
                        onSelectionChange = { selection = it },
                        onShare = ::sharePhotos,
                        onDelete = ::deletePhotos,
                        onFilter = vm::setFilter,
                    )
                } else if (target is Configuring) {
                    val choice by vm.modelChoice.collectAsStateWithLifecycle()
                    val system by vm.systemModel.collectAsStateWithLifecycle()
                    val active by vm.embedderBackend.collectAsStateWithLifecycle()
                    val soundMissing by vm.soundModelMissing.collectAsStateWithLifecycle()
                    val soundDownload by vm.soundDownload.collectAsStateWithLifecycle()
                    val backups by vm.backups.collectAsStateWithLifecycle()
                    val speechNow by vm.speech.collectAsStateWithLifecycle()
                    SettingsScreen(
                        state = SettingsUiState(
                            choice, model, system, active, remember(model, soundDownload) { vm.bundledDownloaded() },
                            sounds = sounds,
                            soundModelMissing = soundMissing,
                            soundDownload = soundDownload,
                            modelHears = remember(active) { vm.modelHears() },
                            speech = speechNow,
                        ),
                        onBack = { pop() },
                        onChoose = vm::setModelChoice,
                        onDownload = onDownload,
                        indexSchedule = indexSchedule,
                        onIndexSchedule = vm::setIndexSchedule,
                        indexKinds = indexKinds,
                        onIndexKinds = vm::setIndexKinds,
                        onSounds = { on -> if (on != sounds) vm.setSounds(on) },
                        onDownloadSoundModel = vm::downloadSoundModel,
                        onSpeech = { vm.chooseSpeech(it) },
                        onDownloadSpeech = vm::downloadSpeech,
                        backups = backups,
                        backupActions = remember {
                            BackupActions(
                                onPickFolder = { pickBackupFolder.launch(null) },
                                onRefresh = vm::refreshBackups,
                                onBackupNow = vm::backupNow,
                                onRestore = vm::restoreBackup,
                                onAuto = vm::setAutoBackup,
                                onDismissMessage = vm::clearBackupMessage,
                            )
                        },
                    )
                } else if (target is Viewing) {
                    val photos = remember(target, deletedIds) { target.photos.filterNot { it.id in deletedIds } }
                    // Deleted the last photo in view: nothing left to show.
                    LaunchedEffect(photos.isEmpty()) { if (photos.isEmpty() && top == target) pop() }
                    ViewerScreen(
                        photos = photos,
                        startIndex = target.index,
                        seekTo = target.seekTo,
                        marks = target.marks,
                        onDismiss = { photo ->
                            // Make sure the print we're returning to is on screen to land on.
                            val flat = sections.flatMap { it.photos }
                            val i = flat.indexOfFirst { it.id == photo.id }
                            if (i >= 0 && input is SearchInput.None) {
                                val sectionIndex = sections.indexOfFirst { s -> s.photos.any { it.id == photo.id } }
                                // grid index = hero + (empty?) + headers before + photos before
                                val gridIndex = 1 + (sectionIndex + 1) + i
                                val visible = libraryGrid.layoutInfo.visibleItemsInfo.map { it.index }
                                if (gridIndex !in (visible.minOrNull() ?: 0)..(visible.maxOrNull() ?: 0)) {
                                    libraryGrid.requestScrollToItem(gridIndex)
                                }
                            }
                            pop()
                        },
                        onLikePhoto = { photo -> stack.clear(); circleVm.close(); vm.likePhoto(photo) },
                        similar = vm::similar,
                        onOpenList = { list, index -> stack[stack.lastIndex] = Viewing(list, index, target.seekTo, target.marks) },
                        onCircle = { photo -> push(Circling(CircleSource.FromPhoto(photo))) },
                        onDelete = { photo -> deletePhotos(listOf(photo)) },
                    )
                }
            }
        }
    }

    // Unanswered on open: ask once the library has settled, at a quiet moment, not mid-gesture.
    LaunchedEffect(sounds, modelChecked, soundsDeferred) {
        if (sounds != null || !modelChecked || soundsDeferred || askSounds) return@LaunchedEffect
        kotlinx.coroutines.delay(kotlin.random.Random.nextLong(3_000, 8_000))
        askSounds = true
    }
    val soundsVisible = askSounds && sounds == null && (top == null || top is Configuring)
    com.lucrativeworm.seekframe.ui.components.SoundsSheet(
        visible = soundsVisible,
        cost = remember(soundsVisible, model) { vm.soundsCost() },
        onChoose = onSoundsChosen,
        onDismiss = {
            askSounds = false
            soundsDeferred = true
            // Asked from the download button: go ahead with pictures only for now.
            if (downloadAfterSounds) {
                downloadAfterSounds = false
                startDownload()
            }
        },
    )

    // Spoken words: asked once sounds are on and Android has said whether it offers its own
    // speech model. Each answer is the go-ahead for exactly what it downloads.
    val speech by vm.speech.collectAsStateWithLifecycle()
    var speechDeferred by rememberSaveable { mutableStateOf(false) }
    val speechVisible = sounds == true && speech.choice == null &&
        speech.android != com.lucrativeworm.seekframe.ml.SystemModelStatus.CHECKING &&
        !speechDeferred && !soundsVisible && (top == null || top is Configuring)
    com.lucrativeworm.seekframe.ui.components.SpeechSheet(
        visible = speechVisible,
        androidOffered = speech.androidOffered,
        sizeMb = com.lucrativeworm.seekframe.ml.SpeechModel.APPROX_MB,
        onChoose = { vm.chooseSpeech(it, download = true) },
        onDismiss = { speechDeferred = true },
    )

    com.lucrativeworm.seekframe.ui.components.IndexScheduleSheet(
        visible = needsIndexSchedule && !indexScheduleDeferred && top == null && !soundsVisible && !speechVisible,
        onChoose = vm::setIndexSchedule,
        onDismiss = { indexScheduleDeferred = true },
    )

    val pendingDelete = confirmDelete
    val folderCount = pendingDelete?.count { it.source == com.lucrativeworm.seekframe.data.Source.FOLDER } ?: 0
    com.lucrativeworm.seekframe.ui.components.ConfirmSheet(
        visible = pendingDelete != null,
        title = if (folderCount == 1) "Delete 1 photo from a folder?" else "Delete $folderCount photos from folders?",
        body = "Photos in folders you added are deleted for good. There's no trash to restore them from." +
            if (pendingDelete != null && folderCount < pendingDelete.size) " The other ${pendingDelete.size - folderCount} go to the system trash." else "",
        confirm = "Delete",
        onDismiss = { confirmDelete = null },
        onConfirm = {
            val list = pendingDelete.orEmpty()
            confirmDelete = null
            scope.launch {
                val folderPhotos = list.filter { it.source == com.lucrativeworm.seekframe.data.Source.FOLDER }
                val failed = vm.deleteFolderPhotos(folderPhotos)
                if (failed == 0) deletedIds = deletedIds + folderPhotos.map { it.id }
                if (failed > 0) android.widget.Toast.makeText(
                    context,
                    "Couldn't delete $failed. Remove the folder and add it again to allow deleting.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                selection = selection - folderPhotos.map { it.id }.toSet()
                trashMedia(list - folderPhotos.toSet())
            }
        },
    )
}
