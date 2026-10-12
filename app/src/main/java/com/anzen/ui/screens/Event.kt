package com.anzen.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anzen.core.Event
import com.anzen.core.Format
import com.anzen.core.NextAction
import com.anzen.core.Outcome
import com.anzen.core.Repository
import com.anzen.core.TxState
import com.anzen.core.canRelease
import com.anzen.core.feeText
import com.anzen.core.nextAction
import com.anzen.core.releaseTerms
import com.anzen.core.outcome
import com.anzen.ui.ActionViewModel
import com.anzen.ui.AppViewModel
import com.anzen.ui.activity
import com.anzen.ui.components.AmountText
import com.anzen.ui.components.CapacityBar
import com.anzen.ui.components.ConfirmDialog
import com.anzen.ui.components.Divider
import com.anzen.ui.components.ErrorState
import com.anzen.ui.components.EventArtwork
import com.anzen.ui.components.Gap
import com.anzen.ui.components.InfoNote
import com.anzen.ui.components.InfoRow
import com.anzen.ui.components.LargeTitle
import com.anzen.ui.components.LoadingState
import com.anzen.ui.components.NetworkBadge
import com.anzen.ui.components.Panel
import com.anzen.ui.components.PrimaryButton
import com.anzen.ui.components.SealedPanel
import com.anzen.ui.components.SecondaryButton
import com.anzen.ui.components.SectionHeader
import com.anzen.ui.components.StatusPill
import com.anzen.ui.components.Tone
import com.anzen.ui.components.TopBar
import com.anzen.ui.components.TxProgress
import com.anzen.ui.components.TxReference
import com.anzen.ui.components.eventStatus
import com.anzen.ui.theme.Anzen
import com.anzen.ui.theme.AnzenType
import com.anzen.ui.theme.Radius
import com.anzen.ui.theme.Spacing
import com.anzen.ui.theme.rememberHaptics

/** Loads one event (with this account's participation). Bump [version] to reload after an action. */
@Composable
fun rememberEvent(id: Long, vm: AppViewModel, version: Int = 0): Result<Event>? =
    produceState<Result<Event>?>(null, id, version) { value = runCatching { vm.repo.event(id) } }.value

/** Standard pushed-screen frame: back bar, scrolling content, optional pinned bottom action. */
@Composable
fun Pushed(title: String, back: () -> Unit, bottom: (@Composable ColumnScope.() -> Unit)? = null,
           content: @Composable ColumnScope.() -> Unit) {
    val c = Anzen.colors
    Column(Modifier.fillMaxSize().background(c.backgroundPrimary)) {
        TopBar(title, onBack = back)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter)) {
            content()
            Gap(32)
        }
        if (bottom != null) {
            Divider()
            Column(Modifier.fillMaxWidth().background(c.backgroundElevated).navigationBarsPadding().padding(Spacing.gutter),
                content = bottom)
        }
    }
}

@Composable
fun Loaded(r: Result<Event>?, retry: () -> Unit, content: @Composable (Event) -> Unit) {
    when {
        r == null -> LoadingState()
        r.isFailure -> ErrorState(Repository.reasonOf(r.exceptionOrNull()!!), Modifier.padding(Spacing.gutter), retry)
        else -> content(r.getOrThrow())
    }
}

