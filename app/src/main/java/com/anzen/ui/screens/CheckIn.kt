package com.anzen.ui.screens

import android.Manifest
import android.content.ClipboardManager
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anzen.core.Challenge
import com.anzen.core.Desk
import com.anzen.core.ErrorCopy
import com.anzen.core.Format
import com.anzen.core.Links
import com.anzen.core.Repository
import com.anzen.core.TxState
import com.anzen.core.VenueCode
import com.anzen.ui.ActionViewModel
import com.anzen.ui.AppViewModel
import com.anzen.ui.activity
import com.anzen.ui.components.Gap
import com.anzen.ui.components.InfoNote
import com.anzen.ui.components.InfoRow
import com.anzen.ui.components.LargeTitle
import com.anzen.ui.components.LocalToast
import com.anzen.ui.components.Panel
import com.anzen.ui.components.PrimaryButton
import com.anzen.ui.components.QrCode
import com.anzen.ui.components.QuietButton
import com.anzen.ui.components.SecondaryButton
import com.anzen.ui.components.StatusPill
import com.anzen.ui.components.Tone
import com.anzen.ui.components.TopBar
import com.anzen.ui.components.TxProgress
import com.anzen.ui.components.copyText
import com.anzen.ui.theme.Anzen
import com.anzen.ui.theme.AnzenType
import com.anzen.ui.theme.Radius
import com.anzen.ui.theme.Spacing
import com.anzen.ui.theme.rememberHaptics
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.util.concurrent.Executors

/** Camera QR scanner for venue codes and passport proofs. Also accepts a pasted anzen:// link. */
@Composable
fun ScanScreen(back: () -> Unit, onLink: (String) -> Unit) {
    val c = Anzen.colors
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var asked by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    var handled by remember { mutableStateOf(false) }
    fun accept(text: String) {
        if (handled) return
        val route = Links.route(text)
        if (route == null) {
            problem = ErrorCopy.of("InvalidCode")
            haptics.reject()
        } else {
            handled = true
            haptics.confirm()
            onLink(route)
        }
    }

    Column(Modifier.fillMaxSize().background(c.backgroundPrimary)) {
        TopBar("Scan code", onBack = back)
        Column(Modifier.weight(1f).padding(horizontal = Spacing.gutter)) {
            Text("Point the camera at the code shown at the venue's check-in desk.", style = AnzenType.body, color = c.textSecondary)
            Gap(16)
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(Radius.large).background(Color.Black), contentAlignment = Alignment.Center) {
                if (granted) QrCamera(onCode = ::accept)
                else Text(if (asked) "Camera access is off. Allow it in Settings, or paste a code link below." else "Waiting for camera permission…",
                    style = AnzenType.callout, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(Spacing.xl))
                // Viewfinder: a quiet frame, no animated laser.
                Box(Modifier.size(220.dp).border(2.dp, Color.White.copy(alpha = 0.8f), Radius.medium))
            }
            Gap(12)
            problem?.let { InfoNote(it, Tone.Failed) }
        }
        Column(Modifier.navigationBarsPadding().padding(Spacing.gutter)) {
            SecondaryButton("Paste code link") {
                val text = ctx.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
                if (text.isNullOrBlank()) problem = "The clipboard is empty." else accept(text)
            }
        }
    }
}

