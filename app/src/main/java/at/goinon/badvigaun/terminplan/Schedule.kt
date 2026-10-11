package at.goinon.badvigaun.terminplan

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class ScheduleItem(
    val date: Long, val start: Int?, val end: Int?, val duration: Int?,
    val title: String, val location: String = "", val note: String = "", val information: Boolean = false,
    val photoPath: String? = null
) {
    fun at(minutes: Int): Long = Calendar.getInstance().apply { timeInMillis = date; set(Calendar.HOUR_OF_DAY, minutes / 60); set(Calendar.MINUTE, minutes % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    val startTime: Long? get() = start?.let(::at)
    val endTime: Long? get() = start?.let { at(end ?: it + (duration ?: 0)) }
    val timeText: String get() = start?.let { clock(it) + (end?.let { e -> " – ${clock(e)}" } ?: "") } ?: ""
    companion object { fun clock(minutes: Int) = "%02d:%02d".format(Locale.GERMAN, minutes / 60, minutes % 60) }
}

private const val UPCOMING_SWITCH_BEFORE_END = 5 * 60_000L

/** Shows the current appointment until five minutes before its end, then advances to the next one. */
fun appointmentForDisplay(items: List<ScheduleItem>, now: Long = System.currentTimeMillis()): ScheduleItem? {
    val appointments = items.filter { !it.information && it.startTime != null }.sortedBy { it.startTime }
    appointments.forEachIndexed { index, appointment ->
        val start = appointment.startTime ?: return@forEachIndexed
        val end = appointment.endTime ?: start
        if (now < start) return appointment
        if (now >= start && now < end) {
            val next = appointments.drop(index + 1).firstOrNull { (it.startTime ?: Long.MIN_VALUE) > start }
            val switchTime = maxOf(start, end - UPCOMING_SWITCH_BEFORE_END)
            if (next != null && now >= switchTime) return next
            return appointment
        }
    }
    return null
}

/** Returns all future times at which the displayed appointment or its status can change. */
fun appointmentDisplayChangeTimes(items: List<ScheduleItem>, now: Long = System.currentTimeMillis()): List<Long> {
    val appointments = items.filter { !it.information && it.startTime != null }.sortedBy { it.startTime }
    val changes = mutableListOf<Long>()
    appointments.forEachIndexed { index, appointment ->
        val start = appointment.startTime ?: return@forEachIndexed
        val end = appointment.endTime ?: start
        changes += start
        val hasLaterAppointment = appointments.drop(index + 1).any { (it.startTime ?: Long.MIN_VALUE) > start }
        if (hasLaterAppointment) changes += maxOf(start, end - UPCOMING_SWITCH_BEFORE_END)
        changes += end + 1
    }
    return changes.asSequence().filter { it > now }.distinct().sorted().toList()
}

/** Returns the next time at which [appointmentForDisplay] or its status must be refreshed. */
fun nextAppointmentDisplayChange(items: List<ScheduleItem>, now: Long = System.currentTimeMillis()): Long? =
    appointmentDisplayChangeTimes(items, now).firstOrNull()

fun isCurrentAppointment(item: ScheduleItem, now: Long = System.currentTimeMillis()): Boolean {
    val start = item.startTime ?: return false
    val end = item.endTime ?: start
    return now >= start && now < end
}

fun concurrentAppointmentCount(items: List<ScheduleItem>, item: ScheduleItem): Int =
    items.count { !it.information && it.date == item.date && it.start == item.start }

internal fun shouldKeepStoredItem(item: ScheduleItem, now: Long, today: Long): Boolean = when {
    item.information -> item.date >= today
    item.end != null || item.duration != null -> (item.endTime ?: item.date) >= now
    else -> item.date >= today
}

/** Replaces only days found in the new scan and keeps every other saved day. */
fun mergeScannedDays(existing: List<ScheduleItem>, scanned: List<ScheduleItem>, now: Long = System.currentTimeMillis()): List<ScheduleItem> {
    if (scanned.isEmpty()) return existing
    val scannedDays = scanned.map { it.date }.toSet()
    val preserved = existing.filter { it.date !in scannedDays }
    val today = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val imported = scanned.filter { if (it.information) it.date >= today else (it.endTime ?: 0) >= now }
    return (preserved + imported).sortedWith(compareBy<ScheduleItem> { it.date }.thenBy { it.start ?: Int.MAX_VALUE })
}

class ScheduleStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("schedule", Context.MODE_PRIVATE)
    fun load(): List<ScheduleItem> = try {
        val data = JSONArray(prefs.getString("items", "[]"))
        (0 until data.length()).map { data.getJSONObject(it) }.map { j ->
            ScheduleItem(j.getLong("date"), j.optIntOrNull("start"), j.optIntOrNull("end"), j.optIntOrNull("duration"), j.getString("title"), j.optString("location"), j.optString("note"), j.optBoolean("information"), j.optStringOrNull("photoPath"))
        }.let(::prune)
    } catch (_: Exception) { emptyList() }
    fun save(items: List<ScheduleItem>) {
        persist(items)
        AppointmentRefreshCoordinator.sync(context, items)
    }
    private fun persist(items: List<ScheduleItem>) {
        val array = JSONArray()
        items.forEach { i -> array.put(JSONObject().put("date", i.date).put("start", i.start).put("end", i.end).put("duration", i.duration).put("title", i.title).put("location", i.location).put("note", i.note).put("information", i.information).put("photoPath", i.photoPath)) }
        prefs.edit { putString("items", array.toString()) }
    }
    fun clear() {
        prefs.edit { remove("items") }
        AppointmentRefreshCoordinator.sync(context, emptyList())
    }
    private fun prune(items: List<ScheduleItem>): List<ScheduleItem> {
        val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val now = System.currentTimeMillis()
        val kept = items.filter { shouldKeepStoredItem(it, now, today) }
        if (kept.size != items.size) persist(kept)
        return kept.sortedWith(compareBy<ScheduleItem> { it.date }.thenBy { it.start ?: Int.MAX_VALUE })
    }
}

private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key) || !has(key)) null else getInt(key)
private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key) || !has(key)) null else getString(key).takeIf { it.isNotBlank() }

