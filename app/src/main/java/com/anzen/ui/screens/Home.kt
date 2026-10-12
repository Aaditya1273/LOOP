package com.anzen.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anzen.core.Activity
import com.anzen.core.Event
import com.anzen.core.Format
import com.anzen.core.NextAction
import com.anzen.core.Repository
import com.anzen.core.TxState
import com.anzen.core.mostUrgent
import com.anzen.core.nextAction
import com.anzen.ui.ActionViewModel
import com.anzen.ui.AppViewModel
import com.anzen.ui.components.AmountText
import com.anzen.ui.components.BrandMark
import com.anzen.ui.components.Wordmark
import com.anzen.ui.components.ContentPadding
import com.anzen.ui.components.EmptyState
import com.anzen.ui.components.ErrorState
import com.anzen.ui.components.EventArtwork
import com.anzen.ui.components.EventCard
import com.anzen.ui.components.Field
import com.anzen.ui.components.Gap
import com.anzen.ui.components.InfoNote
import com.anzen.ui.components.LargeTitle
import com.anzen.ui.components.LoadingState
import com.anzen.ui.components.LocalToast
import com.anzen.ui.theme.rememberHaptics
import com.anzen.ui.components.NetworkBadge
import com.anzen.ui.components.Panel
import com.anzen.ui.components.PrimaryButton
import com.anzen.ui.components.QuietButton
import com.anzen.ui.components.SealedPanel
import com.anzen.ui.components.SectionHeader
import com.anzen.ui.components.Segmented
import com.anzen.ui.components.SkeletonCard
import com.anzen.ui.components.StatusPill
import com.anzen.ui.components.TopBar
import com.anzen.ui.components.TxProgress
import com.anzen.ui.components.TxReference
import com.anzen.ui.components.Tone
import com.anzen.ui.components.txTone
import com.anzen.ui.theme.Anzen
import com.anzen.ui.theme.AnzenType
import com.anzen.ui.theme.Radius
import com.anzen.ui.theme.Spacing
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel, go: (String) -> Unit, onExplore: () -> Unit) {
    val c = Anzen.colors
    val s by vm.state.collectAsStateWithLifecycle()
    val symbol = s.health?.asset?.symbol ?: "tUSD"
    val acct = s.account
    val pull = rememberPullToRefreshState()
    if (pull.isRefreshing) LaunchedEffect(Unit) { vm.repo.refresh(); pull.endRefresh() }

    Box(Modifier.fillMaxSize().nestedScroll(pull.nestedScrollConnection)) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = ContentPadding) {
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                    BrandMark(Modifier.size(30.dp))
                    Spacer(Modifier.width(Spacing.s))
                    Wordmark()
                    Spacer(Modifier.weight(1f))
                    NetworkBadge(s.health?.network)
                }
                LargeTitle("Today", Modifier.padding(top = Spacing.s, bottom = Spacing.l))
            }
            s.error?.let { item { ErrorState(it, Modifier.padding(bottom = Spacing.m)) { vm.refresh() } } }

            if (acct == null) {
                item { if (s.loading) Column { SkeletonCard(); Gap(12); SkeletonCard() } }
                return@LazyColumn
            }

            // 1. The one thing that needs the user now.
            mostUrgent(acct.commitments)?.let { e -> item { PriorityCard(e, symbol) { go(route(e)) } } }

            // 2. Balance, with the faucet only where the network allows it.
            item { Gap(12); BalanceCard(acct.balance, symbol, s.health?.faucet == true && !acct.faucetUsed, vm) }
            item { Gap(12); ShowUpScoreCard(acct) }

            acct.delegation?.takeIf { it.active }?.let { d ->
                item {
                    Gap(12)
                    Panel(onClick = { go("agent") }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Your AI agent", style = AnzenType.headline, color = c.textPrimary)
                                Text("${Format.amount(d.remaining)} of ${Format.amount(d.cap)} $symbol left to reserve with",
                                    style = AnzenType.caption, color = c.textSecondary)
                            }
                            StatusPill("Active", Tone.Accent)
                        }
                    }
                }
            }

            val upcoming = acct.commitments.filter { it.me?.status !in setOf("settled", "released") && !it.cancelled }
            if (upcoming.isNotEmpty()) {
                item { SectionHeader("Your commitments") }
                items(upcoming, key = { "c${it.id}" }) { e -> EventCard(e, symbol, Modifier.padding(bottom = Spacing.s)) { go("event/${e.id}") } }
            }
            if (acct.organized.isNotEmpty()) {
                item { SectionHeader("Events you organize") { QuietButton("Dashboard") { go("dashboard") } } }
                items(acct.organized, key = { "o${it.id}" }) { e -> EventCard(e, symbol, Modifier.padding(bottom = Spacing.s)) { go("event/${e.id}") } }
            }
            val outcomes = acct.commitments.filter { it.me?.status == "settled" }
            if (outcomes.isNotEmpty()) {
                item { SectionHeader("Recent outcomes") }
                items(outcomes.take(3), key = { "r${it.id}" }) { e -> EventCard(e, symbol, Modifier.padding(bottom = Spacing.s)) { go("result/${e.id}") } }
            }
            if (acct.commitments.isEmpty() && acct.organized.isEmpty()) {
                item {
                    EmptyState("Nothing committed yet", "Find a creator's event and reserve your place, or create one of your own.",
                        action = "Explore events", onAction = onExplore)
                }
            }
            if (s.activity.isNotEmpty()) {
                item { SectionHeader("Activity") { QuietButton("See all") { go("activity") } } }
                item { Panel { s.activity.take(3).forEach { ActivityRow(it) } } }
            }
        }
        // Drawn only while pulling or refreshing; at rest it would sit visibly under the status bar.
        if (pull.progress > 0f || pull.isRefreshing) PullToRefreshContainer(pull, Modifier.align(Alignment.TopCenter).statusBarsPadding(), containerColor = c.backgroundElevated,
            contentColor = c.textPrimary)
    }
}

