package com.anzen.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anzen.core.Format
import com.anzen.core.Links
import com.anzen.core.Proof
import com.anzen.core.Repository
import com.anzen.ui.AppViewModel
import com.anzen.ui.components.ContentPadding
import com.anzen.ui.components.EmptyState
import com.anzen.ui.components.ErrorState
import com.anzen.ui.components.Gap
import com.anzen.ui.components.InfoNote
import com.anzen.ui.components.InfoRow
import com.anzen.ui.components.LargeTitle
import com.anzen.ui.components.LoadingState
import com.anzen.ui.components.Panel
import com.anzen.ui.components.PassportCard
import com.anzen.ui.components.PrimaryButton
import com.anzen.ui.components.QrCode
import com.anzen.ui.components.SecondaryButton
import com.anzen.ui.components.SectionHeader
import com.anzen.ui.components.SkeletonCard
import com.anzen.ui.components.StatusPill
import com.anzen.ui.components.Tone
import com.anzen.ui.components.TxReference
import com.anzen.ui.theme.Anzen
import com.anzen.ui.theme.AnzenType
import com.anzen.ui.theme.Radius
import com.anzen.ui.theme.Spacing

@Composable
fun PassportsScreen(vm: AppViewModel, go: (String) -> Unit, onExplore: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val issued = s.account?.commitments.orEmpty().filter { (it.me?.passportId ?: 0) > 0 }
    val pending = s.account?.commitments.orEmpty().filter { it.me?.attended == true && it.me.status != "settled" && !it.cancelled }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = ContentPadding) {
        item { LargeTitle("Passports", Modifier.padding(top = Spacing.xl, bottom = Spacing.l), subtitle = "Records of events you showed up for.") }
        when {
            s.account == null && s.loading -> item { SkeletonCard() }
            issued.isEmpty() && pending.isEmpty() -> item {
                EmptyState("No passports yet", "Check in at an event and apply the outcome. The treasury records a supporter " +
                    "passport for this account.", action = "Explore events", onAction = onExplore)
            }
        }
        items(issued, key = { it.id }) { e ->
            PassportCard(e, e.me!!.passportId, Modifier.padding(bottom = Spacing.l)) { go("passport/${e.id}") }
        }
        if (pending.isNotEmpty()) {
            item { SectionHeader("Waiting to be issued") }
            items(pending, key = { "p${it.id}" }) { e ->
                Panel(Modifier.padding(bottom = Spacing.s), onClick = { go("result/${e.id}") }) {
                    Text(e.title, style = AnzenType.headline, color = Anzen.colors.textPrimary)
                    Gap(4)
                    Text("You checked in. The passport is issued when the outcome is applied after check-in closes.",
                        style = AnzenType.callout, color = Anzen.colors.textSecondary)
                    Gap(8)
                    StatusPill("Not issued yet", Tone.Pending)
                }
            }
        }
    }
}

@Composable
fun PassportScreen(id: Long, vm: AppViewModel, back: () -> Unit, go: (String) -> Unit) {
    val ctx = LocalContext.current
    val keyId = vm.repo.keyId
    var version by remember { mutableIntStateOf(0) }
    val r = rememberEvent(id, vm, version)
    Pushed("Passport", back) {
        Loaded(r, { version++ }) { e ->
            val me = e.me
            if (me == null || me.passportId == 0L || keyId == null) {
                InfoNote("This account has no passport for this event. Passports are issued onchain when an attendee's " +
                    "outcome is applied.", Tone.Pending)
                return@Loaded
            }
            com.anzen.ui.components.Mascot(com.anzen.ui.components.Panda.Excited,
                Modifier.height(128.dp).align(Alignment.CenterHorizontally))
            Gap(8)
            PassportCard(e, me.passportId)
            Gap(16)
            StatusPill("Issued onchain · passport no. ${me.passportId}", Tone.Verified)
            SectionHeader("Details")
            Panel {
                InfoRow("Event", e.title)
                InfoRow("Creator", e.creator)
                InfoRow("Checked in", Format.dateTime(me.checkedInAt))
                InfoRow("Issued by", "ANZEN treasury")
                InfoRow("Holder key", Format.shortHex(keyId), mono = true, copy = keyId)
                InfoRow("Transferable", "No · bound to this key")
            }
            SectionHeader("Present")
            val link = Links.proof(e.id, keyId)
            Box(Modifier.fillMaxWidth().clip(Radius.large).background(Color.White).padding(Spacing.xl), contentAlignment = Alignment.Center) {
                QrCode(link, Modifier.size(200.dp), "Passport proof code")
            }
            Gap(8)
            Text("Anyone with ANZEN can scan this to check the passport against the treasury. It shows this key's " +
                "participation in this event, nothing else.", style = AnzenType.caption, color = Anzen.colors.textSecondary)
            Gap(16)
            PrimaryButton("View proof") { go("proof/${e.id}/$keyId") }
            Gap(8)
            SecondaryButton("Share") {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,
                    "Supporter passport no. ${me.passportId} for ${e.title} by ${e.creator}, recorded by the ANZEN treasury.\n" +
                        "Verify in ANZEN: $link\n(test network, no monetary value)"), "Share passport"))
            }
        }
    }
}

