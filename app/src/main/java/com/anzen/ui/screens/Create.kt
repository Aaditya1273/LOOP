package com.anzen.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anzen.core.DraftRules
import com.anzen.core.EventDraft
import com.anzen.core.Format
import com.anzen.core.TxState
import com.anzen.ui.ActionViewModel
import com.anzen.ui.AppViewModel
import com.anzen.ui.activity
import com.anzen.ui.components.ARTWORK_COUNT
import com.anzen.ui.components.ChoiceRow
import com.anzen.ui.components.ConfirmDialog
import com.anzen.ui.components.Divider
import com.anzen.ui.components.EventArtwork
import com.anzen.ui.components.Field
import com.anzen.ui.components.Gap
import com.anzen.ui.components.InfoNote
import com.anzen.ui.components.InfoRow
import com.anzen.ui.components.LargeTitle
import com.anzen.ui.components.LocalToast
import com.anzen.ui.components.NetworkBadge
import com.anzen.ui.components.Panel
import com.anzen.ui.components.PrimaryButton
import com.anzen.ui.components.SecondaryButton
import com.anzen.ui.components.SectionHeader
import com.anzen.ui.components.Tone
import com.anzen.ui.components.TopBar
import com.anzen.ui.components.TxProgress
import com.anzen.ui.theme.Anzen
import com.anzen.ui.theme.AnzenType
import com.anzen.ui.theme.Motion
import com.anzen.ui.theme.Radius
import com.anzen.ui.theme.Spacing
import com.anzen.ui.theme.rememberHaptics
import java.util.Calendar

private val STEP_TITLES = listOf("Basics", "Schedule", "Treasury", "Check-in", "Settlement", "Review")

@Composable
fun CreateScreen(vm: AppViewModel, onClose: () -> Unit, onCreated: (Long) -> Unit) {
    val c = Anzen.colors
    val toast = LocalToast.current
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    val s by vm.state.collectAsStateWithLifecycle()
    val action: ActionViewModel = hiltViewModel()
    val tx by action.tx.collectAsStateWithLifecycle()
    var d by remember { mutableStateOf(vm.repo.prefs.draft ?: EventDraft()) }
    var created by remember { mutableLongStateOf(0L) }
    var confirmLeave by remember { mutableStateOf(false) }
    val now = s.health?.now ?: (System.currentTimeMillis() / 1000)
    val publishing = tx !is TxState.Review && tx !is TxState.Cancelled && tx !is TxState.Failed

    fun update(next: EventDraft) { d = next; vm.repo.prefs.draft = next }
    fun close() {
        when {
            publishing && tx !is TxState.Verified -> confirmLeave = true
            else -> { if (d != EventDraft()) toast("Draft saved"); onClose() }
        }
    }
    BackHandler { if (d.step > 0 && !publishing) update(d.copy(step = d.step - 1)) else close() }
    LaunchedEffect(tx, created) {
        if (tx is TxState.Verified && created > 0) {
            haptics.confirm()
            vm.repo.prefs.draft = null
            onCreated(created)
        }
        if (tx is TxState.Failed) haptics.reject()
    }

    val problems = DraftRules.problems(d, d.step, now)
    Column(Modifier.fillMaxSize().background(c.backgroundPrimary).imePadding()) {
        TopBar("New event", onBack = ::close)
        // Progress: six segments, the current one filled. Also announced as text.
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(DraftRules.STEPS) { i ->
                Box(Modifier.weight(1f).height(4.dp).clip(Radius.small).background(if (i <= d.step) c.textPrimary else c.surfaceSecondary))
            }
        }
        Text("Step ${d.step + 1} of ${DraftRules.STEPS} · ${STEP_TITLES[d.step]}", style = AnzenType.caption, color = c.textSecondary,
            modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s))
        AnimatedContent(d.step, Modifier.weight(1f), label = "step",
            transitionSpec = { fadeIn(tween(Motion.standard)) togetherWith fadeOut(tween(Motion.micro)) }) { step ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter)) {
                when (step) {
                    0 -> Basics(d, ::update)
                    1 -> Schedule(d, ::update, ctx, now)
                    2 -> Treasury(d, ::update, s.health?.asset?.symbol ?: "tUSD")
                    3 -> Verification(d, ::update, s.health?.issuer)
                    4 -> Settlement(d, ::update)
                    else -> Review(d, s.health?.network, s.health?.asset?.symbol ?: "tUSD", problems, tx)
                }
                Gap(24)
            }
        }
        Divider()
        Column(Modifier.fillMaxWidth().background(c.backgroundElevated).navigationBarsPadding().padding(Spacing.gutter)) {
            if (d.step < 5) {
                if (problems.isNotEmpty()) Text(problems.first(), style = AnzenType.caption, color = c.textSecondary,
                    modifier = Modifier.padding(bottom = Spacing.s))
                PrimaryButton("Continue", enabled = problems.isEmpty()) { update(d.copy(step = d.step + 1)) }
            } else if (tx != TxState.Review) { TxProgress(tx, "Event published"); Gap(12) }
            if (d.step == 5) when (tx) {
                TxState.Review, TxState.Cancelled, is TxState.Failed -> PrimaryButton("Authorize and publish", accent = true,
                    enabled = problems.isEmpty() && s.health != null && s.hasKey) {
                    ctx.activity()?.let { a -> action.run { createEvent(a, d, it, onCreated = { id -> created = id }) } }
                }
                is TxState.StillPending -> SecondaryButton("Close") { onClose() }
                else -> PrimaryButton("Publishing…", loading = true) {}
            }
        }
    }
    if (confirmLeave) ConfirmDialog("Leave while publishing?", "Publishing continues in the background. Check Activity before " +
        "trying again so you don't publish twice.", "Leave", onConfirm = { confirmLeave = false; onClose() },
        onDismiss = { confirmLeave = false })
}

