package com.anzen.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.anzen.core.ErrorCopy
import com.anzen.core.Format
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.anzen.ui.theme.Anzen
import com.anzen.ui.theme.AnzenType
import com.anzen.ui.theme.Motion
import com.anzen.ui.theme.Radius
import com.anzen.ui.theme.Spacing
import com.anzen.ui.theme.rememberHaptics

/** Shows a short message at the bottom of the screen ("Copied", "Draft saved"). */
val LocalToast = staticCompositionLocalOf<(String) -> Unit> { {} }

// ------------------------------------------------------------------ page structure

/** Inline top bar for pushed screens: back, optional title, trailing actions. Large titles live in content. */
@Composable
fun TopBar(title: String = "", onBack: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) =
    Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Anzen.colors.textPrimary)
            }
        } else Spacer(Modifier.width(Spacing.l))
        Text(title, style = AnzenType.headline, color = Anzen.colors.textPrimary, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() })
        trailing()
    }

@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier, subtitle: String? = null) = Column(modifier) {
    Text(text, style = AnzenType.largeTitle, color = Anzen.colors.textPrimary, modifier = Modifier.semantics { heading() })
    if (subtitle != null) {
        Gap(4)
        Text(subtitle, style = AnzenType.callout, color = Anzen.colors.textSecondary)
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) =
    Row(modifier.fillMaxWidth().padding(top = Spacing.xl, bottom = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = AnzenType.overline, color = Anzen.colors.textTertiary,
            modifier = Modifier.weight(1f).semantics { heading() })
        trailing()
    }

@Composable
fun Gap(h: Int) = Spacer(Modifier.height(h.dp))

/** The one container. Never nested inside another Panel. */
@Composable
fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: Dp = Spacing.l,
          content: @Composable ColumnScope.() -> Unit) {
    val c = Anzen.colors
    Column(
        modifier.fillMaxWidth().clip(Radius.medium).background(c.backgroundElevated)
            .border(1.dp, c.borderSubtle, Radius.medium)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** Ink (light) / graphite (dark) surface for the things that matter most: amounts, passports, outcomes. */
@Composable
fun SealedPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = Column(
    modifier.fillMaxWidth().clip(Radius.large).background(Anzen.colors.surfaceSealed).padding(Spacing.xl),
    content = content,
)

// ------------------------------------------------------------------ actions

@Composable
private fun pressScale(source: MutableInteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    val reduced = Anzen.reducedMotion
    return animateFloatAsState(if (pressed && !reduced) 0.97f else 1f, tween(Motion.micro), label = "press").value
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false,
                  accent: Boolean = false, onClick: () -> Unit) {
    val c = Anzen.colors
    val source = remember { MutableInteractionSource() }
    Button(
        onClick = onClick, enabled = enabled && !loading, interactionSource = source,
        modifier = modifier.fillMaxWidth().heightIn(min = 54.dp).scale(pressScale(source)),
        shape = Radius.medium, elevation = null,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (accent) c.accent else c.actionPrimary,
            contentColor = if (accent) c.onAccent else c.onActionPrimary,
            disabledContainerColor = c.surfaceSecondary, disabledContentColor = c.textTertiary),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), color = c.textSecondary, strokeWidth = 2.dp)
            Spacer(Modifier.width(Spacing.m))
        }
        Text(text, style = AnzenType.label)
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, destructive: Boolean = false,
                    onClick: () -> Unit) {
    val c = Anzen.colors
    val source = remember { MutableInteractionSource() }
    OutlinedButton(onClick = onClick, enabled = enabled, interactionSource = source,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp).scale(pressScale(source)), shape = Radius.medium,
        border = BorderStroke(1.dp, c.borderStrong)) {
        Text(text, style = AnzenType.label, color = if (destructive) c.statusFailed else c.textPrimary)
    }
}

@Composable
fun QuietButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) {
        Text(text, style = AnzenType.label, color = Anzen.colors.textPrimary)
    }

/** Destructive or irreversible actions always pass through here. */
@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, destructive: Boolean = false,
                  onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = Anzen.colors
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.backgroundElevated, shape = Radius.large,
        title = { Text(title, style = AnzenType.title, color = c.textPrimary) },
        text = { Text(body, style = AnzenType.body, color = c.textSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, style = AnzenType.label, color = if (destructive) c.statusFailed else c.textPrimary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now", style = AnzenType.label, color = c.textSecondary) } },
    )
}