@Composable
fun EventScreen(id: Long, vm: AppViewModel, back: () -> Unit, go: (String) -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    val r = rememberEvent(id, vm, version)
    val s by vm.state.collectAsStateWithLifecycle()
    val e = r?.getOrNull()
    val symbol = s.health?.asset?.symbol ?: "tUSD"
    val mine = e != null && e.organizer == vm.repo.keyId
    val cancelVm: ActionViewModel = hiltViewModel()
    val cancelTx by cancelVm.tx.collectAsStateWithLifecycle()
    var confirmCancel by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    LaunchedEffect(cancelTx) { if (cancelTx is TxState.Verified) version++ }

    Pushed("", back, bottom = e?.let { ev -> { EventAction(ev, go) } }) {
        Loaded(r, { version++ }) { ev ->
            EventArtwork(ev.artwork, ev.id, Modifier.fillMaxWidth().height(180.dp).clip(Radius.large))
            Gap(20)
            val (status, tone) = eventStatus(ev)
            StatusPill(status, tone)
            Gap(10)
            Text(ev.title, style = AnzenType.largeTitle, color = Anzen.colors.textPrimary)
            Gap(4)
            Text("by ${ev.creator}", style = AnzenType.callout, color = Anzen.colors.textSecondary)
            Gap(20)
            SealedPanel {
                Text("Commitment per person", style = AnzenType.caption, color = Anzen.colors.onSealedSecondary)
                Gap(4)
                AmountText(ev.amount, symbol, color = Anzen.colors.onSealed)
                Gap(8)
                Text(attendOutcomeText(ev, symbol), style = AnzenType.callout, color = Anzen.colors.onSealed)
                Gap(12)
                NetworkBadge(s.health?.network, onSealed = true)
            }
            if (!ev.policyVerified) {
                Gap(12)
                InfoNote("The published terms don't match the hash fixed onchain. Don't commit to this event.", Tone.Failed)
            }
            SectionHeader("When and where")
            Panel {
                InfoRow("Starts", Format.dateTime(ev.startsAt))
                if (ev.timezone != java.time.ZoneId.systemDefault().id) InfoRow("Local to venue", Format.dateTime(ev.startsAt, ev.timezone))
                InfoRow("Where", ev.venue)
                if (ev.categoryName.isNotBlank()) InfoRow("Kind", ev.categoryName)
                InfoRow("Commitments close", Format.dateTime(ev.commitDeadline))
                InfoRow("Check-in", "${Format.dateTime(ev.checkInStart)} – ${Format.time(ev.checkInEnd)}")
                Gap(8)
                CapacityBar(ev.committed, ev.capacity)
                if (ev.waitlisted > 0) { Gap(6); Text("${ev.waitlisted} on the waitlist", style = AnzenType.caption, color = Anzen.colors.textSecondary) }
            }
            SectionHeader("Terms")
            Panel { Terms(ev, symbol) }
            if (ev.description.isNotBlank()) {
                SectionHeader("About")
                Text(ev.description, style = AnzenType.body, color = Anzen.colors.textPrimary)
            }
            ev.me?.takeIf { it.status != "none" }?.let { me ->
                SectionHeader("Your status")
                Panel {
                    InfoRow("Commitment", when (me.status) {
                        "waitlisted" -> "On the waitlist · ${Format.amount(ev.amount)} $symbol held"
                        "released" -> "Place given back"
                        else -> me.status.replace('_', ' ').replaceFirstChar(Char::uppercase)
                    })
                    if (me.byAgent) InfoRow("Reserved by", "Your AI agent")
                    if (me.attended) InfoRow("Checked in", Format.dateTime(me.checkedInAt))
                    if (me.tier > 0) InfoRow("Check-in method", if (me.tier == 2) "Staff scanned your code" else "You scanned the venue code")
                    if (me.status == "settled") InfoRow("Returned to you", "${Format.amount(me.settledAmount)} $symbol")
                    if (canRelease(ev)) {
                        Gap(8)
                        SecondaryButton(if (me.status == "waitlisted") "Leave the waitlist" else "Give my place back") { go("release/${ev.id}") }
                    }
                    vm.repo.keyId?.let { k -> Gap(8); SecondaryButton("View proof") { go("proof/${ev.id}/$k") } }
                }
            }
            if (mine) {
                SectionHeader("Organizer")
                Panel {
                    Text("You published this event with this phone's key.", style = AnzenType.callout, color = Anzen.colors.textSecondary)
                    Gap(12)
                    if (!ev.cancelled && ev.phase in setOf("open", "closed", "check_in")) {
                        PrimaryButton("Open check-in desk") { go("desk/${ev.id}") }
                        Gap(8)
                    }
                    SecondaryButton("Treasury") { go("treasury/${ev.id}") }
                    Gap(8)
                    SecondaryButton("Venue dashboard") { go("dashboard") }
                    if (!ev.cancelled && ev.phase in setOf("open", "closed")) {
                        Gap(8)
                        if (cancelTx == TxState.Review || cancelTx is TxState.Cancelled) SecondaryButton("Cancel event", destructive = true) { confirmCancel = true }
                        else TxProgress(cancelTx, "Event cancelled")
                    }
                }
            }
            SectionHeader("Record")
            Panel {
                InfoRow("Event", "#${ev.id}", copy = ev.id.toString())
                InfoRow("Terms hash", Format.shortHex(ev.policyHash), mono = true, copy = ev.policyHash)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Published terms", style = AnzenType.callout, color = Anzen.colors.textSecondary, modifier = Modifier.weight(1f))
                    if (ev.policyVerified) StatusPill("Match onchain hash", Tone.Verified) else StatusPill("Don't match", Tone.Failed)
                }
                TxReference("Published in", ev.createTx, s.health?.txLink(ev.createTx))
            }
            if (confirmCancel) ConfirmDialog("Cancel this event?",
                "Everyone who committed can take a full refund, and no passports are issued. This can't be undone.",
                "Cancel event", destructive = true,
                onConfirm = { confirmCancel = false; ctx.activity()?.let { a -> cancelVm.run { cancelEvent(a, ev, it) } } },
                onDismiss = { confirmCancel = false })
        }
    }
}

