package com.anzen.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.anzen.core.Links
import com.anzen.core.Repository
import com.anzen.core.TxState
import com.anzen.ui.components.LocalToast
import com.anzen.ui.screens.ActivityScreen
import com.anzen.ui.screens.AgentScreen
import com.anzen.ui.screens.CheckInScreen
import com.anzen.ui.screens.CommitScreen
import com.anzen.ui.screens.CreateScreen
import com.anzen.ui.screens.DeskScreen
import com.anzen.ui.screens.EventScreen
import com.anzen.ui.screens.ExploreScreen
import com.anzen.ui.screens.HomeScreen
import com.anzen.ui.screens.OnboardingScreen
import com.anzen.ui.screens.PassportScreen
import com.anzen.ui.screens.PassportsScreen
import com.anzen.ui.screens.ProfileScreen
import com.anzen.ui.screens.ProofScreen
import com.anzen.ui.screens.ResultScreen
import com.anzen.ui.screens.ScanScreen
import com.anzen.ui.screens.SetupScreen
import com.anzen.ui.screens.TreasuryScreen
import com.anzen.ui.theme.Anzen
import com.anzen.ui.theme.AnzenTheme
import com.anzen.ui.theme.AnzenType
import com.anzen.ui.theme.Motion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(val repo: Repository) : ViewModel() {
    val state = repo.state
    var appearance by mutableStateOf(repo.prefs.appearance)
        private set

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch { repo.refresh() }

    fun changeAppearance(v: String) {
        repo.prefs.appearance = v
        appearance = v
    }
}

/**
 * One financial action per screen. Lives in the screen's back-stack entry, so a running action isn't cancelled
 * by recomposition, and its state survives until the user leaves the screen.
 */
@HiltViewModel
class ActionViewModel @Inject constructor(val repo: Repository) : ViewModel() {
    private val _tx = MutableStateFlow<TxState>(TxState.Review)
    val tx: StateFlow<TxState> = _tx
    val busy get() = _tx.value.let { it !is TxState.Review && it !is TxState.Cancelled && it !is TxState.Failed && it !is TxState.Verified && it !is TxState.StillPending }

    fun run(block: suspend Repository.(onState: (TxState) -> Unit) -> Unit) {
        if (busy) return // one submission at a time: no double taps
        viewModelScope.launch { repo.block { _tx.value = it } }
    }

    fun reset() {
        _tx.value = TxState.Review
    }
}

fun Context.activity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Rounded.Home), Explore("Explore", Icons.Rounded.Search),
    Passports("Passports", Icons.Rounded.Star), Profile("Profile", Icons.Rounded.Person),
}

@Composable
fun AnzenRoot(links: MutableStateFlow<String?>, vm: AppViewModel = hiltViewModel()) {
    AnzenTheme(vm.appearance) {
        val nav = rememberNavController()
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val state by vm.state.collectAsStateWithLifecycle()
        val start = remember {
            when {
                !vm.repo.prefs.onboarded -> "onboarding"
                !vm.repo.key.exists() -> "setup"
                else -> "main"
            }
        }
        val link by links.collectAsStateWithLifecycle()
        LaunchedEffect(link, state.hasKey) {
            val l = link ?: return@LaunchedEffect
            if (!state.hasKey) return@LaunchedEffect // routed once the account exists
            Links.route(l)?.let { nav.navigate(it) } ?: snackbar.showSnackbar("That link isn't an ANZEN link.")
            links.value = null
        }

        CompositionLocalProvider(LocalToast provides { msg -> scope.launch { snackbar.showSnackbar(msg) } }) {
            Box(Modifier.fillMaxSize().background(Anzen.colors.backgroundPrimary)) {
                AnzenNavHost(nav, start, vm)
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp)) {
                    Snackbar(it, containerColor = Anzen.colors.surfaceSealed, contentColor = Anzen.colors.onSealed,
                        shape = RoundedCornerShape(14.dp))
                }
            }
        }
    }
}