// ------------------------------------------------------------------ inputs

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
          supporting: String? = null, error: String? = null, singleLine: Boolean = true, minLines: Int = 1,
          keyboard: KeyboardType = KeyboardType.Text, suffix: String? = null) {
    val c = Anzen.colors
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = modifier.fillMaxWidth(), singleLine = singleLine,
        minLines = minLines, label = { Text(label) }, isError = error != null, shape = Radius.small,
        textStyle = AnzenType.body.copy(color = c.textPrimary),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        suffix = suffix?.let { { Text(it, style = AnzenType.callout, color = c.textSecondary) } },
        supportingText = (error ?: supporting)?.let { { Text(it, style = AnzenType.caption) } },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.focusRing, unfocusedBorderColor = c.borderStrong, focusedLabelColor = c.textSecondary,
            unfocusedLabelColor = c.textSecondary, cursorColor = c.textPrimary, errorBorderColor = c.statusFailed,
            focusedContainerColor = c.backgroundElevated, unfocusedContainerColor = c.backgroundElevated,
            focusedSupportingTextColor = c.textSecondary, unfocusedSupportingTextColor = c.textSecondary),
    )
}

@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    val c = Anzen.colors
    Row(modifier.fillMaxWidth().clip(Radius.small).background(c.surfaceSecondary).padding(3.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        options.forEach { o ->
            val on = o == selected
            val bg by animateColorAsState(if (on) c.backgroundElevated else Color.Transparent, tween(Motion.micro), label = "seg")
            Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedSmall).background(bg)
                .selectable(on, role = Role.RadioButton) { onSelect(o) }, contentAlignment = Alignment.Center) {
                Text(label(o), style = AnzenType.caption, color = if (on) c.textPrimary else c.textSecondary,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = Spacing.s))
            }
        }
    }
}

private val RoundedSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)

/** A choice with an explanation: settlement rules, appearance, check-in method. */
@Composable
fun ChoiceRow(title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    val c = Anzen.colors
    Row(Modifier.fillMaxWidth().clip(Radius.medium)
        .border(if (selected) 2.dp else 1.dp, if (selected) c.textPrimary else c.borderSubtle, Radius.medium)
        .background(c.backgroundElevated).selectable(selected, role = Role.RadioButton, onClick = onClick)
        .padding(Spacing.l), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 2.dp).size(20.dp).border(2.dp, if (selected) c.textPrimary else c.borderStrong, CircleShape),
            contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).background(c.textPrimary, CircleShape))
        }
        Spacer(Modifier.width(Spacing.m))
        Column {
            Text(title, style = AnzenType.headline, color = c.textPrimary)
            Gap(2)
            Text(body, style = AnzenType.callout, color = c.textSecondary)
        }
    }
}

// ------------------------------------------------------------------ status

enum class Tone { Verified, Pending, Failed, Info, Neutral, Accent }

/** Status is never color alone: an icon/shape and a text label always go with it. */
@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val c = Anzen.colors
    val color = when (tone) {
        Tone.Verified -> c.statusVerified
        Tone.Pending -> c.statusPending
        Tone.Failed -> c.statusFailed
        Tone.Info -> c.statusInfo
        Tone.Neutral -> c.textSecondary
        Tone.Accent -> c.onAccent
    }
    val bg = if (tone == Tone.Accent) c.accent else c.tint(color)
    Row(modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        StatusGlyph(tone, color, 12.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, style = AnzenType.caption, color = color, maxLines = 1)
    }
}