fun route(e: Event): String = when (nextAction(e)) {
    NextAction.CheckIn -> "checkin/${e.id}"
    NextAction.Settle, NextAction.RefundAvailable, NextAction.ViewResult -> "result/${e.id}"
    else -> "event/${e.id}"
}

@Composable
private fun PriorityCard(e: Event, symbol: String, onClick: () -> Unit) {
    val c = Anzen.colors
    val (title, body, cta) = when (nextAction(e)) {
        NextAction.CheckIn -> Triple("Check in now", "Check-in for ${e.title} closes ${Format.relative(e.checkInEnd, e.now)}.", "Scan venue code")
        NextAction.Settle -> Triple("Your outcome is ready", "Check-in for ${e.title} has closed. Apply the result to your balance.", "View outcome")
        NextAction.RefundAvailable -> Triple("Refund available", "${e.title} was cancelled. Your ${Format.amount(e.amount)} $symbol can be returned.", "Get refund")
        else -> Triple("Next up", "${e.title} · check-in opens ${Format.relative(e.checkInStart, e.now)}.", "View event")
    }
    SealedPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EventArtwork(e.artwork, e.id, Modifier.size(44.dp).clip(Radius.small))
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f)) {
                Text(title, style = AnzenType.title, color = c.onSealed)
                Text(e.creator, style = AnzenType.caption, color = c.onSealedSecondary)
            }
        }
        Gap(12)
        Text(body, style = AnzenType.callout, color = c.onSealed)
        Gap(16)
        PrimaryButton(cta, accent = true, onClick = onClick)
    }
}

@Composable
private fun BalanceCard(balance: Long, symbol: String, faucet: Boolean, vm: AppViewModel) {
    val c = Anzen.colors
    val action: ActionViewModel = hiltViewModel()
    val tx by action.tx.collectAsStateWithLifecycle()
    val toast = LocalToast.current
    val haptics = rememberHaptics()
    LaunchedEffect(tx) { if (tx is TxState.Verified) { haptics.confirm(); toast("100 test dollars added") } }
    Panel {
        Text("Available", style = AnzenType.caption, color = c.textSecondary)
        Gap(4)
        AmountText(balance, symbol)
        Gap(4)
        Text("Test dollars on this key. They have no value.", style = AnzenType.caption, color = c.textTertiary)
        if (faucet && tx !is TxState.Verified) {
            Gap(12)
            if (tx == TxState.Review) PrimaryButton("Get 100 test dollars") { action.run { faucet(it) } }
            else TxProgress(tx, "Test dollars added")
        }
    }
}

