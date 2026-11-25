package com.dawncourse.app

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.dawncourse.feature.settings.WidgetPinResult
import com.dawncourse.feature.widget.DawnWidgetReceiver

/** REQUESTED 仅表示桌面接受了请求，实际添加仍由用户在系统窗口确认。 */
internal fun requestCourseWidgetPin(context: Context): WidgetPinResult {
    return try {
        val manager = AppWidgetManager.getInstance(context)
        if (!manager.isRequestPinAppWidgetSupported) return WidgetPinResult.UNSUPPORTED
        if (manager.requestPinAppWidget(ComponentName(context, DawnWidgetReceiver::class.java), null, null)) {
            WidgetPinResult.REQUESTED
        } else {
            WidgetPinResult.FAILED
        }
    } catch (_: RuntimeException) {
        WidgetPinResult.FAILED
    }
}
