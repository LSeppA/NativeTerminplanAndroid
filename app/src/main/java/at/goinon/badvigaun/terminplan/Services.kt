package at.goinon.badvigaun.terminplan

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat

object Reminders {
    private const val channel = "appointments"
    fun sync(context: Context, items: List<ScheduleItem>) {
        val manager = context.getSystemService(AlarmManager::class.java)
        ScheduleStore(context).load().forEach { item -> manager.cancel(intent(context, item)) }
        items.filter { !it.information }.forEach { item ->
            val fire = (item.startTime ?: return@forEach) - 10 * 60_000L
            if (fire > System.currentTimeMillis()) manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fire, intent(context, item))
        }
    }
    private fun intent(context: Context, item: ScheduleItem): PendingIntent {
        val id = ("${item.date}:${item.start}:${item.title}").hashCode()
        return PendingIntent.getBroadcast(context, id, Intent(context, ReminderReceiver::class.java).putExtra("title", item.title).putExtra("time", item.timeText), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    fun cancelAll(context: Context) { val alarm = context.getSystemService(AlarmManager::class.java); ScheduleStore(context).load().forEach { alarm.cancel(intent(context, it)) } }
    fun show(context: Context, title: String, time: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(channel, "Terminerinnerungen", NotificationManager.IMPORTANCE_HIGH))
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channel).setSmallIcon(android.R.drawable.ic_menu_my_calendar).setContentTitle("Termin in 10 Minuten").setContentText("$time • $title").setAutoCancel(true).setContentIntent(open).build()
        nm.notify(("$time:$title").hashCode(), notification)
    }
}

class ReminderReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Reminders.show(context, intent.getStringExtra("title") ?: "Termin", intent.getStringExtra("time") ?: "")
}
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { if (intent.action == Intent.ACTION_BOOT_COMPLETED) { val items = ScheduleStore(context).load(); Reminders.sync(context, items); StatusNotification.sync(context, items) } }
}

object StatusNotification {
    private const val channel = "current_appointment"
    private const val id = 31
    fun sync(context: Context, items: List<ScheduleItem>) {
        val now = System.currentTimeMillis()
        val alarm = context.getSystemService(AlarmManager::class.java)
        val refresh = PendingIntent.getBroadcast(context, 31, Intent(context, StatusReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(refresh)
        val shown = appointmentForDisplay(items, now)
        val nm = context.getSystemService(NotificationManager::class.java)
        if (shown == null) { nm.cancel(id); return }
        val nextChange = nextAppointmentDisplayChange(items, now)
        if (nextChange != null) alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextChange, refresh)
        nm.createNotificationChannel(NotificationChannel(channel, "Aktueller oder nächster Termin", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(id, NotificationCompat.Builder(context, channel).setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(if (isCurrentAppointment(shown, now)) "Jetzt" else "Demnächst")
            .setContentText("${shown.timeText} • ${shown.title}").setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).build())
    }
}
class StatusReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = StatusNotification.sync(context, ScheduleStore(context).load())
}

class NextAppointmentWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val items = ScheduleStore(context).load()
        val now = System.currentTimeMillis()
        val shown = appointmentForDisplay(items, now)
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_appointment)
            val concurrentCount = shown?.let { concurrentAppointmentCount(items, it) } ?: 0
            views.setTextViewText(R.id.widget_heading, if (shown != null && isCurrentAppointment(shown, now)) "Jetzt" else "Demnächst")
            views.setTextViewText(R.id.widget_text, shown?.let { "${it.timeText}  ${it.title}" } ?: "Noch keine Daten")
            views.setViewVisibility(R.id.widget_badge, if (concurrentCount > 1) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.widget_badge, concurrentCount.toString())
            views.setOnClickPendingIntent(R.id.widget_text, PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            manager.updateAppWidget(id, views)
        }
        val refresh = PendingIntent.getBroadcast(context, 7, Intent(context, NextAppointmentWidget::class.java).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.cancel(refresh)
        val switchTime = nextAppointmentDisplayChange(items, now)
        if (switchTime != null) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, switchTime, refresh)
        }
    }
    companion object { fun refresh(context: Context) { context.sendBroadcast(Intent(context, NextAppointmentWidget::class.java).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, NextAppointmentWidget::class.java)))) } }
}
