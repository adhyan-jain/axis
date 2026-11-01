package app.axis.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import axis.agent.CalendarSource
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Today's events from the phone's calendar, read-only. Returns nothing if the permission isn't granted. */
class AndroidCalendarSource(private val context: Context) : CalendarSource {
    override fun todayEvents(): List<String> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
        val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, start.timeInMillis); android.content.ContentUris.appendId(it, end.timeInMillis)
        }.build()
        val time = SimpleDateFormat("h:mm a", Locale.getDefault())
        val out = mutableListOf<String>()
        context.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
            null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext() && out.size < 10) {
                val title = c.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                out += if (c.getInt(2) == 1) "$title (all day)" else "${time.format(Date(c.getLong(1)))} $title"
            }
        }
        return out
    }
}
