package com.lucrativeworm.seekframe.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lucrativeworm.seekframe.ml.QrCode
import com.lucrativeworm.seekframe.ml.QrScanner
import com.lucrativeworm.seekframe.ui.components.Glyph
import com.lucrativeworm.seekframe.ui.components.IconButton
import com.lucrativeworm.seekframe.ui.components.QrChip
import com.lucrativeworm.seekframe.ui.components.QrSheet
import com.lucrativeworm.seekframe.ui.components.Txt
import com.lucrativeworm.seekframe.ui.components.squish
import com.lucrativeworm.seekframe.ui.theme.Theme
import java.io.File
import kotlin.math.roundToInt

/** A fresh file in the cache for a snap; old snaps are pruned so the cache stays small. */
fun newSnapFile(context: Context): File {
    val dir = File(context.cacheDir, "snaps").apply { mkdirs() }
    dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
    return File(dir, "snap-${System.currentTimeMillis()}.jpg")
}

fun snapUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.files", file)

/** How long a code stays bracketed after the camera loses it, so it doesn't flicker. */
private const val HOLD_MS = 700L

/**
 * The in-app camera. A live viewfinder that brackets QR codes the moment they're in frame (tap
 * the brackets or the chip for the link), and a shutter that snaps a photo to circle-search.
 */
