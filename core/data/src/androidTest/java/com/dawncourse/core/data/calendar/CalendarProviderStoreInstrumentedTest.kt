package com.dawncourse.core.data.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.ContextWrapper
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dawncourse.core.domain.model.CalendarOccurrence
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 使用独立 test target 包的本地日历账户，不读取或清除用户应用数据。 */
@RunWith(AndroidJUnit4::class)
class CalendarProviderStoreInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val store = CalendarProviderStore(context)
    private val uuid = "calendar-test-${UUID.randomUUID()}"
    private val cleanup = mutableListOf<Long>()
    private val event = CalendarOccurrence("first", "测试课程", "A101", "教师：测试", 1_800_000_000_000, 1_800_006_000_000)

    @Before fun grantPermissions() {
        listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR).forEach { permission ->
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} $permission")
                .use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        }
        assertTrue(store.hasPermission())
    }

    @After fun removeTestCalendars() {
        cleanup.forEach { id ->
            resolver.delete(syncUri(ContentUris.withAppendedId(Calendars.CONTENT_URI, id)), null, null)
        }
    }

    private fun calendar(profile: String = uuid): Long = store.ensureCalendar(profile, "测试课表").id.also { cleanup += it }

    @Test fun writesEventsAndAlertReminder() {
        val id = calendar()
        assertEquals(1, store.reconcile(uuid, listOf(event), 10, "测试课表"))
        val eventId = eventIds(id).single()
        assertEquals(listOf(10 to Reminders.METHOD_ALERT), reminderValues(eventId))
        assertEquals(10, store.find(uuid)?.reminderMinutes)
        assertTrue(store.find(uuid)!!.lastSyncAt > 0)
    }

    @Test fun repeatedSyncRetainsIdsAndDoesNotDuplicate() {
        val id = calendar()
        store.reconcile(uuid, listOf(event), 10, "测试课表")
        val before = eventIds(id)
        repeat(2) { store.reconcile(uuid, listOf(event), 10, "测试课表") }
        assertEquals(before, eventIds(id))
        assertEquals(1, reminderValues(before.single()).size)
    }

    @Test fun updatesLocationAndReminderOnSameEvent() {
        val id = calendar()
        store.reconcile(uuid, listOf(event), 10, "测试课表")
        val eventId = eventIds(id).single()
        store.reconcile(uuid, listOf(event.copy(location = "B202")), 30, "测试课表")
        assertEquals(listOf(eventId), eventIds(id))
        resolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), arrayOf(Events.EVENT_LOCATION), null, null, null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals("B202", it.getString(0))
        }
        assertEquals(listOf(30 to Reminders.METHOD_ALERT), reminderValues(eventId))
    }

    @Test fun removesObsoleteCoursesAfterWritingReplacement() {
        val id = calendar()
        store.reconcile(uuid, listOf(event, event.copy(key = "second", title = "第二门课")), 5, "测试课表")
        store.reconcile(uuid, listOf(event.copy(key = "replacement", title = "调课")), 5, "测试课表")
        assertEquals(1, eventIds(id).size)
        assertEquals(1, store.eventCount(uuid))
    }

    @Test fun preservesOtherProfilesCalendars() {
        calendar()
        val otherProfile = "$uuid-other"
        val otherId = calendar(otherProfile)
        store.reconcile(otherProfile, listOf(event), 15, "其他课表")
        store.reconcile(uuid, listOf(event), 10, "当前课表")
        store.remove(uuid)
        assertEquals(1, eventIds(otherId).size)
        assertEquals(15, store.find(otherProfile)?.reminderMinutes)
    }

    @Test fun removePreservesUserCreatedEventsInsideCourseCalendar() {
        val id = calendar()
        store.reconcile(uuid, listOf(event), 10, "测试课表")
        val personal = insertPersonalEvent(id)
        store.remove(uuid)
        assertEquals(listOf(personal), eventIds(id))
        assertNotNull(store.find(uuid))
        assertNull(store.find(uuid)?.reminderMinutes)
    }

    @Test fun removeDisablesEmptyManagedCalendarAndReusesItOnNextSync() {
        val id = calendar()
        store.reconcile(uuid, listOf(event), 10, "测试课表")
        store.remove(uuid)
        assertEquals(id, store.find(uuid)?.id)
        assertNull(store.find(uuid)?.reminderMinutes)
        assertTrue(eventIds(id).isEmpty())
        store.reconcile(uuid, listOf(event), 15, "测试课表")
        assertEquals(id, store.find(uuid)?.id)
        assertEquals(1, eventIds(id).size)
    }

    @Test fun batchesLargerTimetablesWithoutDuplicateEventsOrReminders() {
        val id = calendar()
        val many = (1..105).map { n -> event.copy(key = "course-$n", startMillis = event.startMillis + n * 86_400_000L,
            endMillis = event.endMillis + n * 86_400_000L) }
        store.reconcile(uuid, many, 10, "测试课表")
        store.reconcile(uuid, many, 15, "测试课表")
        assertEquals(105, eventIds(id).size)
        assertTrue(eventIds(id).all { reminderValues(it) == listOf(15 to Reminders.METHOD_ALERT) })
    }

    @Test fun clearingCoursesRetainsOnlyPersonalEvents() {
        val id = calendar()
        store.reconcile(uuid, listOf(event), 10, "测试课表")
        val personal = insertPersonalEvent(id)
        store.reconcile(uuid, emptyList(), 10, "测试课表")
        assertEquals(listOf(personal), eventIds(id))
        assertEquals(0, store.eventCount(uuid))
    }

    @Test fun resyncRestoresCanceledEventAndRemovedReminder() {
        val id = calendar()
        store.reconcile(uuid, listOf(event), 10, "测试课表")
        val eventId = eventIds(id).single()
        resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
            ContentValues().apply { put(Events.STATUS, Events.STATUS_CANCELED) }, null, null)
        resolver.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID}=?", arrayOf(eventId.toString()))
        store.reconcile(uuid, listOf(event), 10, "测试课表")
        resolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), arrayOf(Events.STATUS), null, null, null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals(Events.STATUS_CONFIRMED, it.getInt(0))
        }
        assertEquals(listOf(10 to Reminders.METHOD_ALERT), reminderValues(eventId))
    }

    @Test fun sameProfileMarkerInAnotherAccountIsNeverModified() {
        calendar()
        val foreignStore = CalendarProviderStore(object : ContextWrapper(context) {
            override fun getPackageName(): String = "${context.packageName}.foreign-test"
        })
        val foreign = foreignStore.ensureCalendar(uuid, "其他应用账户")
        try {
            foreignStore.reconcile(uuid, listOf(event), 30, "其他应用账户")
            store.reconcile(uuid, listOf(event), 10, "当前应用")
            store.remove(uuid)
            assertEquals(1, eventIds(foreign.id).size)
            assertEquals(30, foreignStore.find(uuid)?.reminderMinutes)
        } finally {
            val uri = ContentUris.withAppendedId(Calendars.CONTENT_URI, foreign.id).buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(Calendars.ACCOUNT_NAME, "${context.packageName}.foreign-test.course-calendar")
                .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
            resolver.delete(uri, null, null)
        }
    }

    private fun eventIds(calendarId: Long): List<Long> = resolver.query(Events.CONTENT_URI, arrayOf(Events._ID),
        "${Events.CALENDAR_ID}=? AND ${Events.DELETED}=0", arrayOf(calendarId.toString()), "${Events._ID} ASC")!!.use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
    }

    private fun reminderValues(eventId: Long): List<Pair<Int, Int>> = resolver.query(Reminders.CONTENT_URI,
        arrayOf(Reminders.MINUTES, Reminders.METHOD), "${Reminders.EVENT_ID}=?", arrayOf(eventId.toString()), null)!!.use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getInt(0) to cursor.getInt(1)) }
    }

    private fun insertPersonalEvent(calendarId: Long): Long = ContentUris.parseId(resolver.insert(Events.CONTENT_URI,
        ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId); put(Events.TITLE, "用户个人日程")
            put(Events.DTSTART, event.startMillis); put(Events.DTEND, event.endMillis)
            put(Events.EVENT_TIMEZONE, "Asia/Shanghai")
        })!!)

    private fun syncUri(uri: Uri): Uri = uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, "${context.packageName}.course-calendar")
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
}
