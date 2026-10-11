package com.anzen.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anzen.core.ErrorCopy
import com.anzen.core.Event
import com.anzen.core.Format
import com.anzen.core.NextAction
import com.anzen.core.TxState
import com.anzen.core.nextAction
import com.anzen.ui.theme.Anzen
import com.anzen.ui.theme.AnzenType
import com.anzen.ui.theme.Motion
import com.anzen.ui.theme.Palette
import com.anzen.ui.theme.Radius
import com.anzen.ui.theme.Spacing

/** Six artwork families the organizer picks from. Generated, so no image is uploaded or hosted. */
private val ARTWORK = listOf(
    Palette.Ink to Palette.SignalLime,
    Color(0xFF1F3A2E) to Color(0xFFE9E3D3),
    Color(0xFF2B2F6B) to Color(0xFFF2B880),
    Color(0xFFE8E2D4) to Palette.Ink,
    Color(0xFF6B2737) to Color(0xFFF6D6C8),
    Color(0xFF14454F) to Color(0xFFBFE3D9),
)
const val ARTWORK_COUNT = 6

@Composable
fun EventArtwork(style: Int, seed: Long, modifier: Modifier = Modifier) {
    val (bg, fg) = ARTWORK[style.mod(ARTWORK_COUNT)]
    Canvas(modifier.background(bg).semantics { contentDescription = "Event artwork" }) {
        val w = size.width
        val h = size.height
        val r = kotlin.random.Random(seed * 31 + style)
        when (style.mod(3)) {
            0 -> repeat(5) { i -> // concentric arcs from a corner
                drawCircle(fg.copy(alpha = 0.9f - i * 0.15f), radius = h * (0.25f + i * 0.22f),
                    center = Offset(w * 0.85f, h * 1.05f), style = Stroke(h * 0.035f))
            }
            1 -> repeat(7) { i -> // stacked bars, like a ticket stub
                val y = h * (0.12f + i * 0.12f)
                drawRect(fg.copy(alpha = 0.25f + 0.1f * (i % 3)), Offset(w * r.nextFloat() * 0.3f, y),
                    Size(w * (0.4f + r.nextFloat() * 0.5f), h * 0.05f))
            }
            else -> { // one large seal and a few small ones
                drawCircle(fg, h * 0.32f, Offset(w * 0.72f, h * 0.5f))
                repeat(4) { drawCircle(fg.copy(alpha = 0.45f), h * 0.05f, Offset(w * (0.12f + r.nextFloat() * 0.4f), h * (0.2f + r.nextFloat() * 0.6f))) }
            }
        }
    }
}

/** The short status for an event card, from onchain phase and this account's participation. */
fun eventStatus(e: Event): Pair<String, Tone> = when (nextAction(e)) {
    NextAction.Commit -> "Open" to Tone.Neutral
    NextAction.Full -> "Full" to Tone.Neutral
    NextAction.Closed -> "Closed" to Tone.Neutral
    NextAction.Cancelled -> "Cancelled" to Tone.Failed
    NextAction.RefundAvailable -> "Refund available" to Tone.Pending
    NextAction.WaitForCheckIn -> if (e.me?.attended == true) "Checked in" to Tone.Verified else "Committed" to Tone.Accent
    NextAction.CheckIn -> "Check in now" to Tone.Pending
    NextAction.Missed -> "Missed" to Tone.Failed
    NextAction.Settle -> "Ready to settle" to Tone.Pending
    NextAction.ViewResult -> "Settled" to Tone.Verified
}

@Composable
fun EventCard(e: Event, symbol: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Anzen.colors
    val (status, tone) = eventStatus(e)
    Panel(modifier, onClick = onClick, padding = 0.dp) {
        Row(Modifier.padding(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            EventArtwork(e.artwork, e.id, Modifier.size(76.dp).clip(Radius.small))
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f)) {
                Text(e.title, style = AnzenType.headline, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Gap(2)
                Text("${e.creator} · ${Format.dateTime(e.startsAt)}", style = AnzenType.caption, color = c.textSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Gap(8)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(status, tone)
                    Spacer(Modifier.weight(1f))
                    Text("${Format.amount(e.amount)} $symbol", style = AnzenType.caption, color = c.textPrimary)
                }
            }
        }
    }
}

