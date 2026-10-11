package com.anzen.core

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.anzen.MainActivity
import com.anzen.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local check-in reminders for events this account committed to, scheduled from real onchain commitments.
 * These are not push notifications: no push service is configured, so outcomes are seen on the next app open.
 *
 * ponytail: inexact alarms, not restored after reboot (they're rescheduled on the next app refresh);
 * add a BOOT_COMPLETED receiver if reminders must survive restarts without opening the app.
 */
@Singleton
class Reminders @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val alarms get() = ctx.getSystemService(AlarmManager::class.java)

    fun schedule(commitments: List<Event>) {
        val now = System.currentTimeMillis() / 1000
        commitments.forEach { e ->
            val pi = intent(e.id, e.title)
            alarms?.cancel(pi)
            if (e.me?.status == "committed" && !e.cancelled && e.checkInStart > now) {
                alarms?.setWindow(AlarmManager.RTC_WAKEUP, e.checkInStart * 1000, 5 * 60_000, pi)
            }
        }
    }

    fun cancel(events: List<Event>) = events.forEach { alarms?.cancel(intent(it.id, it.title)) }

    private fun intent(eventId: Long, title: String) = PendingIntent.getBroadcast(ctx, eventId.toInt(),
        Intent(ctx, Receiver::class.java).putExtra("id", eventId).putExtra("title", title),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    class Receiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Check-in reminders", NotificationManager.IMPORTANCE_DEFAULT))
            val id = intent.getLongExtra("id", 0)
            val open = PendingIntent.getActivity(ctx, id.toInt(),
                Intent(Intent.ACTION_VIEW, Uri.parse("anzen://event/$id"), ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE)
            nm.notify(id.toInt(), NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_anzen)
                .setContentTitle("Check-in is open")
                .setContentText("${intent.getStringExtra("title")}: scan the venue code to keep your commitment.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        }
    }

    companion object {
        const val CHANNEL = "checkin"
    }
}