fun attendOutcomeText(e: Event, symbol: String): String = when (e.noShow) {
    "split" -> "Attend and it comes back, plus an equal share of no-shows' commitments."
    else -> "Attend and it comes back. If you don't, it goes to the organizer."
}

@Composable
private fun Terms(e: Event, symbol: String) {
    val c = Anzen.colors
    @Composable
    fun term(title: String, body: String) {
        Column(Modifier.padding(vertical = Spacing.s)) {
            Text(title, style = AnzenType.headline, color = c.textPrimary)
            Gap(2)
            Text(body, style = AnzenType.callout, color = c.textSecondary)
        }
    }
    term("If you check in", "Your ${Format.amount(e.amount)} $symbol comes back" +
        (if (e.noShow == "split") ", plus an equal share of what no-shows committed (rounding remainder goes to the organizer)." else ".") +
        " You also receive this event's supporter passport, recorded onchain.")
    Divider()
    term("If you don't", if (e.noShow == "split") "Your commitment is shared equally among the people who checked in. If nobody checks in, it goes to the organizer."
        else "Your commitment goes to the organizer.")
    Divider()
    term("If you can't make it", "Give your place back before commitments close (${Format.dateTime(e.commitDeadline)}) for a full " +
        "refund; that also earns a streak shield. After that, " + (if (e.lateFeeBps > 0) "${feeText(e.lateFeeBps)} of the commitment " +
        "goes to the organizer, unless someone on the waitlist takes your place at once." else "it's still free.") +
        " Places can't be given back once check-in opens.")
    Divider()
    term("If it's full", "Committing puts you on the waitlist with your commitment held. If someone gives their place back, " +
        "the first person waiting gets it automatically. Otherwise the whole amount comes back after the event.")
    Divider()
    term("If it's cancelled", "The organizer can cancel until check-in opens. Everyone who committed can take a full refund.")
    Divider()
    term("How check-in works", "During the check-in window, scan the rotating code shown at the venue. Each code is valid for " +
        "45 seconds and is signed by the event's check-in issuer; your phone signs it with your key. This shows this phone " +
        "signed a fresh code. It doesn't prove where you were.")
    Divider()
    term("Disputes", "The treasury applies the result automatically and it can't be reversed onchain. Contact ${e.disputeContact} " +
        "about anything that went wrong.")
}

