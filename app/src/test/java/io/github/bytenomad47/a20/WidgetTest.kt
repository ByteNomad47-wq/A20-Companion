// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20

import android.Manifest
import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Parcel
import android.util.SizeF
import android.view.View
import android.widget.*
import io.github.bytenomad47.a20.device.*
import io.github.bytenomad47.a20.protocol.*
import io.github.bytenomad47.a20.widgets.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetTest {
    private val app
        get() = RuntimeEnvironment.getApplication()

    private fun state() =
        CompanionState(selectedName = "Private test name")
            .withSnapshot(
                DeviceSnapshot(Ear.LEFT, false, 80, null, 100, Mode.SLEEP, "01.57", null, 30),
                System.currentTimeMillis(),
            )

    private fun render(state: CompanionState = state(), controls: Boolean = true): View =
        WidgetRenderer.build(app, state, controls).apply(app, FrameLayout(app))

    @Test
    fun cachedAndMissingReadingsDoNotInventConnectionsOrPercentages() {
        val cached = CompanionState(left = BatteryReading(90, 1000))
        val model = presentWidget(cached)
        assertEquals(90, model.batteries[0].percent)
        assertEquals(1000L, model.batteries[0].receivedAt)
        assertFalse(model.batteries[0].available)
        assertNull(model.batteries[1].percent)
        assertNull(model.lastMode)
        val reported = presentWidget(state())
        assertTrue(reported.batteries[0].available)
        assertFalse(reported.batteries[1].available)
        assertEquals(Mode.SLEEP, reported.lastMode)
        assertTrue(reported.status.startsWith("Last mode:"))
        val off = presentWidget(state().copy(phone = PhoneConnection.DISCONNECTED))
        assertTrue(off.batteries.none { it.available })
        assertEquals(80, off.batteries[0].percent)
    }

    @Test
    fun remoteViewsInflateAndPreserveMissingValuesAndReceiptTimes() {
        val view = render()
        assertEquals("80%", view.findViewById<TextView>(R.id.widget_left_value).text)
        assertEquals("—", view.findViewById<TextView>(R.id.widget_right_value).text)
        assertTrue(
            view.findViewById<View>(R.id.widget_left_cell).contentDescription.contains("Updated")
        )
        assertTrue(
            view.findViewById<View>(R.id.widget_case_cell).contentDescription.contains("relayed")
        )
        assertFalse(
            view
                .findViewById<View>(android.R.id.background)
                .contentDescription
                ?.contains("Private") == true
        )
    }

    @Test
    fun widgetButtonsDispatchDistinctImmutablePrivateServiceActionsWithoutAutomationOptIn() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val view = render()
        for ((id, operation, request) in
            listOf(
                Triple(R.id.widget_sleep, "sleep", 11),
                Triple(R.id.widget_bluetooth, "bluetooth", 12),
                Triple(R.id.widget_refresh, "refresh", 10),
            )) {
            assertTrue(view.findViewById<View>(id).performClick())
            val intent = shadowOf(app).nextStartedService
            assertEquals(ComponentName(app, ActionService::class.java), intent.component)
            assertEquals(operation, intent.getStringExtra("operation"))
            assertFalse(intent.hasExtra("address"))
            val pending =
                PendingIntent.getForegroundService(
                    app,
                    request,
                    intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                )
            assertNotNull(pending)
            assertTrue(shadowOf(pending).isImmutable)
            assertTrue(shadowOf(pending).isForegroundService)
        }
    }

    @Test
    fun missingDeviceOrPermissionOpensAppAndBusyButtonsCannotStartWork() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val busy = render(state().copy(busy = true))
        assertFalse(busy.findViewById<View>(R.id.widget_sleep).isEnabled)
        assertFalse(busy.findViewById<View>(R.id.widget_refresh).isEnabled)
        val initial = render(CompanionState())
        initial.findViewById<View>(R.id.widget_sleep).performClick()
        assertEquals(
            ComponentName(app, MainActivity::class.java),
            shadowOf(app).nextStartedActivity.component,
        )
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        render().findViewById<View>(R.id.widget_bluetooth).performClick()
        assertEquals(
            ComponentName(app, MainActivity::class.java),
            shadowOf(app).nextStartedActivity.component,
        )
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun fixedLayoutsMatchTheThreePlusTwoColumnMockup() {
        for ((controls, size) in listOf(false to SizeF(228f, 80f), true to SizeF(380f, 160f))) {
            val snapshot =
                DeviceSnapshot(
                    Ear.LEFT,
                    true,
                    90,
                    80,
                    if (controls) 100 else null,
                    Mode.SLEEP,
                    "01.57",
                    "01.57",
                    30,
                )
            val state =
                CompanionState(selectedName = "Synthetic preview", theme = ThemeChoice.DARK)
                    .withSnapshot(snapshot, System.currentTimeMillis())
            val view = WidgetRenderer.build(app, state, controls).apply(app, FrameLayout(app))
            val density = app.resources.displayMetrics.density
            val width = (size.width * density).toInt()
            val height = (size.height * density).toInt()
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file =
                File("build/screenshots/widget-mockup-${if(controls) "large" else "compact"}.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertEquals(
                if (controls) View.VISIBLE else View.GONE,
                view.findViewById<View>(R.id.widget_left_value).visibility,
            )
            if (controls) {
                val bluetooth = view.findViewById<View>(R.id.widget_bluetooth)
                val sleep = view.findViewById<View>(R.id.widget_sleep)
                assertTrue(bluetooth.bottom < sleep.top)
                assertEquals(bluetooth.width, sleep.width)
            }
        }
    }

    @Test
    fun compactTapOpensAppAndPayloadStaysBelowBinderBudget() {
        val compact = render(controls = false)
        compact.findViewById<View>(android.R.id.background).performClick()
        assertEquals(
            ComponentName(app, MainActivity::class.java),
            shadowOf(app).nextStartedActivity.component,
        )
        for (controls in listOf(false, true)) {
            val parcel = Parcel.obtain()
            try {
                WidgetRenderer.build(app, state(), controls).writeToParcel(parcel, 0)
                assertTrue("Widget payload exceeds safe IPC size", parcel.dataSize() < 750_000)
            } finally {
                parcel.recycle()
            }
        }
    }

    @Test
    fun bothWidgetsExposeTheirFullBackgroundAndLaunchAsANormalHomeApp() {
        for (controls in listOf(false, true)) {
            val view = render(controls = controls)
            val background = view.findViewById<View>(android.R.id.background)
            assertSame(view, background)
            assertTrue(background.clipToOutline)
            // Every battery uses the same app launch, without task-clearing flags.
            for (id in
                listOf(android.R.id.background, R.id.widget_left_cell, R.id.widget_case_cell)) {
                assertTrue(view.findViewById<View>(id).performClick())
                val intent = shadowOf(app).nextStartedActivity
                assertEquals(ComponentName(app, MainActivity::class.java), intent.component)
                assertEquals(Intent.ACTION_MAIN, intent.action)
                assertTrue(intent.hasCategory(Intent.CATEGORY_LAUNCHER))
                assertEquals(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                    intent.flags,
                )
            }
        }
    }
}