/** Shape encodes status too: check (verified), clock (pending), cross (failed), dot (other). */
@Composable
fun StatusGlyph(tone: Tone, color: Color, size: Dp) = Canvas(Modifier.size(size)) {
    val w = this.size.minDimension
    val stroke = Stroke(w * 0.16f, cap = StrokeCap.Round)
    when (tone) {
        Tone.Verified -> {
            drawCircle(color, w / 2)
            drawLine(Color.White, Offset(w * 0.28f, w * 0.52f), Offset(w * 0.44f, w * 0.68f), w * 0.14f, StrokeCap.Round)
            drawLine(Color.White, Offset(w * 0.44f, w * 0.68f), Offset(w * 0.74f, w * 0.36f), w * 0.14f, StrokeCap.Round)
        }
        Tone.Pending -> {
            drawCircle(color, w / 2 - stroke.width / 2, style = stroke)
            drawLine(color, Offset(w / 2, w / 2), Offset(w / 2, w * 0.28f), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(w / 2, w / 2), Offset(w * 0.68f, w * 0.58f), stroke.width, StrokeCap.Round)
        }
        Tone.Failed -> {
            drawCircle(color, w / 2)
            drawLine(Color.White, Offset(w * 0.34f, w * 0.34f), Offset(w * 0.66f, w * 0.66f), w * 0.14f, StrokeCap.Round)
            drawLine(Color.White, Offset(w * 0.66f, w * 0.34f), Offset(w * 0.34f, w * 0.66f), w * 0.14f, StrokeCap.Round)
        }
        else -> drawCircle(color, w * 0.3f)
    }
}

/** Always visible on money screens off mainnet: which network, and that the asset has no value. */
@Composable
fun NetworkBadge(network: String?, modifier: Modifier = Modifier) {
    if (network == null) return
    StatusPill("$network · test dollars", Tone.Info, modifier)
}

@Composable
fun InfoNote(text: String, tone: Tone = Tone.Info, modifier: Modifier = Modifier) {
    val c = Anzen.colors
    val color = when (tone) {
        Tone.Failed -> c.statusFailed
        Tone.Pending -> c.statusPending
        Tone.Verified -> c.statusVerified
        else -> c.statusInfo
    }
    val icon: ImageVector = when (tone) {
        Tone.Failed, Tone.Pending -> Icons.Rounded.Warning
        Tone.Verified -> Icons.Rounded.CheckCircle
        else -> Icons.Rounded.Info
    }
    Row(modifier.fillMaxWidth().clip(Radius.small).background(c.tint(color)).padding(Spacing.m),
        verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Spacer(Modifier.width(Spacing.s))
        Text(text, style = AnzenType.callout, color = c.textPrimary)
    }
}

// ------------------------------------------------------------------ data display

@Composable
fun AmountText(base: Long, symbol: String, style: TextStyle = AnzenType.value, color: Color = Anzen.colors.textPrimary,
               modifier: Modifier = Modifier) = Row(modifier, verticalAlignment = Alignment.Bottom) {
    Text(Format.amount(base), style = style, color = color)
    Spacer(Modifier.width(6.dp))
    Text(symbol, style = AnzenType.headline, color = color.copy(alpha = 0.72f), modifier = Modifier.padding(bottom = 4.dp))
}

/** Label/value line. [mono] for identifiers; [copy] makes the value copyable. */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false, copy: String? = null,
            valueColor: Color = Anzen.colors.textPrimary) {
    val c = Anzen.colors
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val haptics = rememberHaptics()
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp)
        .then(if (copy != null) Modifier.clickable(onClickLabel = "Copy $label") {
            copyText(ctx, label, copy); haptics.confirm(); toast("$label copied")
        } else Modifier)
        .padding(vertical = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = AnzenType.callout, color = c.textSecondary, modifier = Modifier.widthIn(max = 160.dp))
        Spacer(Modifier.width(Spacing.l).weight(1f))
        Text(value, style = if (mono) AnzenType.mono else AnzenType.valueSmall.copy(fontWeight = null),
            color = valueColor, textAlign = TextAlign.End)
    }
}

@Composable
fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Anzen.colors.borderSubtle))

/** A transaction hash: readable, copyable, and openable in the explorer when the network has one. */
@Composable
fun TxReference(label: String, tx: String, explorer: String?, status: String? = null) {
    val c = Anzen.colors
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = AnzenType.callout, color = c.textSecondary, modifier = Modifier.weight(1f))
            status?.let { StatusPill(it.replaceFirstChar(Char::uppercase), txTone(it)) }
        }
        Gap(4)
        Row(verticalAlignment = Alignment.CenterVertically) {
            InfoRow("Transaction", Format.shortHex(tx, 10, 8), mono = true, copy = tx, modifier = Modifier.weight(1f))
            if (explorer != null) QuietButton("Open") { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(explorer))) }
        }
    }
}

