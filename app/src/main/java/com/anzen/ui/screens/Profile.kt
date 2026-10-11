package com.anzen.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anzen.core.Format
import com.anzen.ui.AppViewModel
import com.anzen.ui.components.ConfirmDialog
import com.anzen.ui.components.ContentPadding
import com.anzen.ui.components.Gap
import com.anzen.ui.components.InfoNote
import com.anzen.ui.components.InfoRow
import com.anzen.ui.components.LargeTitle
import com.anzen.ui.components.Panel
import com.anzen.ui.components.QuietButton
import com.anzen.ui.components.SecondaryButton
import com.anzen.ui.components.SectionHeader
import com.anzen.ui.components.Segmented
import com.anzen.ui.components.Tone
import com.anzen.ui.theme.Anzen
import com.anzen.ui.theme.AnzenType
import com.anzen.ui.theme.Spacing

private const val DOCS = "https://github.com/Aaditya1273/LOOP"

@Composable
fun ProfileScreen(vm: AppViewModel, go: (String) -> Unit) {
    val c = Anzen.colors
    val ctx = LocalContext.current
    val s by vm.state.collectAsStateWithLifecycle()
    val h = s.health
    var reminders by remember { mutableStateOf(vm.repo.prefs.reminders) }
    var confirmRemove by remember { mutableStateOf(false) }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        reminders = ok
        vm.repo.prefs.reminders = ok
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(ContentPadding)) {
        LargeTitle("Profile", Modifier.padding(top = Spacing.xl, bottom = Spacing.s))

        SectionHeader("Account")
        Panel {
            vm.repo.keyId?.let { InfoRow("Account key", Format.shortHex(it), mono = true, copy = it) }
            InfoRow("Key type", "P-256 · Android Keystore")
            InfoRow("Unlocked by", "Fingerprint, face or screen lock")
            s.account?.let { InfoRow("Balance", "${Format.amount(it.balance)} ${h?.asset?.symbol ?: ""}") }
            Gap(8)
            Text("This key exists only on this phone and can't be exported or recovered. Losing or resetting the phone, " +
                "or removing its screen lock, deletes it, and anything it holds is gone. On test networks that's only " +
                "test dollars.", style = AnzenType.caption, color = c.textSecondary)
        }

        SectionHeader("AI agent")
        Panel(onClick = { go("agent") }) {
            val d = s.account?.delegation
            Text(if (d?.active == true) "Budget: ${Format.amount(d.remaining)} of ${Format.amount(d.cap)} ${h?.asset?.symbol ?: ""} left"
                else "No agent budget", style = AnzenType.headline, color = c.textPrimary)
            Text("An agent like OpenClaw can reserve places for you within a cap you approve. It can never check in.",
                style = AnzenType.caption, color = c.textSecondary)
        }

        SectionHeader("Network")
        Panel {
            InfoRow("Network", h?.network ?: "Not connected")
            h?.let {
                InfoRow("Chain ID", "${it.chainId}")
                InfoRow("Treasury", Format.shortHex(it.treasury), mono = true, copy = it.treasury)
                InfoRow("Test dollar", Format.shortHex(it.token), mono = true, copy = it.token)
                InfoRow("Check-in issuer", Format.shortHex(it.issuer), mono = true, copy = it.issuer)
                InfoRow("Fee relayer", if (it.relayerFunded) "Funded" else "Out of gas", valueColor = if (it.relayerFunded) c.textPrimary else c.statusFailed)
            }
        }

        SectionHeader("Notifications")
        Panel {
            Row(Modifier.fillMaxWidth().toggleable(reminders, role = Role.Switch) { on ->
                // The runtime permission exists from Android 13; before that, notifications are allowed by default.
                if (on && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    reminders = on
                    vm.repo.prefs.reminders = on
                }
            }.padding(vertical = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Check-in reminders", style = AnzenType.headline, color = c.textPrimary)
                    Text("A local reminder when check-in opens for events you committed to.", style = AnzenType.caption, color = c.textSecondary)
                }
                Switch(reminders, null, colors = SwitchDefaults.colors(checkedTrackColor = c.actionPrimary, checkedThumbColor = c.onActionPrimary))
            }
            Gap(4)
            Text("Push notifications aren't set up for this build, so settlement results appear when you open the app.",
                style = AnzenType.caption, color = c.textTertiary)
        }

        SectionHeader("Appearance")
        Segmented(listOf("system", "light", "dark"), vm.appearance, { it.replaceFirstChar(Char::uppercase) }) { vm.changeAppearance(it) }

        SectionHeader("Privacy")
        Panel {
            Text("ANZEN stores your events' published terms and the transactions it relayed for you. Contract state is " +
                "public: your account key, commitments, check-in time and settlement are visible onchain. The app " +
                "collects no location, contacts or analytics.", style = AnzenType.callout, color = c.textSecondary)
        }

        SectionHeader("More")
        Panel {
            QuietButton("Activity", Modifier.fillMaxWidth()) { go("activity") }
            QuietButton("Documentation", Modifier.fillMaxWidth()) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DOCS))) }
        }

        SectionHeader("This phone")
        InfoNote("Removing the key signs you out permanently. There's no recovery: the account and anything it holds " +
            "can't be reached again.", Tone.Failed)
        Gap(8)
        SecondaryButton("Remove key from this phone", destructive = true) { confirmRemove = true }
    }
    if (confirmRemove) ConfirmDialog("Remove this key?",
        "Your balance (${s.account?.let { Format.amount(it.balance) } ?: "0"} ${h?.asset?.symbol ?: ""}), commitments and passports " +
            "stay onchain but nobody can use them again. This can't be undone.", "Remove key", destructive = true,
        onConfirm = {
            confirmRemove = false
            vm.repo.removeKey()
            go("setup")
        }, onDismiss = { confirmRemove = false })
}
