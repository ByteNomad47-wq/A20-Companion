// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.widgets

import android.Manifest
import android.app.PendingIntent
import android.appwidget.*
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.*
import android.os.Bundle
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import io.github.bytenomad47.a20.*
import io.github.bytenomad47.a20.R
import io.github.bytenomad47.a20.device.*
import io.github.bytenomad47.a20.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** System widget callbacks render local state only. They never connect to Bluetooth. */
abstract class A20WidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        render(context, manager, ids)

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        options: Bundle,
    ) = render(context, manager, intArrayOf(id))

    private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        val repo = context.a20.repository
        repo.scope.launch {
            try {
                repo.awaitInitialized()
                withContext(Dispatchers.Default) {
                    for (id in ids) {
                        val controls =
                            manager.getAppWidgetInfo(id)?.provider?.className ==
                                ControlsWidget::class.java.name
                        manager.updateAppWidget(
                            id,
                            WidgetRenderer.build(context, repo.state.value, controls),
                        )
                    }
                }
            } finally {
                pending?.finish()
            }
        }
    }
}

class BatteriesWidget : A20WidgetProvider()

class ControlsWidget : A20WidgetProvider()

internal object WidgetUpdates {
    @OptIn(FlowPreview::class)
    fun observe(application: A20Application) {
        val repo = application.repository
        repo.scope.launch {
            repo.awaitInitialized()
            repo.state.debounce(200).collectLatest { state ->
                withContext(Dispatchers.Default) { updateNow(application, state) }
            }
        }
    }

    fun refresh(application: A20Application) {
        val repo = application.repository
        repo.scope.launch {
            repo.awaitInitialized()
            withContext(Dispatchers.Default) { updateNow(application, repo.state.value) }
        }
    }

    private fun updateNow(application: A20Application, state: CompanionState) {
        val manager = AppWidgetManager.getInstance(application)
        for ((provider, controls) in
            listOf(BatteriesWidget::class.java to false, ControlsWidget::class.java to true)) {
            val ids = manager.getAppWidgetIds(ComponentName(application, provider))
            if (ids.isNotEmpty()) {
                try {
                    manager.updateAppWidget(ids, WidgetRenderer.build(application, state, controls))
                } catch (_: RuntimeException) {
                    android.util.Log.w(
                        "A20Widgets",
                        "Home screen widget update could not be applied.",
                    )
                }
            }
        }
    }
}

internal data class WidgetColors(
    val background: Int,
    val foreground: Int,
    val accent: Int,
    val onAccent: Int,
    val container: Int,
) {
    companion object {
        fun from(context: Context, choice: ThemeChoice): WidgetColors {
            val dark =
                when (choice) {
                    ThemeChoice.DARK -> true
                    ThemeChoice.LIGHT -> false
                    ThemeChoice.SYSTEM ->
                        context.resources.configuration.uiMode and
                            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                }
            fun color(id: Int) = context.getColor(id)
            return WidgetColors(
                color(
                    if (dark) android.R.color.system_neutral1_900
                    else android.R.color.system_neutral1_50
                ),
                color(
                    if (dark) android.R.color.system_neutral1_50
                    else android.R.color.system_neutral1_900
                ),
                color(
                    if (dark) android.R.color.system_accent1_200
                    else android.R.color.system_accent1_600
                ),
                color(
                    if (dark) android.R.color.system_neutral1_900
                    else android.R.color.system_neutral1_50
                ),
                color(
                    if (dark) android.R.color.system_neutral1_800
                    else android.R.color.system_neutral1_100
                ),
            )
        }
    }
}

internal object WidgetRenderer {
    private val cells =
        listOf(
            intArrayOf(
                R.id.widget_left_cell,
                R.id.widget_left_ring,
                R.id.widget_left_image,
                R.id.widget_left_value,
            ),
            intArrayOf(
                R.id.widget_right_cell,
                R.id.widget_right_ring,
                R.id.widget_right_image,
                R.id.widget_right_value,
            ),
            intArrayOf(
                R.id.widget_case_cell,
                R.id.widget_case_ring,
                R.id.widget_case_image,
                R.id.widget_case_value,
            ),
        )

    fun build(context: Context, state: CompanionState, controls: Boolean): RemoteViews {
        val model = presentWidget(state)
        val colors = WidgetColors.from(context, state.theme)
        // Keep the ring bitmaps small so the whole widget stays within Binder limits.
        val rings = model.batteries.map { ring(it, colors) }
        return buildLayout(context, state, model, colors, rings, controls)
    }