fun txTone(status: String) = when (status) {
    "confirmed" -> Tone.Verified
    "failed" -> Tone.Failed
    else -> Tone.Pending
}

fun copyText(ctx: Context, label: String, text: String) =
    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))

// ------------------------------------------------------------------ states

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) =
    Column(modifier.fillMaxWidth().padding(vertical = Spacing.xxl, horizontal = Spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally) {
        BrandMark(Modifier.size(44.dp), muted = true)
        Gap(16)
        Text(title, style = AnzenType.headline, color = Anzen.colors.textPrimary, textAlign = TextAlign.Center)
        Gap(6)
        Text(body, style = AnzenType.callout, color = Anzen.colors.textSecondary, textAlign = TextAlign.Center)
        if (action != null) {
            Gap(16)
            SecondaryButton(action, Modifier.widthIn(max = 280.dp), onClick = onAction)
        }
    }

@Composable
fun ErrorState(reason: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) = Column(modifier.fillMaxWidth()) {
    InfoNote(ErrorCopy.of(reason), Tone.Failed)
    if (onRetry != null) QuietButton("Try again", onClick = onRetry)
}

@Composable
fun LoadingState(text: String = "Loading", modifier: Modifier = Modifier) =
    Row(modifier.fillMaxWidth().padding(Spacing.xl).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), color = Anzen.colors.textSecondary, strokeWidth = 2.dp)
        Spacer(Modifier.width(Spacing.m))
        Text(text, style = AnzenType.callout, color = Anzen.colors.textSecondary)
    }

/** Placeholder block while a list loads (no shimmer loop). */
@Composable
fun SkeletonCard() = Box(Modifier.fillMaxWidth().height(96.dp).clip(Radius.medium).background(Anzen.colors.surfaceSecondary))

@Composable
fun FadeIn(visible: Boolean, content: @Composable () -> Unit) =
    AnimatedVisibility(visible, enter = fadeIn(tween(Motion.standard)), exit = fadeOut(tween(Motion.micro))) { content() }

// ------------------------------------------------------------------ brand

/**
 * The ANZEN mark: a ring left open at the upper right, closed by a lime seal: a commitment, sealed.
 * Same geometry as res/drawable/ic_launcher_foreground.xml.
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier, muted: Boolean = false, onSealed: Boolean = false) {
    val c = Anzen.colors
    val ring = when {
        onSealed -> c.onSealed
        muted -> c.textTertiary
        else -> c.textPrimary
    }
    val seal = if (muted) c.textTertiary else c.accent
    Canvas(modifier.semantics { contentDescription = "ANZEN" }) { drawMark(ring, seal) }
}

fun DrawScope.drawMark(ring: Color, seal: Color) {
    val w = size.minDimension
    val stroke = w * 0.13f
    val inset = stroke / 2 + w * 0.06f
    // Ring from -10° clockwise through 290°, leaving a gap centred at -45° (upper right) for the seal.
    drawArc(ring, startAngle = -10f, sweepAngle = 290f, useCenter = false, topLeft = Offset(inset, inset),
        size = Size(w - 2 * inset, w - 2 * inset), style = Stroke(stroke, cap = StrokeCap.Round))
    val r = (w - 2 * inset) / 2
    val a = Math.toRadians(-45.0)
    drawCircle(seal, w * 0.15f, Offset(w / 2 + r * kotlin.math.cos(a).toFloat(), w / 2 + r * kotlin.math.sin(a).toFloat()))
}

/** A QR code drawn from bits (no bitmap). Quiet zone included. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier, description: String) {
    val bits = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
    }
    Canvas(modifier.background(Color.White).semantics { contentDescription = description }) {
        val cell = size.width / bits.width
        for (y in 0 until bits.height) for (x in 0 until bits.width) if (bits[x, y])
            drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
    }
}

@Composable
fun FullScreenCenter(content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.fillMaxSize().background(Anzen.colors.backgroundPrimary).padding(Spacing.gutter),
    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally, content = content)

val ContentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp)
