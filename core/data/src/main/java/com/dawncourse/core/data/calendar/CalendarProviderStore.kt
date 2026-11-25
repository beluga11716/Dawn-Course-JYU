package com.dawncourse.core.data.calendar

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import com.dawncourse.core.domain.model.CalendarOccurrence
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** 所有查询和写入均限定在本安装创建且有来源标记的本地日历。 */
@Singleton
class CalendarProviderStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val resolver get() = context.contentResolver
    private val account = "${context.packageName}.course-calendar"

    data class OwnedCalendar(val id: Long, val reminderMinutes: Int?, val lastSyncAt: Long)
    private data class StoredEvent(val id: Long, val occurrence: CalendarOccurrence, val validMetadata: Boolean)

    fun hasPermission(): Boolean = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun find(profileUuid: String): OwnedCalendar? {
        val calendars: List<OwnedCalendar> = resolver.query(Calendars.CONTENT_URI,
            arrayOf(Calendars._ID, Calendars.CAL_SYNC2, Calendars.CAL_SYNC3),
            "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.CAL_SYNC1}=?",
            arrayOf(account, CalendarContract.ACCOUNT_TYPE_LOCAL, calendarMarker(profileUuid)), null)
            ?.use { cursor ->
                buildList<OwnedCalendar> {
                    while (cursor.moveToNext()) add(OwnedCalendar(cursor.getLong(0), cursor.getString(1)?.toIntOrNull(), cursor.getString(2)?.toLongOrNull() ?: 0))
                }
            } ?: throw IOException("系统日历服务暂不可用")
        check(calendars.size <= 1) { "发现重复的课程日历，请在系统日历中检查后重试" }
        return calendars.singleOrNull()
    }

    fun ensureCalendar(profileUuid: String, displayName: String): OwnedCalendar = find(profileUuid) ?: run {
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, account)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, calendarMarker(profileUuid))
            put(Calendars.CALENDAR_DISPLAY_NAME, "课程表 · $displayName")
            put(Calendars.OWNER_ACCOUNT, account)
            put(Calendars.CALENDAR_COLOR, 0xFF388E3C.toInt())
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.CALENDAR_TIME_ZONE, TIME_ZONE)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.ALLOWED_REMINDERS, Reminders.METHOD_ALERT.toString())
            put(Calendars.MAX_REMINDERS, 1)
            put(Calendars.CAL_SYNC1, calendarMarker(profileUuid))
        }
        val uri = resolver.insert(syncUri(Calendars.CONTENT_URI), values) ?: throw IOException("无法创建课程日历")
        OwnedCalendar(ContentUris.parseId(uri), null, 0)
    }

    fun eventCount(profileUuid: String): Int = find(profileUuid)?.let { readEvents(it.id).size } ?: 0

    /** 新增和修改全部成功后才移除旧日程；失败可重试，已完成的分批写入不会再次插入。 */
    fun reconcile(profileUuid: String, desired: List<CalendarOccurrence>, reminderMinutes: Int, displayName: String): Int {
        val calendar = ensureCalendar(profileUuid, displayName)
        val existing = readEvents(calendar.id)
        val reminders = readReminders(existing.map { it.id })
        val byKey = existing.groupBy { it.occurrence.key }
        desired.chunked(40).forEach { chunk ->
            val operations = arrayListOf<ContentProviderOperation>()
            chunk.forEach { occurrence ->
                val previous = byKey[occurrence.key]?.firstOrNull()
                if (previous == null) {
                    val eventIndex = operations.size
                    operations += ContentProviderOperation.newInsert(syncUri(Events.CONTENT_URI))
                        .withValues(eventValues(calendar.id, occurrence)).build()
                    operations += ContentProviderOperation.newInsert(syncUri(Reminders.CONTENT_URI))
                        .withValueBackReference(Reminders.EVENT_ID, eventIndex)
                        .withValue(Reminders.MINUTES, reminderMinutes).withValue(Reminders.METHOD, Reminders.METHOD_ALERT).build()
                } else if (previous.occurrence != occurrence || !previous.validMetadata || reminders[previous.id] != listOf(reminderMinutes to Reminders.METHOD_ALERT)) {
                    operations += ContentProviderOperation.newUpdate(syncUri(Events.CONTENT_URI))
                        .withSelection("${Events._ID}=? AND ${Events.CALENDAR_ID}=? AND ${Events.SYNC_DATA1}=?", arrayOf(previous.id.toString(), calendar.id.toString(), eventMarker(occurrence.key)))
                        .withValues(eventValues(calendar.id, occurrence)).withExpectedCount(1).build()
                    operations += ContentProviderOperation.newDelete(syncUri(Reminders.CONTENT_URI))
                        .withSelection("${Reminders.EVENT_ID}=?", arrayOf(previous.id.toString())).build()
                    operations += ContentProviderOperation.newInsert(syncUri(Reminders.CONTENT_URI))
                        .withValue(Reminders.EVENT_ID, previous.id).withValue(Reminders.MINUTES, reminderMinutes)
                        .withValue(Reminders.METHOD, Reminders.METHOD_ALERT).build()
                }
            }
            apply(operations)
        }
        val retainedIds = desired.mapNotNull { byKey[it.key]?.firstOrNull()?.id }.toSet()
        val wantedKeys = desired.map { it.key }.toSet()
        val obsolete = existing.filter { it.occurrence.key !in wantedKeys || it.id !in retainedIds }
        deleteEvents(calendar.id, obsolete)
        val values = ContentValues().apply {
            put(Calendars.CAL_SYNC2, reminderMinutes.toString())
            put(Calendars.CAL_SYNC3, System.currentTimeMillis().toString())
            put(Calendars.CALENDAR_DISPLAY_NAME, "课程表 · $displayName")
        }
        check(updateCalendar(profileUuid, calendar.id, values) == 1) { "课程日历已变化，请重新同步" }
        return desired.size
    }

    fun remove(profileUuid: String) {
        val calendar = find(profileUuid) ?: return
        deleteEvents(calendar.id, readEvents(calendar.id))
        // 始终保留日历，避免其他应用并发新增的个人日程被日历级联删除。
        check(updateCalendar(profileUuid, calendar.id,
            ContentValues().apply { putNull(Calendars.CAL_SYNC2); putNull(Calendars.CAL_SYNC3) }) == 1) {
            "课程日历已变化，请重试"
        }
    }

    private fun updateCalendar(profileUuid: String, id: Long, values: ContentValues): Int =
        // AOSP 将以 _id= 开头的 selection 整段当成数字解析；账户条件放前面以走完整筛选。
        resolver.update(syncUri(Calendars.CONTENT_URI), values,
            "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.CAL_SYNC1}=? AND ${Calendars._ID}=?",
            arrayOf(account, CalendarContract.ACCOUNT_TYPE_LOCAL, calendarMarker(profileUuid), id.toString()))

    private fun readEvents(calendarId: Long): List<StoredEvent> = resolver.query(Events.CONTENT_URI,
        arrayOf(Events._ID, Events.SYNC_DATA1, Events.TITLE, Events.EVENT_LOCATION, Events.DESCRIPTION, Events.DTSTART, Events.DTEND,
            Events.EVENT_TIMEZONE, Events.STATUS, Events.ALL_DAY, Events.HAS_ALARM),
        "${Events.CALENDAR_ID}=? AND ${Events.DELETED}=0 AND ${Events.SYNC_DATA1} LIKE ?",
        arrayOf(calendarId.toString(), "$EVENT_PREFIX%"), "${Events._ID} ASC")?.use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(StoredEvent(cursor.getLong(0), CalendarOccurrence(
                cursor.getString(1).removePrefix(EVENT_PREFIX), cursor.getString(2).orEmpty(), cursor.getString(3).orEmpty(),
                cursor.getString(4).orEmpty(), cursor.getLong(5), cursor.getLong(6)),
                cursor.getString(7) == TIME_ZONE && cursor.getInt(8) == Events.STATUS_CONFIRMED &&
                    cursor.getInt(9) == 0 && cursor.getInt(10) == 1))
        }
    } ?: throw IOException("无法读取课程日程")

    private fun readReminders(ids: List<Long>): Map<Long, List<Pair<Int, Int>>> {
        val reminders = mutableMapOf<Long, MutableList<Pair<Int, Int>>>()
        ids.chunked(100).forEach { chunk ->
            resolver.query(Reminders.CONTENT_URI, arrayOf(Reminders.EVENT_ID, Reminders.MINUTES, Reminders.METHOD),
                "${Reminders.EVENT_ID} IN (${chunk.joinToString(",") { "?" }})", chunk.map { it.toString() }.toTypedArray(), null)
                ?.use { cursor ->
                    while (cursor.moveToNext()) reminders.getOrPut(cursor.getLong(0)) { mutableListOf() }
                        .add(cursor.getInt(1) to cursor.getInt(2))
                } ?: throw IOException("无法读取日程提醒")
        }
        return reminders
    }

    private fun deleteEvents(calendarId: Long, events: List<StoredEvent>) = events.chunked(80).forEach { chunk ->
        apply(ArrayList(chunk.map { event ->
            ContentProviderOperation.newDelete(syncUri(Events.CONTENT_URI))
                .withSelection("${Events._ID}=? AND ${Events.CALENDAR_ID}=? AND ${Events.SYNC_DATA1}=?",
                    arrayOf(event.id.toString(), calendarId.toString(), eventMarker(event.occurrence.key))).build()
        }))
    }

    private fun eventValues(calendarId: Long, event: CalendarOccurrence) = ContentValues().apply {
        put(Events.CALENDAR_ID, calendarId)
        put(Events.SYNC_DATA1, eventMarker(event.key))
        put(Events.TITLE, event.title)
        put(Events.EVENT_LOCATION, event.location)
        put(Events.DESCRIPTION, event.description)
        put(Events.DTSTART, event.startMillis)
        put(Events.DTEND, event.endMillis)
        put(Events.EVENT_TIMEZONE, TIME_ZONE)
        put(Events.HAS_ALARM, 1)
        put(Events.ALL_DAY, 0)
        put(Events.STATUS, Events.STATUS_CONFIRMED)
    }

    private fun apply(operations: ArrayList<ContentProviderOperation>) {
        if (operations.isNotEmpty()) resolver.applyBatch(CalendarContract.AUTHORITY, operations)
    }

    private fun syncUri(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, account)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()

    private fun calendarMarker(uuid: String) = "dawn-course-v1:$uuid"
    private fun eventMarker(key: String) = "$EVENT_PREFIX$key"

    private companion object {
        const val EVENT_PREFIX = "dawn-course-event-v1:"
        const val TIME_ZONE = "Asia/Shanghai"
    }
}