@Composable
private fun EventAction(e: Event, go: (String) -> Unit) {
    val c = Anzen.colors
    when (nextAction(e)) {
        NextAction.Commit -> PrimaryButton(if (e.committed >= e.capacity) "Join the waitlist" else "Review commitment",
            enabled = e.policyVerified) { go("commit/${e.id}") }
        NextAction.CheckIn -> PrimaryButton("Check in", accent = true) { go("checkin/${e.id}") }
        NextAction.Settle, NextAction.RefundAvailable, NextAction.ViewResult -> PrimaryButton("View outcome") { go("result/${e.id}") }
        NextAction.WaitForCheckIn -> {
            val text = if (e.me?.attended == true) "Checked in. The outcome is applied after check-in closes ${Format.relative(e.checkInEnd, e.now)}."
            else "Committed. Check-in opens ${Format.relative(e.checkInStart, e.now)} (${Format.dateTime(e.checkInStart)})."
            Text(text, style = AnzenType.callout, color = c.textSecondary)
        }
        NextAction.Full -> Text("This event and its waitlist are full.", style = AnzenType.callout, color = c.textSecondary)
        NextAction.Waitlisted -> Text("You're on the waitlist with ${Format.amount(e.amount)} held. If someone gives their place back, " +
            "it's yours automatically; if not, the whole amount comes back after the event.", style = AnzenType.callout, color = c.textSecondary)
        NextAction.Released -> Text("You gave your place back.", style = AnzenType.callout, color = c.textSecondary)
        NextAction.Closed -> Text("Commitments for this event are closed.", style = AnzenType.callout, color = c.textSecondary)
        NextAction.Cancelled -> Text("This event was cancelled.", style = AnzenType.callout, color = c.textSecondary)
        NextAction.Missed -> Text("Check-in has closed.", style = AnzenType.callout, color = c.textSecondary)
    }
}

/** Everything the user is agreeing to, then their authorization, then the real transaction lifecycle. */
@Composable
fun CommitScreen(id: Long, vm: AppViewModel, back: () -> Unit, go: (String) -> Unit) {
    val r = rememberEvent(id, vm)
    val s by vm.state.collectAsStateWithLifecycle()
    val action: ActionViewModel = hiltViewModel()
    val tx by action.tx.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    val h = s.health
    val symbol = h?.asset?.symbol ?: "tUSD"
    LaunchedEffect(tx) {
        if (tx is TxState.Verified) haptics.confirm()
        if (tx is TxState.Failed) haptics.reject()
    }
    val e = r?.getOrNull()
    val balance = s.account?.balance ?: 0
    Pushed("Review commitment", back, bottom = e?.let { ev ->
        {
            // Progress sits right above the button the user just pressed.
            val full = ev.committed >= ev.capacity
            if (tx != TxState.Review) { TxProgress(tx, if (full) "You're on the waitlist" else "Commitment recorded"); Gap(12) }
            when (tx) {
                TxState.Review, TxState.Cancelled -> PrimaryButton((if (full) "Join waitlist · " else "") + "Authorize ${Format.amount(ev.amount)} $symbol",
                    enabled = balance >= ev.amount && nextAction(ev) == NextAction.Commit && h != null) {
                    ctx.activity()?.let { a -> action.run { commit(a, ev, it) } }
                }
                is TxState.Verified -> PrimaryButton("Done") { back() }
                is TxState.Failed, is TxState.StillPending -> SecondaryButton("Back to event") { back() }
                else -> PrimaryButton("Working…", loading = true) {}
            }
        }
    }) {
        Loaded(r, {}) { ev ->
            LargeTitle(ev.title, Modifier.padding(vertical = Spacing.m), subtitle = "by ${ev.creator}")
            if (ev.committed >= ev.capacity) {
                InfoNote("This event is full, so this joins the waitlist. Your ${Format.amount(ev.amount)} $symbol is held. If someone " +
                    "gives their place back, the first person waiting gets it automatically. If nobody does, the whole amount " +
                    "comes back after the event.", Tone.Pending)
                Gap(12)
            }
            SealedPanel {
                Text("You commit", style = AnzenType.caption, color = Anzen.colors.onSealedSecondary)
                AmountText(ev.amount, symbol, color = Anzen.colors.onSealed)
                Gap(8)
                Text(attendOutcomeText(ev, symbol), style = AnzenType.callout, color = Anzen.colors.onSealed)
            }
            Gap(16)
            Panel {
                InfoRow("Asset", "${h?.asset?.label ?: symbol} ($symbol)")
                InfoRow("Held by", "ANZEN treasury")
                h?.let { InfoRow(if (it.solana) "ANZEN program" else "Treasury contract", Format.shortHex(it.programId ?: it.treasury),
                    mono = true, copy = it.programId ?: it.treasury) }
                InfoRow("Network", h?.network ?: "—")
                InfoRow("Network fee", "None for you · paid by ANZEN's relayer")
                InfoRow("Your balance after", "${Format.amount((balance - ev.amount).coerceAtLeast(0))} $symbol")
            }
            SectionHeader("Conditions")
            Panel {
                InfoRow("Check-in window", "${Format.dateTime(ev.checkInStart)} – ${Format.time(ev.checkInEnd)}")
                InfoRow("If you don't check in", if (ev.noShow == "split") "Shared among attendees" else "Goes to organizer")
                InfoRow("If cancelled", "Full refund")
                InfoRow("Give it back before", "${Format.dateTime(ev.commitDeadline)} · free")
                InfoRow("Give it back later", if (ev.lateFeeBps > 0) "${feeText(ev.lateFeeBps)} late fee, waived if the waitlist refills it" else "Free")
                InfoRow("Commitments close", Format.dateTime(ev.commitDeadline))
            }
            Gap(16)
            InfoNote("Your phone will ask for your fingerprint, face or screen lock. You're signing exactly this: " +
                "commit ${Format.amount(ev.amount)} $symbol to event #${ev.id} on ${h?.network ?: "this network"}. " +
                "The authorization expires in 10 minutes and can be used once.")
            if (balance < ev.amount && tx == TxState.Review) {
                Gap(12)
                InfoNote("This account has ${Format.amount(balance)} $symbol. Get test dollars from Home first.", Tone.Pending)
            }
        }
    }
}