/** What the evidence supports, what it doesn't, and every reference needed to check it independently. */
@Composable
fun ProofScreen(eventId: Long, keyId: String, vm: AppViewModel, back: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    var version by remember { mutableIntStateOf(0) }
    val r by produceState<Result<Proof>?>(null, version) { value = runCatching { vm.repo.proof(eventId, keyId) } }
    val symbol = s.health?.asset?.symbol ?: "tUSD"
    Pushed("Proof", back) {
        val res = r
        when {
            res == null -> LoadingState()
            res.isFailure -> ErrorState(Repository.reasonOf(res.exceptionOrNull()!!)) { version++ }
            else -> {
                val p = res.getOrThrow()
                val e = p.event
                val me = p.participation
                LargeTitle(e.title, Modifier.padding(vertical = Spacing.m), subtitle = if (keyId == vm.repo.keyId) "Your participation" else "Another account's participation")

                SectionHeader("Verified onchain")
                Panel {
                    Check("Commitment recorded", me.status != "none")
                    Check("Check-in signed with a fresh venue code", me.attended)
                    Check("Outcome applied", me.status == "settled")
                    Check("Supporter passport issued", me.passportId > 0)
                    Check("Published terms match the onchain hash", e.policyVerified)
                }
                SectionHeader("Record")
                Panel {
                    InfoRow("Event", "#${e.id}", copy = e.id.toString())
                    InfoRow("Treasury", Format.shortHex(p.treasury), mono = true, copy = p.treasury)
                    InfoRow("Chain ID", "${p.chainId}")
                    InfoRow("Participant key", Format.shortHex(p.keyId), mono = true, copy = p.keyId)
                    InfoRow("Terms hash", Format.shortHex(e.policyHash), mono = true, copy = e.policyHash)
                    InfoRow("Check-in issuer", Format.shortHex(e.issuer), mono = true, copy = e.issuer)
                    if (me.attended) InfoRow("Checked in", Format.dateTime(me.checkedInAt))
                    if (me.status == "settled") InfoRow("Settled amount", "${Format.amount(me.settledAmount)} $symbol")
                    if (me.passportId > 0) InfoRow("Passport", "No. ${me.passportId}")
                }
                SectionHeader("Transactions")
                Panel {
                    if (p.transactions.isEmpty()) Text("No transactions relayed through this service for this key.",
                        style = AnzenType.callout, color = Anzen.colors.textSecondary)
                    p.transactions.forEach { t -> TxReference(actionLabel(t.action), t.tx, t.explorer, t.status) }
                }
                SectionHeader("What this doesn't prove")
                p.limitations.forEach { InfoNote(it, Tone.Info, Modifier.padding(bottom = Spacing.s)) }
            }
        }
    }
}

@Composable
private fun Check(label: String, ok: Boolean) = Row(Modifier.fillMaxWidth().padding(vertical = Spacing.s),
    verticalAlignment = Alignment.CenterVertically) {
    Text(label, style = AnzenType.callout, color = Anzen.colors.textPrimary, modifier = Modifier.weight(1f))
    StatusPill(if (ok) "Yes" else "No", if (ok) Tone.Verified else Tone.Neutral)
}

fun actionLabel(a: String) = when (a) {
    "create" -> "Event published"
    "commit" -> "Commitment"
    "checkin" -> "Check-in"
    "finalize" -> "Event finalized"
    "settle" -> "Outcome applied"
    "cancel" -> "Event cancelled"
    "faucet" -> "Test dollars"
    "delegate" -> "Agent budget"
    "agent_commit" -> "Reserved by your agent"
    else -> a
}