@Composable
fun ActivityRow(a: Activity) {
    val c = Anzen.colors
    val label = when (a.action) {
        "faucet" -> "Test dollars received"
        "commit" -> "Committed"
        "checkin" -> "Checked in"
        "settle" -> "Outcome applied"
        "create" -> "Event published"
        "cancel" -> "Event cancelled"
        "finalize" -> "Event finalized"
        "delegate" -> "Agent budget changed"
        "agent_commit" -> "Reserved by your agent"
        else -> a.action
    }
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = AnzenType.headline, color = c.textPrimary)
            Text(listOfNotNull(a.eventTitle, Format.dateTime(a.at.toLong())).joinToString(" · "),
                style = AnzenType.caption, color = c.textSecondary)
        }
        StatusPill(a.status.replaceFirstChar(Char::uppercase), txTone(a.status))
    }
}

@Composable
fun ActivityScreen(vm: AppViewModel, back: () -> Unit, go: (String) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.repo.refresh() }
    Column(Modifier.fillMaxSize().background(Anzen.colors.backgroundPrimary)) {
        TopBar("Activity", onBack = back)
        LazyColumn(contentPadding = ContentPadding) {
            item {
                InfoNote("Everything this account submitted through ANZEN, with its onchain status. Notifications are " +
                    "local reminders only; outcomes appear here when you open the app.")
                Gap(12)
            }
            if (s.activity.isEmpty()) item { if (s.loading) LoadingState() else EmptyState("No activity yet", "Commitments, check-ins and outcomes appear here.") }
            items(s.activity, key = { it.tx }) { a ->
                Panel(Modifier.padding(bottom = Spacing.s), onClick = a.eventId?.let { { go("event/$it") } }) {
                    ActivityRow(a)
                    TxReference("Reference", a.tx, a.explorer)
                }
            }
        }
    }
}

private enum class Filter(val label: String) { All("All"), Open("Open"), Week("This week"), Mine("Committed") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(vm: AppViewModel, go: (String) -> Unit) {
    val c = Anzen.colors
    val s by vm.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    var refreshes by remember { mutableIntStateOf(0) }
    val results by produceState<Result<List<Event>>?>(null, query, refreshes) {
        delay(if (query.isBlank()) 0 else 300) // debounce typing
        value = runCatching { vm.repo.search(query) }
    }
    val pull = rememberPullToRefreshState()
    if (pull.isRefreshing) LaunchedEffect(Unit) { refreshes++; delay(400); pull.endRefresh() }
    val symbol = s.health?.asset?.symbol ?: "tUSD"

    Box(Modifier.fillMaxSize().nestedScroll(pull.nestedScrollConnection)) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = ContentPadding) {
            item {
                LargeTitle("Explore", Modifier.padding(top = Spacing.xl, bottom = Spacing.l), subtitle = "Creator events with a commitment.")
                Field("Search events, creators, venues", query, { query = it })
                Gap(12)
                Segmented(Filter.entries, filter, { it.label }) { filter = it }
                Gap(16)
            }
            val r = results
            when {
                r == null -> item { Column { SkeletonCard(); Gap(12); SkeletonCard() } }
                r.isFailure -> item { ErrorState(Repository.reasonOf(r.exceptionOrNull()!!)) { refreshes++ } }
                else -> {
                    val now = s.health?.now ?: (System.currentTimeMillis() / 1000)
                    val list = r.getOrThrow().filter {
                        when (filter) {
                            Filter.All -> true
                            Filter.Open -> nextAction(it) == NextAction.Commit
                            Filter.Week -> it.startsAt in now..(now + 7 * 86_400)
                            Filter.Mine -> (it.me?.status ?: "none") != "none"
                        }
                    }
                    if (list.isEmpty()) item {
                        EmptyState(if (query.isBlank()) "No events here yet" else "No matches",
                            if (query.isBlank()) "Events published on ${s.health?.network ?: "this network"} appear here. Create the first one with +."
                            else "Try a creator's name or a venue.")
                    }
                    items(list, key = { it.id }) { e -> EventCard(e, symbol, Modifier.padding(bottom = Spacing.s)) { go("event/${e.id}") } }
                }
            }
        }
        // Drawn only while pulling or refreshing; at rest it would sit visibly under the status bar.
        if (pull.progress > 0f || pull.isRefreshing) PullToRefreshContainer(pull, Modifier.align(Alignment.TopCenter).statusBarsPadding(), containerColor = c.backgroundElevated,
            contentColor = c.textPrimary)
    }
}