@Composable
fun TreasuryScreen(id: Long, vm: AppViewModel, back: () -> Unit, go: (String) -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    val r = rememberEvent(id, vm, version)
    val s by vm.state.collectAsStateWithLifecycle()
    val symbol = s.health?.asset?.symbol ?: "tUSD"
    Pushed("Treasury", back) {
        Loaded(r, { version++ }) { e ->
            LargeTitle(e.title, Modifier.padding(vertical = Spacing.m))
            SealedPanel {
                Text("Held for this event", style = AnzenType.caption, color = Anzen.colors.onSealedSecondary)
                AmountText(e.amount * e.committed, symbol, color = Anzen.colors.onSealed)
                Gap(6)
                Text("${e.committed} commitments of ${Format.amount(e.amount)} $symbol", style = AnzenType.callout, color = Anzen.colors.onSealed)
            }
            SectionHeader("Participants")
            Panel {
                InfoRow("Committed", "${e.committed} of ${e.capacity}")
                InfoRow("Checked in", "${e.attended}")
                InfoRow("Phase", phaseLabel(e.phase))
                if (e.finalized) {
                    InfoRow("Paid per attendee", "${Format.amount(e.attendeePayout)} $symbol")
                    val forfeited = (e.committed - e.attended).toLong() * e.amount
                    InfoRow("Forfeited by no-shows", "${Format.amount(forfeited)} $symbol")
                }
            }
            Gap(12)
            InfoNote("Individual participants aren't listed. The contract records commitments by key, and this app " +
                "only shows each person their own.")
            SectionHeader("Rules")
            Panel {
                InfoRow("No-show commitments", if (e.noShow == "split") "Split among attendees" else "To organizer")
                InfoRow("Cancellation", "Until check-in opens · full refund")
                InfoRow("Check-in issuer", Format.shortHex(e.issuer), mono = true, copy = e.issuer)
                InfoRow("Organizer key", Format.shortHex(e.organizer), mono = true, copy = e.organizer)
            }
            SectionHeader("Record")
            Panel { TxReference("Published in", e.createTx, s.health?.txLink(e.createTx)) }
        }
    }
}