@Composable
private fun AnzenNavHost(nav: NavHostController, start: String, vm: AppViewModel) {
    val reduced = Anzen.reducedMotion
    val dur = if (reduced) 0 else Motion.page
    val go: (String) -> Unit = { r -> if (r == "setup") nav.navigate(r) { popUpTo(0) { inclusive = true } } else nav.navigate(r) }
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(nav, start,
        enterTransition = { if (reduced) fadeIn(tween(0)) else slideInHorizontally(tween(dur, easing = Motion.easing)) { it / 4 } + fadeIn(tween(dur)) },
        exitTransition = { fadeOut(tween(dur / 2)) },
        popEnterTransition = { fadeIn(tween(dur)) },
        popExitTransition = { if (reduced) fadeOut(tween(0)) else slideOutHorizontally(tween(dur, easing = Motion.easing)) { it / 4 } + fadeOut(tween(dur)) },
    ) {
        composable("onboarding") {
            OnboardingScreen(onDone = {
                vm.repo.prefs.onboarded = true
                nav.navigate(if (vm.repo.key.exists()) "main" else "setup") { popUpTo("onboarding") { inclusive = true } }
            })
        }
        composable("setup") {
            SetupScreen(vm, onDone = { nav.navigate("main") { popUpTo(0) { inclusive = true } } })
        }
        composable("main") { MainTabs(vm, go) }
        composable("create") { CreateScreen(vm, onClose = back, onCreated = { id -> nav.navigate("event/$id") { popUpTo("create") { inclusive = true } } }) }
        composable("event/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
            EventScreen(it.arguments!!.getLong("id"), vm, back, go)
        }
        composable("commit/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
            CommitScreen(it.arguments!!.getLong("id"), vm, back, go)
        }
        composable("checkin/{id}?code={code}", listOf(navArgument("id") { type = NavType.LongType },
            navArgument("code") { type = NavType.StringType; nullable = true; defaultValue = null })) {
            CheckInScreen(it.arguments!!.getLong("id"), it.arguments?.getString("code"), vm, back, go)
        }
        composable("scan") { ScanScreen(back, onLink = { route -> nav.navigate(route) { popUpTo("scan") { inclusive = true } } }) }
        composable("desk/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
            DeskScreen(it.arguments!!.getLong("id"), vm, back)
        }
        composable("treasury/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
            TreasuryScreen(it.arguments!!.getLong("id"), vm, back, go)
        }
        composable("result/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
            ResultScreen(it.arguments!!.getLong("id"), vm, back, go)
        }
        composable("passport/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
            PassportScreen(it.arguments!!.getLong("id"), vm, back, go)
        }
        composable("proof/{id}/{key}", listOf(navArgument("id") { type = NavType.LongType }, navArgument("key") { type = NavType.StringType })) {
            ProofScreen(it.arguments!!.getLong("id"), it.arguments!!.getString("key")!!, vm, back)
        }
        composable("activity") { ActivityScreen(vm, back, go) }
        composable("agent?agent={agent}&cap={cap}&days={days}&id={id}", listOf(
            navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("agent") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("cap") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("days") { type = NavType.StringType; nullable = true; defaultValue = null })) {
            val a = it.arguments
            AgentScreen(a?.getString("agent"), a?.getString("cap")?.toLongOrNull(), a?.getString("days")?.toIntOrNull(),
                a?.getString("id")?.toLongOrNull(), vm, back)
        }
    }
}

@Composable
private fun MainTabs(vm: AppViewModel, go: (String) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    BackHandler(enabled = tab != Tab.Home) { tab = Tab.Home }
    Box(Modifier.fillMaxSize()) {
        when (tab) {
            Tab.Home -> HomeScreen(vm, go, onExplore = { tab = Tab.Explore })
            Tab.Explore -> ExploreScreen(vm, go)
            Tab.Passports -> PassportsScreen(vm, go, onExplore = { tab = Tab.Explore })
            Tab.Profile -> ProfileScreen(vm, go)
        }
        TabBar(tab, onSelect = { tab = it }, onCreate = { go("create") }, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * Floating navigation on the functional layer: a near-opaque elevated material with a hairline edge
 * (Android has no Liquid Glass; content stays readable as it scrolls beneath). Create sits in the middle as
 * the one prominent action.
 */
@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit, onCreate: () -> Unit, modifier: Modifier = Modifier) {
    val c = Anzen.colors
    Row(
        modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().height(64.dp)
            .shadow(8.dp, RoundedCornerShape(32.dp), ambientColor = c.textPrimary.copy(alpha = 0.12f), spotColor = c.textPrimary.copy(alpha = 0.12f))
            .clip(RoundedCornerShape(32.dp)).background(c.chrome).border(1.dp, c.borderSubtle, RoundedCornerShape(32.dp))
            .padding(horizontal = 6.dp).selectableGroup(),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Tab.entries.forEachIndexed { i, t ->
            if (i == 2) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(c.accent).clickable(onClickLabel = "Create event", onClick = onCreate)
                    .semantics { contentDescription = "Create event" }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, null, tint = c.onAccent)
                }
            }
            val on = t == selected
            Column(
                Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(28.dp))
                    .selectable(on, role = Role.Tab) { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.size(width = 44.dp, height = 26.dp).clip(CircleShape).background(if (on) c.surfaceSecondary else Color.Transparent),
                    contentAlignment = Alignment.Center) {
                    Icon(t.icon, null, tint = if (on) c.brand else c.textTertiary, modifier = Modifier.size(22.dp))
                }
                Text(t.label, style = AnzenType.caption.copy(fontSize = AnzenType.caption.fontSize * 0.85f),
                    color = if (on) c.brand else c.textTertiary)
            }
        }
    }
}
