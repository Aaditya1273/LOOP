package com.anzen.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anzen.core.ErrorCopy
import com.anzen.ui.AppViewModel
import com.anzen.ui.components.BrandMark
import com.anzen.ui.components.Gap
import com.anzen.ui.components.InfoNote
import com.anzen.ui.components.NetworkBadge
import com.anzen.ui.components.PrimaryButton
import com.anzen.ui.components.QuietButton
import com.anzen.ui.components.SecondaryButton
import com.anzen.ui.components.Tone
import com.anzen.ui.components.Mascot
import com.anzen.ui.components.Panda
import com.anzen.ui.components.Wordmark
import com.anzen.ui.theme.Anzen
import com.anzen.ui.theme.AnzenType
import com.anzen.ui.theme.Radius
import com.anzen.ui.theme.Spacing
import com.anzen.ui.theme.rememberHaptics
import kotlinx.coroutines.launch

private data class Page(val title: String, val body: String, val panda: Panda)

private val PAGES = listOf(
    Page("Find events worth showing up for.", "Creators publish meetups, runs, launches and workshops. Each one lists " +
        "its rules up front: what you commit, when check-in opens, and what happens if you don't come.", Panda.Curious),
    Page("Commit to show.", "Reserve your place by committing a set amount. The terms are fixed onchain before you " +
        "agree, and the prompt shows exactly what you sign.", Panda.Focused),
    Page("Prove your presence.", "At the venue, scan the rotating code the organizer shows. Your phone signs it, and " +
        "the treasury checks the signature and that the code is fresh. It shows you checked in with this phone, " +
        "not where you were.", Panda.Confident),
    Page("Unlock your outcome.", "When check-in closes, the treasury applies that event's published rules. Usually " +
        "your commitment comes back; some events also share no-shows' commitments. Attended events become " +
        "supporter passports you keep.", Panda.Happy),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val c = Anzen.colors
    val pager = rememberPagerState { PAGES.size }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(c.backgroundPrimary).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            BrandMark(Modifier.size(30.dp))
            Spacer(Modifier.width(Spacing.s))
            Wordmark()
            Spacer(Modifier.weight(1f))
            QuietButton("Skip", onClick = onDone)
        }
        HorizontalPager(pager, Modifier.weight(1f)) { i ->
            val p = PAGES[i]
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter),
                verticalArrangement = Arrangement.Center) {
                Box(Modifier.fillMaxWidth().height(220.dp).clip(Radius.large).background(c.surfaceSecondary),
                    contentAlignment = Alignment.Center) {
                    Mascot(p.panda, Modifier.height(184.dp))
                }
                Gap(32)
                Text(p.title, style = AnzenType.largeTitle, color = c.textPrimary)
                Gap(12)
                Text(p.body, style = AnzenType.body, color = c.textSecondary)
            }
        }
        Row(Modifier.fillMaxWidth().padding(Spacing.gutter), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(PAGES.size) { i ->
                    Box(Modifier.size(width = if (i == pager.currentPage) 20.dp else 6.dp, height = 6.dp)
                        .background(if (i == pager.currentPage) c.actionPrimary else c.borderStrong, CircleShape))
                }
            }
            Spacer(Modifier.weight(1f))
            PrimaryButton(if (pager.currentPage == PAGES.lastIndex) "Get started" else "Continue", Modifier.width(180.dp)) {
                if (pager.currentPage == PAGES.lastIndex) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            }
        }
    }
}

/**
 * Creates the account key. There is no password, seed phrase or server account: the key is the account,
 * and it never leaves this phone.
 */
@Composable
fun SetupScreen(vm: AppViewModel, onDone: () -> Unit) {
    val c = Anzen.colors
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    val state by vm.state.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }
    var canCreate by remember { mutableStateOf(vm.repo.key.canCreate()) }
    Column(Modifier.fillMaxSize().background(c.backgroundPrimary).statusBarsPadding().navigationBarsPadding()
        .verticalScroll(rememberScrollState()).padding(Spacing.gutter)) {
        Gap(32)
        BrandMark(Modifier.size(64.dp))
        Gap(24)
        Text("Create your ANZEN key", style = AnzenType.largeTitle, color = c.textPrimary)
        Gap(12)
        Text("Your account is a key stored in this phone's secure hardware. Your fingerprint, face or screen lock " +
            "unlocks it, and every commitment, check-in or event you publish asks for it — showing exactly what " +
            "you're approving.", style = AnzenType.body, color = c.textSecondary)
        Gap(24)
        Fact("Lives only on this phone", "It isn't backed up or synced. Losing or resetting this phone, or removing " +
            "its screen lock, deletes the key, and anything it holds can't be recovered.")
        Fact("No fees for you", "ANZEN's relayer pays network fees. It can't move your funds or sign for you; it can " +
            "only deliver what you signed.")
        Fact("It proves this phone signed", "A check-in signature shows this key signed a fresh venue code. It doesn't " +
            "prove who you are or exactly where you were.")
        Gap(8)
        state.health?.let { NetworkBadge(it.network) }
        state.error?.let { Gap(12); InfoNote(ErrorCopy.of(it), Tone.Pending) }
        Gap(32)
        if (!canCreate) {
            InfoNote("Set a screen lock (PIN, pattern or password) on this phone first. The key can only be created " +
                "behind one.", Tone.Pending)
            Gap(12)
            SecondaryButton("Open security settings") { ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
            Gap(8)
            QuietButton("I've set one", Modifier.fillMaxWidth()) { canCreate = vm.repo.key.canCreate() }
        } else {
            error?.let { InfoNote(it, Tone.Failed); Gap(12) }
            PrimaryButton("Create key") {
                runCatching { vm.repo.createKey() }
                    .onSuccess { haptics.confirm(); vm.refresh(); onDone() }
                    .onFailure { haptics.reject(); error = "The key couldn't be created: ${it.message ?: "unknown error"}." }
            }
        }
    }
}

@Composable
private fun Fact(title: String, body: String) {
    val c = Anzen.colors
    Row(Modifier.padding(vertical = Spacing.s)) {
        Box(Modifier.padding(top = 7.dp).size(8.dp).background(c.actionPrimary, CircleShape))
        Spacer(Modifier.width(Spacing.m))
        Column {
            Text(title, style = AnzenType.headline, color = c.textPrimary)
            Gap(2)
            Text(body, style = AnzenType.callout, color = c.textSecondary)
        }
    }
}