@Composable
private fun Basics(d: EventDraft, update: (EventDraft) -> Unit) {
    LargeTitle("The event", Modifier.padding(bottom = Spacing.l), subtitle = "What supporters see first.")
    Field("Title", d.title, { update(d.copy(title = it.take(80))) }, supporting = "${d.title.length}/80")
    Gap(12)
    Field("Creator name", d.creator, { update(d.copy(creator = it.take(60))) })
    Gap(12)
    Field("Description", d.description, { update(d.copy(description = it.take(2000))) }, singleLine = false, minLines = 4)
    SectionHeader("Artwork")
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        repeat(ARTWORK_COUNT) { i ->
            val on = d.artwork == i
            EventArtwork(i, 7, Modifier.weight(1f).height(56.dp).clip(Radius.small)
                .border(if (on) 3.dp else 0.dp, if (on) Anzen.colors.textPrimary else Anzen.colors.backgroundPrimary, Radius.small)
                .selectable(on, role = Role.RadioButton) { update(d.copy(artwork = i)) })
        }
    }
}

@Composable
private fun Schedule(d: EventDraft, update: (EventDraft) -> Unit, ctx: Context, now: Long) {
    LargeTitle("When and where", Modifier.padding(bottom = Spacing.l), subtitle = "Times are in ${d.timezone}.")
    Field("Venue or meeting point", d.venue, { update(d.copy(venue = it.take(200))) })
    Gap(12)
    TimeField("Event starts", d.startsAt, ctx, now) { t ->
        // First pick fills sensible defaults around the start time; each stays editable.
        update(if (d.startsAt == 0L) d.copy(startsAt = t, commitDeadline = t - 2 * 3600, checkInStart = t - 30 * 60, checkInEnd = t + 2 * 3600)
        else d.copy(startsAt = t))
    }
    SectionHeader("Commitments")
    TimeField("Commitments close", d.commitDeadline, ctx, now) { update(d.copy(commitDeadline = it)) }
    SectionHeader("Check-in window")
    TimeField("Check-in opens", d.checkInStart, ctx, now) { update(d.copy(checkInStart = it)) }
    Gap(8)
    TimeField("Check-in closes", d.checkInEnd, ctx, now) { update(d.copy(checkInEnd = it)) }
    Gap(12)
    InfoNote("Attendees can only check in inside this window. It's your grace period too: late arrivals can check in " +
        "until it closes, and nobody can after.")
}

@Composable
private fun TimeField(label: String, epoch: Long, ctx: Context, now: Long, onPick: (Long) -> Unit) {
    val c = Anzen.colors
    Row(Modifier.fillMaxWidth().clip(Radius.small).background(c.backgroundElevated).border(1.dp, c.borderStrong, Radius.small)
        .clickable(onClickLabel = "Choose $label") { pickDateTime(ctx, epoch, now, onPick) }.padding(Spacing.l),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = AnzenType.callout, color = c.textSecondary, modifier = Modifier.weight(1f))
        Text(if (epoch > 0) Format.dateTime(epoch) else "Choose", style = AnzenType.headline, color = c.textPrimary)
    }
}

/** Native date then time pickers (respect the system's 12/24-hour setting). [now] is chain time, which every
 *  deadline is checked against; it can differ from the phone's clock. */