@Composable
private fun QrCamera(onCode: (String) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    AndroidView(factory = { context ->
        PreviewView(context).also { view ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val reader = QRCodeReader()
                val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { image ->
                    try {
                        val plane = image.planes[0]
                        val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
                        val source = PlanarYUVLuminanceSource(bytes, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
                        val text = reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
                        ContextCompat.getMainExecutor(ctx).execute { onCode(text) }
                    } catch (_: Exception) {
                        // no code in this frame
                    } finally {
                        reader.reset()
                        image.close()
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(context))
        }
    }, modifier = Modifier.fillMaxSize())
}

/** Attendee check-in: verify the code is for this event and still fresh, then sign it. */
@Composable
fun CheckInScreen(id: Long, codeArg: String?, vm: AppViewModel, back: () -> Unit, go: (String) -> Unit) {
    val c = Anzen.colors
    val r = rememberEvent(id, vm)
    val action: ActionViewModel = hiltViewModel()
    val tx by action.tx.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    val code = remember(codeArg) { codeArg?.let { VenueCode.parse(URLDecoder.decode(it, "UTF-8")) } }
    LaunchedEffect(tx) {
        if (tx is TxState.Verified) haptics.confirm()
        if (tx is TxState.Failed) haptics.reject()
    }
    Pushed("Check in", back) {
        Loaded(r, {}) { e ->
            // Countdown against chain time: the phone's clock may be off.
            val skew = remember(e) { e.now - System.currentTimeMillis() / 1000 }
            var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000 + skew) }
            LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() / 1000 + skew } }

            LargeTitle(e.title, Modifier.padding(vertical = Spacing.m), subtitle = e.venue)
            val status = e.me?.status ?: "none"
            Panel {
                InfoRow("Check-in window", "${Format.time(e.checkInStart)} – ${Format.time(e.checkInEnd)}")
                InfoRow("Your commitment", when {
                    e.me?.attended == true -> "Checked in"
                    status == "committed" -> "Committed · eligible"
                    else -> "No commitment"
                })
            }
            Gap(16)
            when {
                tx is TxState.Verified -> CheckedIn(e.title, (tx as TxState.Verified).tx) { back() }
                status != "committed" && tx == TxState.Review ->
                    InfoNote(if (e.me?.attended == true) "You're already checked in." else "This account has no commitment for this event, so there's nothing to check in.", Tone.Pending)
                e.phase != "check_in" && tx == TxState.Review -> InfoNote(
                    if (now < e.checkInStart) "Check-in opens ${Format.relative(e.checkInStart, now)} (${Format.time(e.checkInStart)})."
                    else "Check-in has closed.", Tone.Pending)
                code == null -> {
                    if (codeArg != null) { InfoNote(ErrorCopy.of("InvalidCode"), Tone.Failed); Gap(12) }
                    Text("Scan the code shown at the venue's check-in desk.", style = AnzenType.body, color = c.textSecondary)
                    Gap(16)
                    PrimaryButton("Scan venue code", accent = true) { go("scan") }
                }
                code.eventId != e.id -> {
                    InfoNote(ErrorCopy.of("CodeForOtherEvent"), Tone.Failed)
                    Gap(12)
                    SecondaryButton("Scan again") { go("scan") }
                }
                else -> {
                    val left = code.expiresAt - now
                    Panel {
                        Text("Venue code", style = AnzenType.caption, color = c.textSecondary)
                        Gap(4)
                        if (left > 0) StatusPill("Valid for $left s", if (left > 10) Tone.Info else Tone.Pending)
                        else StatusPill("Expired · scan a fresh code", Tone.Failed)
                        Gap(8)
                        Text("Signing shows this phone's key signed this event's code while it was fresh. It doesn't prove " +
                            "your location.", style = AnzenType.callout, color = c.textSecondary)
                    }
                    Gap(16)
                    when (tx) {
                        TxState.Review, TxState.Cancelled ->
                            if (left > 0) PrimaryButton("Sign check-in", accent = true) { ctx.activity()?.let { a -> action.run { checkIn(a, e, code, it) } } }
                            else PrimaryButton("Scan a fresh code") { go("scan") }
                        is TxState.Failed -> {
                            TxProgress(tx, "Checked in")
                            Gap(12)
                            PrimaryButton("Scan a fresh code") { go("scan") }
                        }
                        else -> TxProgress(tx, "Checked in")
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckedIn(title: String, tx: String, onDone: () -> Unit) {
    val c = Anzen.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        com.anzen.ui.components.StatusGlyph(Tone.Verified, c.statusVerified, 64.dp)
        Gap(16)
        Text("You're checked in", style = AnzenType.title, color = c.textPrimary)
        Gap(6)
        Text("The treasury recorded your check-in for $title. Your outcome is applied after check-in closes.",
            style = AnzenType.callout, color = c.textSecondary, textAlign = TextAlign.Center)
        Gap(12)
        InfoRow("Transaction", Format.shortHex(tx, 10, 8), mono = true, copy = tx)
        Gap(16)
        PrimaryButton("Done", onClick = onDone)
    }
}

/**
 * Organizer's check-in desk: one authorization opens it, then the screen shows a fresh issuer-signed code every
 * ~35 s (each valid 45 s), with live check-in counts from the contract. Keeps the screen on.
 */
@Composable
fun DeskScreen(id: Long, vm: AppViewModel, back: () -> Unit) {
    val c = Anzen.colors
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    var version by remember { mutableIntStateOf(0) }
    val r = rememberEvent(id, vm, version)
    var desk by remember { mutableStateOf<Desk?>(null) }
    var code by remember { mutableStateOf<Challenge?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var opening by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(desk) {
        val d = desk ?: return@LaunchedEffect
        while (true) {
            try {
                code = vm.repo.venueCode(id, d.token)
                problem = null
                version++ // refresh counts
                delay(35_000)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                code = null
                problem = Repository.reasonOf(e)
                delay(10_000)
            }
        }
    }

    Pushed("Check-in desk", back) {
        Loaded(r, { version++ }) { e ->
            LargeTitle(e.title, Modifier.padding(vertical = Spacing.m), subtitle = "${e.attended} of ${e.committed} checked in")
            when {
                e.organizer != vm.repo.keyId -> InfoNote(ErrorCopy.of("NotOrganizer"), Tone.Failed)
                desk == null -> {
                    Text("Opening the desk asks for your authorization once. The screen then shows a new code every " +
                        "35 seconds. Each code works for 45 seconds, so a photo of it stops working quickly.",
                        style = AnzenType.body, color = c.textSecondary)
                    Gap(20)
                    problem?.let { InfoNote(ErrorCopy.of(it), Tone.Failed); Gap(12) }
                    PrimaryButton("Open desk", loading = opening) {
                        val a = ctx.activity() ?: return@PrimaryButton
                        opening = true
                        scope.launch {
                            try { desk = vm.repo.openDesk(a, e) } catch (ex: CancellationException) { throw ex } catch (ex: Exception) {
                                problem = if (ex is com.anzen.core.AuthorizationCancelled) null else Repository.reasonOf(ex)
                            }
                            opening = false
                        }
                    }
                }
                code != null -> {
                    val cc = code!!
                    Box(Modifier.fillMaxWidth().clip(Radius.large).background(Color.White).padding(Spacing.l), contentAlignment = Alignment.Center) {
                        QrCode(cc.uri, Modifier.fillMaxWidth().aspectRatio(1f), "Venue check-in code for ${e.title}")
                    }
                    Gap(12)
                    StatusPill("New code every 35 s · each valid 45 s", Tone.Info)
                    Gap(12)
                    InfoNote("Attendees scan this with Shonko or their camera app. A code works for anyone who can see it while " +
                        "it's valid, so keep it at the desk.")
                    Gap(8)
                    QuietButton("Copy code link") { copyText(ctx, "Venue code", cc.uri); toast("Code link copied · valid 45 s") }
                }
                problem == "WrongPhase" -> InfoNote(if (e.phase == "open" || e.phase == "closed")
                    "Codes appear when check-in opens at ${Format.dateTime(e.checkInStart)}. Keep this screen open." else
                    "Check-in has closed.", Tone.Pending)
                problem != null -> InfoNote(ErrorCopy.of(problem!!), Tone.Failed)
                else -> com.anzen.ui.components.LoadingState("Getting a code")
            }
        }
    }
}