object ScheduleParser {
    private val datePattern = Regex("\\d{1,2}\\.\\d{1,2}\\.\\d{4}")
    private val timePattern = Regex("(\\d{1,2})[:.](\\d{2})(?:\\s*[-–—]\\s*(\\d{1,2})[:.](\\d{2}))?")
    private val durationPattern = Regex("(\\d{1,3})\\s*Min(?:\\.|uten?)?", RegexOption.IGNORE_CASE)
    private val roomCodePattern = Regex("\\([A-Za-z]\\d{4}\\)")
    private val prefixPattern = Regex("^\\s*\\d{1,2}[:.]\\d{2}(?:\\s*[-–—]\\s*\\d{1,2}[:.]\\d{2})?")
    private val endOfSchedulePattern = Regex("\\bsehr\\s+(?:verehrte|geehrte)\\b", RegexOption.IGNORE_CASE)
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).apply { isLenient = false }
    private fun clean(s: String) = roomCodePattern.replace(s, "").replace(Regex("\\s+"), " ").trim()
    private fun trimAtLateSlash(title: String): String {
        val slashIndex = title.indexOf('/', startIndex = 28)
        return if (slashIndex >= 0) title.substring(0, slashIndex).trim() else title
    }
    fun parse(lines: List<String>): List<ScheduleItem> {
        val items = mutableListOf<ScheduleItem>()
        var date: Long? = null
        var last: Int? = null
        var seen = false
        for (raw in lines) {
            if (endOfSchedulePattern.containsMatchIn(raw)) break
            val foundDate = datePattern.find(raw)
            if (foundDate != null) {
                runCatching { dateFormat.parse(foundDate.value) }.getOrNull()?.let { date = it.time; last = null; seen = false }
                continue
            }
            val day = date ?: continue
            val line = clean(raw)
            val time = timePattern.find(line)
            if (time != null) {
                val startHour = time.groupValues[1].toInt()
                val startMinute = time.groupValues[2].toInt()
                if (startHour !in 0..23 || startMinute !in 0..59) {
                    last = null
                    continue
                }
                val start = startHour * 60 + startMinute
                val end = if (time.groupValues[3].isNotEmpty()) {
                    val endHour = time.groupValues[3].toInt()
                    val endMinute = time.groupValues[4].toInt()
                    if (endHour !in 0..23 || endMinute !in 0..59) {
                        last = null
                        continue
                    }
                    endHour * 60 + endMinute
                } else null
                if (end != null && end < start) {
                    last = null
                    continue
                }
                var title = clean(prefixPattern.replace(line, ""))
                val note = if (listOf("ultraschall", "gymnastik", "funktionell", "elektro", "massage", "eisenstadt", "senso", "bewegung", "therapie").any { title.lowercase(Locale.GERMAN).contains(it) }) "bitte Leintuch zur Therapie mitnehmen!" else ""
                val duration = durationPattern.find(title)?.groupValues?.get(1)?.toIntOrNull()
                title = trimAtLateSlash(clean(durationPattern.replace(title, ""))).ifEmpty { "Termin" }
                items += ScheduleItem(day, start, end, duration, title, note = note)
                last = items.lastIndex; seen = true
            } else {
                val durationMatch = durationPattern.find(line)
                val previousIndex = last
                if (durationMatch != null && previousIndex != null) {
                    val minutes = durationMatch.groupValues[1].toInt()
                    val old = items[previousIndex]
                    items[previousIndex] = old.copy(duration = minutes, end = old.end ?: old.start?.plus(minutes))
                } else if (seen && line.isNotEmpty()) {
                    items += ScheduleItem(day, null, null, null, line, information = true)
                    last = null
                }
            }
        }
        return items
    }
}