@Composable
fun CameraScreen(
    onBack: () -> Unit,
    onCaptured: (Uri) -> Unit,
    /** Fallback when the camera permission is refused: the system camera app needs none. */
    onSystemCamera: () -> Unit,
) {
    val context = LocalContext.current
    fun hasCamera() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(hasCamera()) }
    var asked by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LaunchedEffect(Unit) { if (!granted) request.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            LiveCamera(onCaptured)
        } else if (asked) {
            NoCamera(
                onAllow = {
                    val activity = context as? android.app.Activity
                    // Once Android stops showing the dialog, the only way back is app settings.
                    if (activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        )
                    } else request.launch(Manifest.permission.CAMERA)
                },
                onSystemCamera = onSystemCamera,
            )
        }
        IconButton(
            Glyph.Close, "Close camera",
            tint = Color.White, background = Color.Black.copy(alpha = 0.35f), size = 44.dp,
            modifier = Modifier.statusBarsPadding().padding(12.dp),
            onClick = onBack,
        )
    }

    // Coming back from Settings with the permission now on.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) granted = hasCamera()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun LiveCamera(onCaptured: (Uri) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val main = remember { ContextCompat.getMainExecutor(context) }

    var live by remember { mutableStateOf<QrCode?>(null) }
    var lastSeen by remember { mutableLongStateOf(0L) }
    var openQr by remember { mutableStateOf<QrCode?>(null) }
    var capturing by remember { mutableStateOf(false) }
    val flash = remember { Animatable(0f) }
    var flashes by remember { mutableStateOf(0) }

    val onFrame by rememberUpdatedState { codes: List<QrCode> ->
        if (openQr != null) return@rememberUpdatedState // freeze while the sheet is up
        val now = System.currentTimeMillis()
        // Several codes in frame: follow the biggest, which is almost always the one aimed at.
        val best = codes.maxByOrNull { it.bounds.width() * it.bounds.height() }
        if (best != null) {
            if (best.raw != live?.raw) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            live = best
            lastSeen = now
        } else if (live != null && now - lastSeen > HOLD_MS) {
            live = null
        }
    }

    val scanner = remember { QrScanner.newClient() }
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }
    }
    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            scanner.close()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    this.controller = controller // also gives tap-to-focus and pinch-to-zoom
                    // View-referenced: ML Kit's boxes come back already in this view's pixels.
                    controller.setImageAnalysisAnalyzer(
                        main,
                        MlKitAnalyzer(listOf(scanner), ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED, main) { result ->
                            val w = width.toFloat()
                            val h = height.toFloat()
                            if (w <= 0f || h <= 0f) return@MlKitAnalyzer
                            val codes = result.getValue(scanner).orEmpty().mapNotNull { QrScanner.fromBarcode(it, w, h) }
                            onFrame(codes)
                        },
                    )
                }
            },
        )

        LiveBrackets(live, onTap = { openQr = it })

        // Shutter flash.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = flash.value }.background(Color.White))
        LaunchedEffect(flashes) {
            if (flashes == 0) return@LaunchedEffect
            flash.snapTo(0.7f)
            flash.animateTo(0f, tween(260))
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) {
                val shown = live
                androidx.compose.animation.AnimatedVisibility(
                    visible = shown != null,
                    enter = fadeIn(tween(160)) + slideInVertically { it / 2 } + scaleIn(initialScale = 0.9f),
                    exit = fadeOut(tween(160)),
                ) {
                    var last by remember { mutableStateOf(shown) }
                    if (shown != null) last = shown
                    last?.let { code ->
                        QrChip(code, 1, onClick = { openQr = code }, modifier = Modifier.background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(50)))
                    }
                }
                androidx.compose.animation.AnimatedVisibility(visible = shown == null, enter = fadeIn(tween(300, delayMillis = 200)), exit = fadeOut(tween(100))) {
                    Txt(
                        "Point at a QR code, or snap to search your photos",
                        Theme.type.caption, Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Shutter(enabled = !capturing) {
                capturing = true
                flashes++
                val file = newSnapFile(context)
                controller.takePicture(
                    ImageCapture.OutputFileOptions.Builder(file).build(),
                    main,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            onCaptured(snapUri(context, file))
                        }
                        override fun onError(exception: ImageCaptureException) {
                            capturing = false
                            Toast.makeText(context, "Couldn't take the photo. Try again", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }

        QrSheet(openQr, onDismiss = { openQr = null })
    }
}

/** Corner brackets that glide after the code as the camera moves, so it feels locked on. */
@Composable
private fun LiveBrackets(code: QrCode?, onTap: (QrCode) -> Unit) {
    val c = Theme.colors
    val density = LocalDensity.current
    var last by remember { mutableStateOf(code) }
    if (code != null) last = code
    val alpha by animateFloatAsState(if (code != null) 1f else 0f, tween(if (code != null) 120 else 220), label = "qrAlpha")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val target = last?.bounds?.let { Rect(it.left * w, it.top * h, it.right * w, it.bottom * h) } ?: return@BoxWithConstraints
        // Snap into place on first sight, then follow with a soft spring.
        var seen by remember { mutableStateOf(false) }
        val rect by animateRectAsState(
            target,
            if (seen) spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow) else tween(0),
            label = "qrRect",
        )
        LaunchedEffect(Unit) { seen = true }
        val outset = with(density) { 8.dp.toPx() }
        val r = rect.inflate(outset)
        val minTouch = with(density) { 56.dp.toPx() }
        val tw = maxOf(r.width, minTouch)
        val th = maxOf(r.height, minTouch)

        Canvas(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
            val arm = minOf(r.width, r.height) * 0.22f
            val cr = 6.dp.toPx()
            drawRoundRect(c.safelight.copy(alpha = 0.16f), topLeft = r.topLeft, size = Size(r.width, r.height), cornerRadius = CornerRadius(cr * 2))
            listOf(
                Triple(r.topLeft, 1f, 1f), Triple(r.topRight, -1f, 1f),
                Triple(r.bottomLeft, 1f, -1f), Triple(r.bottomRight, -1f, -1f),
            ).forEach { (p, dx, dy) ->
                val path = Path().apply {
                    moveTo(p.x, p.y + dy * arm)
                    lineTo(p.x, p.y + dy * cr)
                    quadraticTo(p.x, p.y, p.x + dx * cr, p.y)
                    lineTo(p.x + dx * arm, p.y)
                }
                drawPath(path, Color.Black.copy(alpha = 0.35f), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
                drawPath(path, c.safelight, style = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        if (code != null) {
            Box(
                Modifier
                    .offset { IntOffset((r.center.x - tw / 2).roundToInt(), (r.center.y - th / 2).roundToInt()) }
                    .size(with(density) { tw.toDp() }, with(density) { th.toDp() })
                    .semantics { contentDescription = "QR code: ${code.label}" }
                    .pointerInput(code) { detectTapGestures { onTap(code) } },
            )
        }
    }
}

@Composable
private fun Shutter(enabled: Boolean, onClick: () -> Unit) {
    val c = Theme.colors
    Box(
        Modifier
            .size(76.dp)
            .clip(CircleShape)
            .border(4.dp, Color.White, CircleShape)
            .squish(pressedScale = 0.88f) { if (enabled) onClick() }
            .semantics { contentDescription = "Snap and circle to search" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(60.dp).clip(CircleShape).background(if (enabled) c.safelight else c.safelight.copy(alpha = 0.5f)))
    }
}

@Composable
private fun NoCamera(onAllow: () -> Unit, onSystemCamera: () -> Unit) {
    val c = Theme.colors
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Txt("Camera is off", Theme.type.title, Color.White)
        Spacer(Modifier.height(8.dp))
        Txt(
            "Allow the camera to scan QR codes live. You can still snap a photo with your camera app.",
            Theme.type.body.copy(textAlign = TextAlign.Center), Color.White.copy(alpha = 0.7f),
            modifier = Modifier.widthIn(max = 320.dp),
        )
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier.squish(onClick = onAllow).clip(RoundedCornerShape(16.dp)).background(c.safelight)
                .padding(horizontal = 22.dp, vertical = 14.dp),
        ) { Txt("Allow camera", Theme.type.label, c.onSafelight) }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.squish(onClick = onSystemCamera).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.12f))
                .padding(horizontal = 22.dp, vertical = 14.dp),
        ) { Txt("Use camera app instead", Theme.type.label, Color.White) }
    }
}
