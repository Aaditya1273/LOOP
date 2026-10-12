package com.anzen.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anzen.core.Account
import com.anzen.core.Dashboard
import com.anzen.core.Format
import com.anzen.core.Repository
import com.anzen.core.scoreTier
import com.anzen.ui.AppViewModel
import com.anzen.ui.components.AmountText
import com.anzen.ui.components.EmptyState
import com.anzen.ui.components.ErrorState
import com.anzen.ui.components.Gap
import com.anzen.ui.components.InfoNote
import com.anzen.ui.components.InfoRow
import com.anzen.ui.components.LargeTitle
import com.anzen.ui.components.LoadingState
import com.anzen.ui.components.Panel
import com.anzen.ui.components.SealedPanel
import com.anzen.ui.components.SectionHeader
import com.anzen.ui.components.StatusPill
import com.anzen.ui.components.Tone
import com.anzen.ui.theme.Anzen
import com.anzen.ui.theme.AnzenType
import com.anzen.ui.theme.Spacing

/** The owner's Show-Up Score, read from the treasury's record for this key. Nothing here is computed on the phone. */
@Composable
fun ShowUpScoreCard(a: Account, modifier: Modifier = Modifier) {
    val c = Anzen.colors
    val r = a.record
    Panel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Show-Up Score", style = AnzenType.headline, color = c.textPrimary)
                Text(if (a.scoreBasis == 0) "No settled events yet" else "From ${a.scoreBasis} settled event" +
                    (if (a.scoreBasis > 1) "s" else ""), style = AnzenType.caption, color = c.textSecondary)
            }
            Text("${a.score}", style = AnzenType.largeTitle, color = c.brand,
                modifier = Modifier.semantics { contentDescription = "Score ${a.score} out of 100" })
        }
        Gap(8)
        StatusPill(if (r.streak > 0) "${r.streak} in a row" else "No streak yet", if (r.streak > 0) Tone.Accent else Tone.Neutral)
        Gap(8)
        InfoRow("Attended · missed", "${r.attended} · ${r.missed}")
        InfoRow("Best streak", "${r.bestStreak}")
        InfoRow("Streak shields", "${r.shields} of 2")
        Gap(4)
        Text(if (a.scoreBasis == 0) "Everyone starts at 75. It moves toward your real show-up rate as you check in."
            else "Giving a place back before commitments close earns a shield; a miss then spends it instead of breaking your streak.",
            style = AnzenType.caption, color = c.textSecondary)
        Gap(4)
        Text("Perk level (venues can opt in; not live yet): ${scoreTier(a.score)}", style = AnzenType.caption, color = c.textTertiary)
    }
}

/** Organizer's venue dashboard: seats refilled and money recovered, read from each event's onchain state. */
@Composable
fun DashboardScreen(vm: AppViewModel, back: () -> Unit, go: (String) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val symbol = s.health?.asset?.symbol ?: "tUSD"
    var version by remember { mutableIntStateOf(0) }
    val r = produceState<Result<Dashboard>?>(null, version) { value = runCatching { vm.repo.dashboard() } }.value
    Pushed("Venue dashboard", back) {
        LargeTitle("Your events", Modifier.padding(vertical = Spacing.m), subtitle = "Every number is read from the treasury.")
        when {
            r == null -> LoadingState()
            r.isFailure -> ErrorState(Repository.reasonOf(r.exceptionOrNull()!!)) { version++ }
            r.getOrThrow().events.isEmpty() -> EmptyState("No events yet", "Publish an event to see its seats and outcomes here.")
            else -> {
                val d = r.getOrThrow()
                val t = d.totals
                SealedPanel {
                    Text("Recovered for you", style = AnzenType.caption, color = Anzen.colors.onSealedSecondary)
                    AmountText(t.recovered, symbol, color = Anzen.colors.onSealed)
                    Gap(6)
                    Text("Late-release fees and no-show commitments that reached your balance.", style = AnzenType.callout,
                        color = Anzen.colors.onSealed)
                }
                Gap(12)
                Panel {
                    InfoRow("Events", "${t.events}")
                    InfoRow("Seats committed", "${t.seats}")
                    InfoRow("Checked in", "${t.attended}")
                    InfoRow("Show rate", t.showRate?.let { "${"%.0f".format(it * 100)}%" } ?: "—")
                    InfoRow("Places given back", "${t.released}")
                    InfoRow("Refilled from waitlist", "${t.refilled}")
                }
                SectionHeader("By event")
                d.events.forEach { e ->
                    Panel(Modifier.padding(bottom = Spacing.s), onClick = { go("event/${e.id}") }) {
                        Text(e.title, style = AnzenType.headline, color = Anzen.colors.textPrimary)
                        Gap(4)
                        InfoRow("Committed · checked in", "${e.committed} of ${e.capacity} · ${e.attended}")
                        InfoRow("Given back · refilled", "${e.released} · ${e.refilled}")
                        InfoRow("Recovered", "${Format.amount(e.recovered)} $symbol")
                    }
                }
                Gap(4)
                InfoNote("Show rate counts settled events only. A refilled seat is one someone gave back that the waitlist took at once.")
            }
        }
    }
}
