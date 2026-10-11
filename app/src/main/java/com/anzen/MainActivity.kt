package com.anzen

import android.animation.ObjectAnimator
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import com.anzen.ui.AnzenRoot
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.flow.MutableStateFlow

@HiltAndroidApp
class AnzenApp : Application()

/** FragmentActivity because BiometricPrompt needs one. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    /** anzen:// links (venue codes from the system camera, reminder taps) waiting to be routed. */
    val links = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The system launch screen shows the mark and hands off on the first frame; it is never held open.
        installSplashScreen().setOnExitAnimationListener { splash ->
            val reduced = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            if (reduced) return@setOnExitAnimationListener splash.remove()
            ObjectAnimator.ofFloat(splash.view, "alpha", 1f, 0f).apply {
                duration = 200
                doOnEnd { splash.remove() }
                start()
            }
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        links.value = intent?.dataString
        setContent { AnzenRoot(links) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        links.value = intent.dataString
    }
}