fun phaseLabel(p: String) = when (p) {
    "open" -> "Open for commitments"
    "closed" -> "Commitments closed"
    "check_in" -> "Check-in open"
    "awaiting_settlement" -> "Check-in closed · awaiting settlement"
    "finalized" -> "Settled"
    "cancelled" -> "Cancelled"
    else -> p
}

/** The outcome for this account, with the action that applies it. Success is shown only once settled onchain. */
@Composable
fun ResultScreen(id: Long, vm: AppViewModel, back: () -> Unit, go: (String) -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    val r = rememberEvent(id, vm, version)
    val s by vm.state.collectAsStateWithLifecycle()
    val symbol = s.health?.asset?.symbol ?: "tUSD"
    val action: ActionViewModel = hiltViewModel()
    val tx by action.tx.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    LaunchedEffect(tx) {
        if (tx is TxState.Verified) { haptics.confirm(); version++ }
        if (tx is TxState.Failed) haptics.reject()
    }
    Pushed("Outcome", back) {
        Loaded(r, { version++ }) { e ->
            val o = outcome(e)
            val me = e.me
            Gap(12)
            val (title, body, tone) = when (o) {
                Outcome.Returned -> Triple("Commitment returned", "You checked in. ${Format.amount(me!!.settledAmount)} $symbol is back in your balance.", Tone.Verified)
                Outcome.Rewarded -> Triple("Returned, plus a share", "You checked in. ${Format.amount(me!!.settledAmount)} $symbol is in your balance: your " +
                    "${Format.amount(e.amount)} back plus ${Format.amount(me.settledAmount - e.amount)} from no-shows.", Tone.Verified)
                Outcome.NoShowApplied -> Triple("No-show rule applied", "There's no check-in from this account, so under the event's rules your " +
                    "commitment " + (if (e.noShow == "split") "was shared among attendees." else "went to the organizer."), Tone.Neutral)
                Outcome.Refunded -> Triple("Refund completed", "The event was cancelled. ${Format.amount(me!!.settledAmount)} $symbol is back in your balance.", Tone.Verified)
                Outcome.CancelledRefundPending -> Triple("Refund available", "The event was cancelled. Apply the refund to return your " +
                    "${Format.amount(e.amount)} $symbol.", Tone.Pending)
                Outcome.AwaitingSettlement -> Triple("Ready to settle", "Check-in has closed. " + (if (me?.status == "waitlisted")
                    "No place freed up for you, so applying the outcome returns your whole commitment." else if (me?.attended == true)
                    "You checked in; applying the outcome returns your commitment" + (if (e.noShow == "split") " and your share." else ".")
                    else "There's no check-in from this account, so applying the outcome closes your commitment under the no-show rule."), Tone.Pending)
                Outcome.InProgress -> Triple("Not decided yet", "The outcome is applied after check-in closes ${Format.relative(e.checkInEnd, e.now)}.", Tone.Pending)
                Outcome.NotParticipating -> Triple("No commitment", "This account didn't commit to this event.", Tone.Neutral)
                Outcome.Released -> Triple("Place given back", "You gave your place back before check-in. The treasury returned " +
                    "what the published terms allow to your balance.", Tone.Verified)
                Outcome.WaitlistRefunded -> Triple("Waitlist refund", "No place freed up, so your whole ${Format.amount(me!!.settledAmount)} " +
                    "$symbol came back. This doesn't count against your Show-Up Score.", Tone.Verified)
            }
            StatusPill(when (tone) { Tone.Verified -> "Settled onchain"; Tone.Pending -> "Pending"; else -> "Final" }, tone)
            Gap(12)
            Text(title, style = AnzenType.largeTitle, color = Anzen.colors.textPrimary)
            Gap(8)
            Text(body, style = AnzenType.body, color = Anzen.colors.textSecondary)
            Gap(20)
            Panel {
                InfoRow("Event", e.title)
                InfoRow("Committed", "${Format.amount(e.amount)} $symbol")
                if (me != null && me.attended) InfoRow("Checked in", Format.dateTime(me.checkedInAt))
                if (me?.status == "settled") InfoRow("Applied to balance", "${Format.amount(me.settledAmount)} $symbol")
            }
            if (o == Outcome.AwaitingSettlement || o == Outcome.CancelledRefundPending) {
                Gap(20)
                when (tx) {
                    TxState.Review, TxState.Cancelled -> PrimaryButton(if (o == Outcome.CancelledRefundPending) "Apply refund" else "Apply outcome") {
                        action.run { settle(e, it) }
                    }
                    else -> TxProgress(tx, "Outcome applied")
                }
                Gap(8)
                Text("Anyone can apply it; the result only ever goes to this account's balance.", style = AnzenType.caption,
                    color = Anzen.colors.textTertiary)
            }
            if (me != null && me.passportId > 0) {
                Gap(20)
                PrimaryButton("View supporter passport", accent = true) { go("passport/${e.id}") }
            }
            vm.repo.keyId?.let { k -> if (me != null && me.status != "none") { Gap(8); SecondaryButton("View proof") { go("proof/${e.id}/$k") } } }
        }
    }
}

