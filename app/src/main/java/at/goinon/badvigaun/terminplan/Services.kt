package at.goinon.badvigaun.terminplan

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import java.util.UUID

private fun ScheduleItem.notificationText(): String =
    listOf(title, location.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" • ")

private object AlarmScheduling {
    @SuppressLint("ScheduleExactAlarm", "MissingPermission")
    fun set(context: Context, triggerAtMillis: Long, operation: PendingIntent): Boolean {
        val manager = context.getSystemService(AlarmManager::class.java)
        val canUseExactAlarm = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        if (canUseExactAlarm) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
                return true
            } catch (_: SecurityException) {
                // A device policy can revoke exact-alarm access after the capability check.
            } catch (_: IllegalStateException) {
                return false
            }
        }
        return try {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
    }
}

object Reminders {
    private const val CHANNEL_ID = "appointments"

    fun sync(context: Context, items: List<ScheduleItem>) {
        val manager = context.getSystemService(AlarmManager::class.java)
        ScheduleStore(context).load().forEach { item ->
            manager.cancel(intent(context, item))
            manager.cancel(legacyIntent(context, item))
        }
        items.filter { !it.information }.forEach { item ->
            val fire = (item.startTime ?: return@forEach) - 10 * 60_000L
            if (fire > System.currentTimeMillis()) AlarmScheduling.set(context, fire, intent(context, item))
        }
    }

    private fun fingerprint(item: ScheduleItem): String =
        "${item.date}:${item.start}:${item.end}:${item.duration}:${item.title}:${item.location}"

    private fun notificationId(item: ScheduleItem): Int =
        0x40000000 or (fingerprint(item).hashCode() and 0x3fffffff)

    private fun legacyIntent(context: Context, item: ScheduleItem): PendingIntent = PendingIntent.getBroadcast(
        context,
        "${item.date}:${item.start}:${item.title}".hashCode(),
        Intent(context, ReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun intent(context: Context, item: ScheduleItem): PendingIntent {
        val id = notificationId(item)
        return PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, ReminderReceiver::class.java)
                .setData("terminplan://reminder/${UUID.nameUUIDFromBytes(fingerprint(item).toByteArray())}".toUri())
                .putExtra("title", item.notificationText())
                .putExtra("time", item.timeText)
                .putExtra("notificationId", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun cancelAll(context: Context, clearDelivered: Boolean = false) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        ScheduleStore(context).load().forEach { item ->
            alarm.cancel(intent(context, item))
            alarm.cancel(legacyIntent(context, item))
        }
        if (clearDelivered) notifications.cancelAll()
    }

    fun show(context: Context, title: String, time: String, notificationId: Int) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Terminerinnerungen", NotificationManager.IMPORTANCE_HIGH)
        )
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle("Termin in 10 Minuten")
            .setContentText("$time • $title")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        notifications.notify(notificationId, notification)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "Termin"
        val time = intent.getStringExtra("time") ?: ""
        val id = intent.getIntExtra("notificationId", "$time:$title".hashCode())
        Reminders.show(context, title, time, id)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in refreshActions) return
        val items = ScheduleStore(context).load()
        Reminders.sync(context, items)
        AppointmentRefreshCoordinator.sync(context, items)
    }

    private companion object {
        val refreshActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED
        )
    }
}

object AppointmentRefreshCoordinator {
    fun sync(context: Context, items: List<ScheduleItem> = ScheduleStore(context).load()) {
        val now = System.currentTimeMillis()
        StatusNotification.render(context, items, now)
        NextAppointmentWidget.render(context, items, now)
        AppointmentRefreshScheduler.sync(context, items, now)
    }
}

private object AppointmentRefreshScheduler {
    private const val PREFERENCES_NAME = "appointment_refresh"
    private const val TIMES_KEY = "scheduled_times"
    private const val ACTION_REFRESH = "at.goinon.badvigaun.terminplan.REFRESH_APPOINTMENTS"
    private const val MAX_SCHEDULED_TRANSITIONS = 200

    fun sync(context: Context, items: List<ScheduleItem>, now: Long) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.getStringSet(TIMES_KEY, emptySet()).orEmpty()
            .mapNotNull(String::toLongOrNull)
            .forEach { manager.cancel(intent(context, it)) }

        cancelLegacyAlarms(context, manager)

        val scheduledTimes = appointmentDisplayChangeTimes(items, now)
            .take(MAX_SCHEDULED_TRANSITIONS)
            .filter { AlarmScheduling.set(context, it, intent(context, it)) }
        preferences.edit { putStringSet(TIMES_KEY, scheduledTimes.map(Long::toString).toSet()) }
    }

    private fun intent(context: Context, time: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        32,
        Intent(context, AppointmentRefreshReceiver::class.java)
            .setAction(ACTION_REFRESH)
            .setData("terminplan://appointment-refresh/$time".toUri()),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun cancelLegacyAlarms(context: Context, manager: AlarmManager) {
        val oldStatus = PendingIntent.getBroadcast(
            context,
            31,
            Intent(context, StatusReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val oldWidget = PendingIntent.getBroadcast(
            context,
            7,
            Intent(context, NextAppointmentWidget::class.java).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.cancel(oldStatus)
        manager.cancel(oldWidget)
    }
}

class AppointmentRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppointmentRefreshCoordinator.sync(context)
    }
}

object StatusNotification {
    private const val CHANNEL_ID = "current_appointment"
    private const val NOTIFICATION_ID = 31

    internal fun render(context: Context, items: List<ScheduleItem>, now: Long) {
        val shown = appointmentForDisplay(items, now)
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (shown == null) {
            notifications.cancel(NOTIFICATION_ID)
            return
        }
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Aktueller oder nächster Termin", NotificationManager.IMPORTANCE_LOW)
        )
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        notifications.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                .setContentTitle(if (isCurrentAppointment(shown, now)) "Jetzt" else "Demnächst")
                .setContentText("${shown.timeText} • ${shown.notificationText()}")
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .build()
        )
    }
}

/** Keeps previously scheduled refresh broadcasts from older app versions functional. */
class StatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = AppointmentRefreshCoordinator.sync(context)
}

class NextAppointmentWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        AppointmentRefreshCoordinator.sync(context)
    }

    override fun onEnabled(context: Context) {
        AppointmentRefreshCoordinator.sync(context)
    }

    override fun onDisabled(context: Context) {
        AppointmentRefreshCoordinator.sync(context)
    }

    companion object {
        internal fun render(context: Context, items: List<ScheduleItem>, now: Long) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NextAppointmentWidget::class.java))
            if (ids.isEmpty()) return
            val shown = appointmentForDisplay(items, now)
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_appointment)
                val concurrentCount = shown?.let { concurrentAppointmentCount(items, it) } ?: 0
                views.setTextViewText(
                    R.id.widget_heading,
                    if (shown != null && isCurrentAppointment(shown, now)) "Jetzt" else "Demnächst"
                )
                views.setTextViewText(
                    R.id.widget_text,
                    shown?.let { "${it.timeText}  ${it.notificationText()}" } ?: "Noch keine Daten"
                )
                views.setViewVisibility(R.id.widget_badge, if (concurrentCount > 1) View.VISIBLE else View.GONE)
                views.setTextViewText(R.id.widget_badge, concurrentCount.toString())
                views.setOnClickPendingIntent(R.id.widget_root, open)
                manager.updateAppWidget(id, views)
            }
        }
    }
}