private fun pickDateTime(ctx: Context, epoch: Long, now: Long, onPick: (Long) -> Unit) {
    val cal = Calendar.getInstance().apply { timeInMillis = (if (epoch > 0) epoch else now + 7 * 86_400) * 1000 }
    DatePickerDialog(ctx, { _, y, m, day ->
        TimePickerDialog(ctx, { _, hour, minute ->
            cal.set(y, m, day, hour, minute, 0)
            onPick(cal.timeInMillis / 1000)
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), DateFormat.is24HourFormat(ctx)).show()
    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).apply {
        datePicker.minDate = now * 1000 - 60_000
    }.show()
}

@Composable
private fun Treasury(d: EventDraft, update: (EventDraft) -> Unit, symbol: String) {
    LargeTitle("The commitment", Modifier.padding(bottom = Spacing.l), subtitle = "What each supporter puts in.")
    Field("Amount per person", d.amountUnits, { update(d.copy(amountUnits = it.filter { ch -> ch.isDigit() || ch == '.' }.take(14))) },
        keyboard = KeyboardType.Decimal, suffix = symbol, supporting = "Up to 6 decimal places")
    Gap(12)
    Field("Capacity", d.capacity, { update(d.copy(capacity = it.filter(Char::isDigit).take(5))) }, keyboard = KeyboardType.Number,
        suffix = "people")
    Gap(12)
    InfoNote("The only supported asset is $symbol, a test dollar with no value. Supporters commit from their ANZEN " +
        "balance; nobody pays network fees.")
}

@Composable
private fun Verification(d: EventDraft, update: (EventDraft) -> Unit, issuer: String?) {
    LargeTitle("Check-in", Modifier.padding(bottom = Spacing.l), subtitle = "How attendance is evidenced.")
    ChoiceRow("Rotating venue code", "You open a check-in desk on your phone. It shows a code that changes every 35 s and " +
        "works for 45 s. Attendees scan it and sign it with their key.", selected = true) {}
    Gap(12)
    Panel {
        InfoRow("Code issuer", issuer?.let { Format.shortHex(it) } ?: "—", mono = true, copy = issuer)
        Gap(4)
        Text("ANZEN's check-in service signs the codes, only for a desk your key opened. That's the trust assumption " +
            "attendees accept.", style = AnzenType.caption, color = Anzen.colors.textSecondary)
    }
    Gap(12)
    InfoNote("This shows a supporter's phone signed a fresh code from your desk. It doesn't prove where they were: anyone " +
        "who can see a live code can use it until it expires.", Tone.Pending)
    SectionHeader("Disputes")
    Field("Dispute contact", d.disputeContact, { update(d.copy(disputeContact = it.take(200))) },
        supporting = "Email or handle. The onchain result is final, so disputes are settled with you directly.")
}

@Composable
private fun Settlement(d: EventDraft, update: (EventDraft) -> Unit) {
    LargeTitle("Outcomes", Modifier.padding(bottom = Spacing.l), subtitle = "Fixed once published.")
    Text("If a supporter doesn't check in", style = AnzenType.headline, color = Anzen.colors.textPrimary)
    Gap(8)
    ChoiceRow("Share it among attendees", "Each attendee gets their commitment back plus an equal share. Rounding " +
        "remainders, and everything if nobody comes, go to you.", d.noShow == 1) { update(d.copy(noShow = 1)) }
    Gap(8)
    ChoiceRow("It goes to the organizer", "Attendees get exactly their commitment back. No-shows' commitments go to you.",
        d.noShow == 0) { update(d.copy(noShow = 0)) }
    SectionHeader("Always")
    Panel {
        InfoRow("Attendees", "Commitment back + passport")
        InfoRow("You cancel", "Full refunds, until check-in opens")
        InfoRow("Changing terms", "Not possible after publishing")
    }
}

@Composable
private fun Review(d: EventDraft, network: String?, symbol: String, problems: List<String>, tx: TxState) {
    LargeTitle("Review", Modifier.padding(bottom = Spacing.l), subtitle = "This is exactly what supporters agree to.")
    EventArtwork(d.artwork, 7, Modifier.fillMaxWidth().height(120.dp).clip(Radius.medium))
    Gap(12)
    Text(d.title.ifBlank { "Untitled" }, style = AnzenType.title, color = Anzen.colors.textPrimary)
    Text("by ${d.creator}", style = AnzenType.callout, color = Anzen.colors.textSecondary)
    Gap(12)
    Panel {
        InfoRow("Starts", if (d.startsAt > 0) Format.dateTime(d.startsAt) else "—")
        InfoRow("Where", d.venue)
        InfoRow("Commitment", "${d.amountUnits} $symbol")
        InfoRow("Capacity", "${d.capacity} people")
        InfoRow("Commitments close", if (d.commitDeadline > 0) Format.dateTime(d.commitDeadline) else "—")
        InfoRow("Check-in", if (d.checkInStart > 0) "${Format.dateTime(d.checkInStart)} – ${Format.time(d.checkInEnd)}" else "—")
        InfoRow("No-shows", if (d.noShow == 1) "Shared among attendees" else "To organizer")
        InfoRow("Disputes", d.disputeContact)
        InfoRow("Network fee", "None for you")
    }
    Gap(12)
    NetworkBadge(network)
    Gap(12)
    if (problems.isNotEmpty()) problems.forEach { InfoNote(it, Tone.Failed, Modifier.padding(bottom = Spacing.s)) }
    else InfoNote("Publishing asks for your authorization. The terms' fingerprint (sha256) is fixed in the treasury, so " +
        "nobody, including you, can change them after supporters commit.")
}