/** Give the place back (or leave the waitlist): what comes back, any fee, the shield, then the owner's authorization. */
@Composable
fun ReleaseScreen(id: Long, vm: AppViewModel, back: () -> Unit) {
    val r = rememberEvent(id, vm)
    val s by vm.state.collectAsStateWithLifecycle()
    val action: ActionViewModel = hiltViewModel()
    val tx by action.tx.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    val symbol = s.health?.asset?.symbol ?: "tUSD"
    LaunchedEffect(tx) {
        if (tx is TxState.Verified) haptics.confirm()
        if (tx is TxState.Failed) haptics.reject()
    }
    val e = r?.getOrNull()
    val waiting = e?.me?.status == "waitlisted"
    Pushed(if (waiting) "Leave the waitlist" else "Give your place back", back, bottom = e?.let { ev ->
        {
            if (tx != TxState.Review) { TxProgress(tx, if (waiting) "You left the waitlist" else "Place given back"); Gap(12) }
            when (tx) {
                TxState.Review, TxState.Cancelled -> PrimaryButton("Authorize", enabled = canRelease(ev)) {
                    ctx.activity()?.let { a -> action.run { release(a, ev, it) } }
                }
                is TxState.Verified -> PrimaryButton("Done") { back() }
                is TxState.Failed, is TxState.StillPending -> SecondaryButton("Back to event") { back() }
                else -> PrimaryButton("Working…", loading = true) {}
            }
        }
    }) {
        Loaded(r, {}) { ev ->
            val t = releaseTerms(ev)
            LargeTitle(ev.title, Modifier.padding(vertical = Spacing.m), subtitle = "by ${ev.creator}")
            SealedPanel {
                Text("Comes back to your balance", style = AnzenType.caption, color = Anzen.colors.onSealedSecondary)
                AmountText(t.refund, symbol, color = Anzen.colors.onSealed)
                if (t.fee > 0) {
                    Gap(8)
                    Text("Late fee: ${Format.amount(t.fee)} $symbol (${feeText(ev.lateFeeBps)}) to the organizer, as published. " +
                        "If someone on the waitlist takes your place in the same moment, there's no fee and the whole " +
                        "${Format.amount(ev.amount)} comes back.", style = AnzenType.callout, color = Anzen.colors.onSealed)
                }
            }
            Gap(16)
            Panel {
                InfoRow("Your commitment", "${Format.amount(ev.amount)} $symbol")
                InfoRow("Commitments close", Format.dateTime(ev.commitDeadline))
                if (!waiting) InfoRow("Streak shield", if (t.inTime) "Earned (up to 2)" else "Not earned: after the deadline")
                InfoRow("Your place", if (waiting) "Leaves the waitlist" else if (ev.waitlisted > 0) "Goes to the first person waiting" else "Opens up")
            }
            Gap(16)
            if (!canRelease(ev) && tx == TxState.Review) InfoNote("This can't be done any more: places can only be given back " +
                "before check-in opens.", Tone.Pending)
            else InfoNote("Only your key can do this; your AI agent can't. You're signing: give back your place at event #${ev.id}. " +
                "The authorization expires in 10 minutes and can be used once.")
        }
    }
}
