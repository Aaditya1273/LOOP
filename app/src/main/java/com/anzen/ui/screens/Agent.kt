package com.anzen.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anzen.core.Format
import com.anzen.core.TxState
import com.anzen.ui.ActionViewModel
import com.anzen.ui.AppViewModel
import com.anzen.ui.activity
import com.anzen.ui.components.AmountText
import com.anzen.ui.components.ConfirmDialog
import com.anzen.ui.components.Field
import com.anzen.ui.components.Gap
import com.anzen.ui.components.InfoNote
import com.anzen.ui.components.InfoRow
import com.anzen.ui.components.LargeTitle
import com.anzen.ui.components.Panel
import com.anzen.ui.components.PrimaryButton
import com.anzen.ui.components.SealedPanel
import com.anzen.ui.components.SecondaryButton
import com.anzen.ui.components.SectionHeader
import com.anzen.ui.components.Segmented
import com.anzen.ui.components.StatusPill
import com.anzen.ui.components.Tone
import com.anzen.ui.components.TxProgress
import com.anzen.ui.theme.Anzen
import com.anzen.ui.theme.AnzenType
import com.anzen.ui.theme.Spacing
import com.anzen.ui.theme.rememberHaptics

private const val ZERO = "0x0000000000000000000000000000000000000000"
private val ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")

/**
 * The AI agent's budget. Money can be delegated; presence cannot: the agent may reserve places with this
 * account's funds up to the cap, until the expiry, and nothing else. Opened from Profile or from the agent's
 * anzen://delegate link (which pre-fills the request; the owner decides the real cap).
 */
@Composable
fun AgentScreen(agentArg: String?, capArg: Long?, daysArg: Int?, vm: AppViewModel, back: () -> Unit) {
    val c = Anzen.colors
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    val s by vm.state.collectAsStateWithLifecycle()
    val action: ActionViewModel = hiltViewModel()
    val tx by action.tx.collectAsStateWithLifecycle()
    val d = s.account?.delegation
    val symbol = s.health?.asset?.symbol ?: "tUSD"
    val now = s.health?.now ?: (System.currentTimeMillis() / 1000)
    var agent by remember { mutableStateOf(agentArg ?: d?.agent?.takeIf { it != ZERO } ?: "") }
    var cap by remember { mutableStateOf((capArg ?: 20L).toString()) }
    var days by remember { mutableStateOf(daysArg ?: 7) }
    var confirmRevoke by remember { mutableStateOf(false) }
    LaunchedEffect(tx) {
        if (tx is TxState.Verified) haptics.confirm()
        if (tx is TxState.Failed) haptics.reject()
    }
    val capBase = cap.toLongOrNull()?.takeIf { it in 1..1000 }?.times(1_000_000)
    val requested = agentArg != null

    Pushed("AI agent", back) {
        LargeTitle(if (requested) "Your agent is asking for a budget" else "Your AI agent",
            Modifier.padding(vertical = Spacing.m),
            subtitle = "It can reserve places for you. Showing up is always you.")

        if (d != null && d.active) {
            SealedPanel {
                Row {
                    Text("Budget left", style = AnzenType.caption, color = c.onSealedSecondary, modifier = Modifier.weight(1f))
                    StatusPill("Active", Tone.Accent)
                }
                AmountText(d.remaining, symbol, color = c.onSealed)
                Gap(4)
                Text("of ${Format.amount(d.cap)} $symbol · until ${Format.dateTime(d.expiry)}", style = AnzenType.callout, color = c.onSealed)
            }
            Gap(12)
            Panel { InfoRow("Agent", Format.shortHex(d.agent), mono = true, copy = d.agent) }
        } else if (!requested) {
            InfoNote("No active budget. Connect an agent such as OpenClaw with the ANZEN skill; it will send you an " +
                "approval link, or you can paste its address below.")
        }

        SectionHeader("What it can and can't do")
        Panel {
            InfoRow("Reserve places with your funds", "Yes, within the cap")
            InfoRow("Check in for you", "Never", valueColor = c.statusFailed)
            InfoRow("Cancel or withdraw", "Never", valueColor = c.statusFailed)
            InfoRow("Raise its own budget", "Never", valueColor = c.statusFailed)
            Gap(4)
            Text("The treasury contract enforces this. If the agent misbehaves, the worst case is reservations up to " +
                "the cap, which you get back by checking in.", style = AnzenType.caption, color = c.textSecondary)
        }

        SectionHeader(if (d?.active == true) "Change budget" else "Approve a budget")
        Field("Agent address", agent, { agent = it.trim() }, error = if (agent.isNotEmpty() && !ADDRESS.matches(agent)) "Not an address" else null,
            supporting = if (requested) "Sent by your agent's approval link" else "0x… shown by your agent")
        Gap(12)
        Field("Spending cap", cap, { cap = it.filter(Char::isDigit).take(4) }, keyboard = KeyboardType.Number, suffix = symbol,
            error = if (cap.isNotEmpty() && capBase == null) "Between 1 and 1,000" else null)
        Gap(12)
        Segmented(listOf(1, 7, 30), days, { "$it day" + if (it > 1) "s" else "" }) { days = it }
        Gap(16)
        when (tx) {
            TxState.Review, TxState.Cancelled, is TxState.Failed -> {
                if (tx is TxState.Failed) { TxProgress(tx, ""); Gap(12) }
                PrimaryButton("Approve ${capBase?.let { Format.amount(it) } ?: "—"} $symbol for $days day" + if (days > 1) "s" else "",
                    enabled = ADDRESS.matches(agent) && capBase != null && s.health != null) {
                    ctx.activity()?.let { a -> action.run { delegate(a, agent, capBase!!, now + days * 86_400L, it) } }
                }
                if (d?.active == true) {
                    Gap(8)
                    SecondaryButton("Revoke budget", destructive = true) { confirmRevoke = true }
                }
            }
            else -> TxProgress(tx, "Budget updated")
        }
    }
    if (confirmRevoke) ConfirmDialog("Revoke your agent's budget?", "It won't be able to reserve anything. Places it already " +
        "reserved stay yours.", "Revoke", destructive = true,
        onConfirm = { confirmRevoke = false; ctx.activity()?.let { a -> action.run { delegate(a, ZERO, 0, 0, it) } } },
        onDismiss = { confirmRevoke = false })
}