/** Seats as a thin bar plus the exact numbers (the bar alone never carries the information). */
@Composable
fun CapacityBar(committed: Int, capacity: Int) {
    val c = Anzen.colors
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Row {
            Text("$committed of $capacity committed", style = AnzenType.caption, color = c.textPrimary, modifier = Modifier.weight(1f))
            Text("${(capacity - committed).coerceAtLeast(0)} left", style = AnzenType.caption, color = c.textSecondary)
        }
        Gap(6)
        Box(Modifier.fillMaxWidth().height(6.dp).clip(Radius.small).background(c.surfaceSecondary)) {
            Box(Modifier.fillMaxWidth((committed.toFloat() / capacity).coerceIn(0f, 1f)).height(6.dp).background(c.textPrimary))
        }
    }
}

/** Supporter passport: only rendered for passports the contract actually issued (passportId > 0). */
@Composable
fun PassportCard(e: Event, passportId: Long, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = Anzen.colors
    Box(modifier.fillMaxWidth().aspectRatio(1.6f).clip(Radius.large).background(c.surfaceSealed)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        // Artwork as a strip on the right; text never sits on top of it.
        EventArtwork(e.artwork, e.id, Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.28f))
        Column(Modifier.fillMaxHeight().fillMaxWidth(0.72f).padding(Spacing.xl), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(Modifier.size(22.dp), onSealed = true)
                Spacer(Modifier.width(Spacing.s))
                Text("SUPPORTER PASSPORT", style = AnzenType.overline, color = c.onSealedSecondary)
            }
            Column {
                Text(e.title, style = AnzenType.title, color = c.onSealed, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Gap(4)
                Text("${e.creator} · ${Format.date(e.startsAt)}", style = AnzenType.caption, color = c.onSealedSecondary)
                Gap(10)
                Text("No. $passportId · issued onchain", style = AnzenType.caption, color = c.accent)
            }
        }
    }
}

/** The financial lifecycle, step by step. Success copy appears only at Verified. */
@Composable
fun TxProgress(state: TxState, verifiedText: String) {
    AnimatedContent(state, transitionSpec = { fadeIn(tween(Motion.standard)) togetherWith fadeOut(tween(Motion.micro)) },
        contentKey = { it::class }, label = "tx") { s ->
        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
            when (s) {
                TxState.Review -> {}
                TxState.Authorizing -> Step("Waiting for your authorization", "Confirm with your fingerprint, face or screen lock.", Tone.Pending, spinner = true)
                TxState.Submitting -> Step("Submitting", "Sending your signed action to the network.", Tone.Pending, spinner = true)
                is TxState.Pending -> Step("Pending", "Submitted. Waiting for the network to include it.", Tone.Pending, spinner = true, tx = s.tx)
                is TxState.Confirmed -> Step("Confirmed onchain", "Reading the treasury to verify the result.", Tone.Pending, spinner = true, tx = s.tx)
                is TxState.Verified -> Step(verifiedText, "Verified against the treasury contract.", Tone.Verified, tx = s.tx)
                is TxState.StillPending -> Step("Still pending", "It hasn't been confirmed yet. It may still go through; check Activity before trying again.", Tone.Pending, tx = s.tx)
                is TxState.Failed -> Step("Not completed", ErrorCopy.of(s.reason), Tone.Failed, tx = s.tx)
                TxState.Cancelled -> Step("Cancelled", "You cancelled the authorization. Nothing was signed or sent.", Tone.Neutral)
            }
        }
    }
}

@Composable
private fun Step(title: String, body: String, tone: Tone, spinner: Boolean = false, tx: String? = null) {
    val c = Anzen.colors
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (spinner) CircularProgressIndicator(Modifier.size(18.dp), color = c.statusPending, strokeWidth = 2.dp)
            else StatusGlyph(tone, when (tone) {
                Tone.Verified -> c.statusVerified
                Tone.Failed -> c.statusFailed
                else -> c.textSecondary
            }, 18.dp)
            Spacer(Modifier.width(Spacing.m))
            Text(title, style = AnzenType.headline, color = c.textPrimary)
        }
        Gap(6)
        Text(body, style = AnzenType.callout, color = c.textSecondary)
        if (tx != null) {
            Gap(4)
            InfoRow("Transaction", Format.shortHex(tx, 10, 8), mono = true, copy = tx)
        }
    }
}