    private fun buildLayout(
        context: Context,
        state: CompanionState,
        model: WidgetPresentation,
        colors: WidgetColors,
        rings: List<Bitmap>,
        controls: Boolean,
    ): RemoteViews {
        val views =
            RemoteViews(
                context.packageName,
                if (controls) R.layout.widget_controls else R.layout.widget_batteries,
            )
        views.setColorStateList(
            android.R.id.background,
            "setBackgroundTintList",
            ColorStateList.valueOf(colors.background),
        )
        val open =
            PendingIntent.getActivity(
                context,
                0,
                // Match an ordinary launcher launch so the home app can retain the
                // widget as the transition source. Do not clear or rebuild the task.
                Intent.makeMainActivity(ComponentName(context, MainActivity::class.java))
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        views.setOnClickPendingIntent(android.R.id.background, open)
        for ((index, battery) in model.batteries.withIndex()) {
            val ids = cells[index]
            views.setImageViewBitmap(ids[1], rings[index])
            views.setTextViewText(ids[3], battery.percent?.let { "$it%" } ?: "—")
            views.setTextColor(
                ids[3],
                if (battery.available) colors.foreground else faded(colors.foreground, 0.5f),
            )
            views.setInt(ids[2], "setImageAlpha", if (battery.available) 255 else 100)
            val imageSize = if (controls) 42 else 38
            views.setViewLayoutHeight(ids[2], imageSize.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
            views.setViewLayoutWidth(ids[2], imageSize.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
            views.setViewVisibility(ids[3], if (controls) View.VISIBLE else View.GONE)
            views.setTextViewTextSize(ids[3], TypedValue.COMPLEX_UNIT_SP, 18f)
            val percentage = battery.percent?.let { "$it percent" } ?: "unavailable"
            val availability =
                if (index == 2) "last case reading relayed by earbuds"
                else if (battery.available) "last reported available"
                else "not connected or not reported"
            views.setContentDescription(
                ids[0],
                "${battery.label}: $percentage, $availability. Updated ${time(context,battery.receivedAt)}.",
            )
            views.setOnClickPendingIntent(ids[0], open)
        }
        if (controls) {
            val statusTime =
                if (model.modeReceivedAt > 0) " · ${time(context,model.modeReceivedAt)}" else ""
            views.setContentDescription(
                R.id.widget_open,
                "Open A20 Companion. ${model.status}$statusTime",
            )
            views.setOnClickPendingIntent(R.id.widget_open, open)
            views.setInt(R.id.widget_refresh, "setColorFilter", colors.foreground)
            views.setBoolean(R.id.widget_refresh, "setEnabled", !model.busy)
            views.setOnClickPendingIntent(
                R.id.widget_refresh,
                action(context, state, DeviceAction.Refresh, open),
            )
            for ((id, mode) in
                listOf(R.id.widget_bluetooth to Mode.BLUETOOTH, R.id.widget_sleep to Mode.SLEEP)) {
                val selected = model.lastMode == mode
                views.setInt(
                    id,
                    "setBackgroundResource",
                    if (selected) R.drawable.widget_button_selected else R.drawable.widget_button,
                )
                views.setColorStateList(
                    id,
                    "setBackgroundTintList",
                    ColorStateList.valueOf(if (selected) colors.accent else colors.container),
                )
                views.setTextColor(id, if (selected) colors.onAccent else colors.foreground)
                views.setBoolean(id, "setEnabled", !model.busy)
                views.setContentDescription(
                    id,
                    "Set ${mode.wireName} mode${if(selected) ", last reported selected" else ""}",
                )
                views.setOnClickPendingIntent(
                    id,
                    action(context, state, DeviceAction.SetMode(mode), open),
                )
            }
        }
        return views
    }

    private fun action(
        context: Context,
        state: CompanionState,
        action: DeviceAction,
        open: PendingIntent,
    ): PendingIntent {
        val permitted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        if (state.selectedName == null || !permitted) return open
        val request =
            when (action) {
                DeviceAction.Refresh -> 10
                is DeviceAction.SetMode -> if (action.mode == Mode.SLEEP) 11 else 12
                else -> error("Unsupported widget action")
            }
        // Genuine widget taps supply the Android user-interaction FGS exemption.
        // Immutable, explicit intents target only the private shared action service.
        return PendingIntent.getForegroundService(
            context,
            request,
            ActionService.actionIntent(context, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ring(battery: WidgetBattery, colors: WidgetColors): Bitmap {
        val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 13.5f
                strokeCap = Paint.Cap.ROUND
            }
        val bounds = RectF(10.5f, 10.5f, 181.5f, 181.5f)
        paint.color = faded(colors.foreground, .15f)
        canvas.drawOval(bounds, paint)
        if (battery.percent != null && battery.percent > 0) {
            paint.color =
                if (battery.available) Color.rgb(54, 214, 133) else faded(colors.foreground, .4f)
            canvas.drawArc(bounds, -90f, 360f * battery.percent / 100f, false, paint)
        }
        return bitmap
    }

    private fun faded(color: Int, alpha: Float) =
        Color.argb((255 * alpha).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    // Fixed calendar receipt times stay true even if a widget remains untouched overnight.
    private fun time(context: Context, at: Long): String =
        if (at <= 0) "never"
        else
            DateUtils.formatDateTime(
                context,
                at,
                DateUtils.FORMAT_SHOW_DATE or
                    DateUtils.FORMAT_NUMERIC_DATE or
                    DateUtils.FORMAT_NO_YEAR or
                    DateUtils.FORMAT_SHOW_TIME,
            )
}
