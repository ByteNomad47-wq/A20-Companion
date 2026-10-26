// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package io.github.bytenomad47.a20

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.bytenomad47.a20.device.*
import io.github.bytenomad47.a20.protocol.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

enum class Page {
    SLEEP_HISTORY,
    HOME,
    SETTINGS,
    REPAIR,
    FIND,
}

@Composable
fun A20App(
    repository: CompanionRepository,
    onChoose: () -> Unit,
    onRefresh: () -> Unit,
    onAction: (DeviceAction) -> Unit,
    onReady: (Boolean) -> Unit,
    onAutomation: (Boolean) -> Unit,
    onTheme: (ThemeChoice) -> Unit,
    onAddTile: () -> Unit,
    onExport: () -> Unit,
    onBatterySettings: () -> Unit,
    onAddBatteryWidget: () -> Unit = {},
    onAddControlsWidget: () -> Unit = {},
    repairContent: (@Composable (() -> Unit) -> Unit)? = null,
    initialPage: Page = Page.HOME,
    onSleepExport: (String) -> Unit = {},
    dialogs: @Composable () -> Unit = {},
) {
    val state by repository.state.collectAsStateWithLifecycle()
    var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialPage) }
    val context = LocalContext.current
    PlaybackPresence(page == Page.HOME, repository::setPlaybackVisible)
    val dark =
        when (state.theme) {
            ThemeChoice.SYSTEM -> isSystemInDarkTheme()
            ThemeChoice.DARK -> true
            ThemeChoice.LIGHT -> false
        }
    val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialExpressiveTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            when (page) {
                Page.HOME ->
                    Dashboard(
                        state,
                        onChoose,
                        onRefresh,
                        onAction,
                        { page = Page.SETTINGS },
                        onFind = { page = Page.FIND },
                    )
                Page.SETTINGS ->
                    SettingsPage(
                        state,
                        onReady,
                        onAutomation,
                        onTheme,
                        onExport,
                        onBatterySettings,
                        { page = Page.REPAIR },
                        { page = Page.HOME },
                        onSleepHistory = { page = Page.SLEEP_HISTORY },
                        onAddTile = onAddTile,
                        onAddBatteryWidget = onAddBatteryWidget,
                        onAddControlsWidget = onAddControlsWidget,
                    )
                Page.FIND ->
                    FindEarbudsPage(
                        state,
                        onBack = { page = Page.HOME },
                        session = (context.applicationContext as? A20Application)?.find,
                    )
                Page.SLEEP_HISTORY ->
                    (context.applicationContext as? A20Application)?.sleepHistory?.let {
                        SleepHistoryPage(
                            it,
                            state.selectedName,
                            state.busy,
                            { page = Page.SETTINGS },
                            onSleepExport,
                        )
                    }
                Page.REPAIR ->
                    if (repairContent != null) repairContent { page = Page.SETTINGS }
                    else Text("Charging repair is unavailable in this view.")
            }
        }
        dialogs()
    }
    androidx.activity.compose.BackHandler(enabled = page != Page.HOME) {
        page = if (page == Page.REPAIR || page == Page.SLEEP_HISTORY) Page.SETTINGS else Page.HOME
    }
}

@Composable
private fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

@Composable
internal fun Dashboard(
    s: CompanionState,
    onChoose: () -> Unit,
    onRefresh: () -> Unit,
    onAction: (DeviceAction) -> Unit,
    onSettings: () -> Unit,
    onFind: () -> Unit = {},
) {
    var clockTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                clockTick = System.currentTimeMillis()
                delay(15_000)
            }
        }
    }
    // A status packet can arrive between clock ticks. Sample the wall clock during
    // composition too, so a fresh receipt is never compared against a stale UI clock.
    val now = maxOf(clockTick, System.currentTimeMillis())
    PullToRefreshBox(
        isRefreshing = s.busy,
        onRefresh = { if (!s.busy && s.selectedName != null) onRefresh() },
        modifier = Modifier.fillMaxSize().testTag("dashboard"),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val compact = maxHeight < 620.dp || LocalDensity.current.fontScale > 1.3f
            val summary: @Composable () -> Unit = {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Sleep A20",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row {
                            TextButton(onClick = onFind) { Text("Find earbuds") }
                            TextButton(onClick = onSettings) { Text("Settings") }
                        }
                    }
                    Card(
                        onClick = onChoose,
                        enabled = !s.busy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ),
                    ) {
                        Row(
                            Modifier.padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                Modifier.size(9.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(
                                        if (s.phone == PhoneConnection.CONNECTED)
                                            MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline
                                    )
                            )
                            Text(
                                if (s.selectedName == null) "Choose your A20"
                                else
                                    when (s.phone) {
                                        PhoneConnection.CONNECTED -> "${s.selectedName} connected"
                                        PhoneConnection.DISCONNECTED ->
                                            "${s.selectedName} disconnected"
                                        PhoneConnection.UNKNOWN ->
                                            if (s.busy) "${s.selectedName} · checking connection…"
                                            else "${s.selectedName} · connection unknown"
                                    },
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                    if (LocalDensity.current.fontScale > 1.3f) {
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            BatteryCard(
                                "Left",
                                s.left,
                                now,
                                earAvailable(s, Ear.LEFT),
                                Modifier.fillMaxWidth(),
                            )
                            BatteryCard(
                                "Right",
                                s.right,
                                now,
                                earAvailable(s, Ear.RIGHT),
                                Modifier.fillMaxWidth(),
                            )
                            BatteryCard(
                                "Case",
                                s.case,
                                now,
                                s.phone == PhoneConnection.CONNECTED,
                                Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            BatteryCard(
                                "Left",
                                s.left,
                                now,
                                earAvailable(s, Ear.LEFT),
                                Modifier.weight(1f).fillMaxHeight(),
                            )
                            BatteryCard(
                                "Right",
                                s.right,
                                now,
                                earAvailable(s, Ear.RIGHT),
                                Modifier.weight(1f).fillMaxHeight(),
                            )
                            BatteryCard(
                                "Case",
                                s.case,
                                now,
                                s.phone == PhoneConnection.CONNECTED,
                                Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                    if (s.connection == Connection.ERROR) {
                        Text(
                            s.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
            Column(Modifier.fillMaxSize()) {
                if (compact) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        summary()
                        ListeningControls(
                            s,
                            onAction,
                            Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                } else {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { summary() }
                    ListeningControls(
                        s,
                        onAction,
                        Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

private fun earAvailable(s: CompanionState, ear: Ear): Boolean =
    s.phone == PhoneConnection.CONNECTED && s.snapshot?.availability(ear) == Availability.AVAILABLE

@Composable
private fun ListeningControls(
    s: CompanionState,
    onAction: (DeviceAction) -> Unit,
    modifier: Modifier,
) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "LISTENING MODE",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeButton(
                        "Bluetooth",
                        s.snapshot?.mode == Mode.BLUETOOTH,
                        ModeCapability.VERIFIED && !s.busy && s.selectedName != null,
                        Modifier.weight(1f),
                    ) {
                        onAction(DeviceAction.SetMode(Mode.BLUETOOTH))
                    }
                    ModeButton(
                        "Sleep",
                        s.snapshot?.mode == Mode.SLEEP,
                        ModeCapability.VERIFIED && !s.busy && s.selectedName != null,
                        Modifier.weight(1f),
                    ) {
                        onAction(DeviceAction.SetMode(Mode.SLEEP))
                    }
                }
            }

            MediaVolumeControl(s, onAction)
        }
    }
}

@Composable
private fun ModeButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 64.dp).semantics { this.selected = selected },
        shape = RoundedCornerShape(if (selected) 20.dp else 32.dp),
        colors =
            ButtonDefaults.filledTonalButtonColors(
                containerColor =
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor =
                    if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                disabledContainerColor =
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .5f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContentColor =
                    if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = .7f)
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
            ),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun BatteryCard(
    label: String,
    reading: BatteryReading,
    now: Long,
    available: Boolean,
    modifier: Modifier,
) {
    Card(
        modifier =
            modifier.testTag("battery-${label.lowercase()}").semantics {
                if (!available) stateDescription = "Not connected or not reported"
            },
        shape = RoundedCornerShape(26.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .graphicsLayer { alpha = if (available) 1f else .38f }
                .padding(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Image(
                painterResource(
                    if (label == "Case") R.drawable.sleep_a20_case else R.drawable.sleep_a20_bud
                ),
                contentDescription = null,
                modifier =
                    Modifier.fillMaxWidth().height(80.dp).graphicsLayer {
                        scaleX = if (label == "Left") -1f else 1f
                    },
                contentScale = ContentScale.Fit,
            )
            Text(
                reading.percent?.let { "$it%" } ?: "—",
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                modifier =
                    Modifier.semantics {
                        contentDescription =
                            "$label battery ${reading.percent?.let { "$it percent" } ?: "unavailable"}"
                    },
            )
            Text(
                if (reading.percent == null) "—" else age(reading.receivedAtMs, now),
                modifier = Modifier.fillMaxWidth().testTag("battery-age-${label.lowercase()}"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 1,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun age(at: Long, now: Long): String {
    if (at <= 0) return "—"
    // A small clock correction or receipt just ahead of the sampled clock is fresh,
    // not an unknown timestamp. The original receipt remains stored unchanged.
    val elapsed = (now - at).coerceAtLeast(0)
    return when {
        elapsed < 60_000 -> "now"
        elapsed < 3_600_000 -> "${elapsed / 60_000}min ago"
        elapsed < 86_400_000 -> "${elapsed / 3_600_000}h ago"
        else -> "${elapsed / 86_400_000}d ago"
    }
}

@Composable
internal fun SettingsPage(
    s: CompanionState,
    onReady: (Boolean) -> Unit,
    onAutomation: (Boolean) -> Unit,
    onTheme: (ThemeChoice) -> Unit,
    onExport: () -> Unit,
    onBatterySettings: () -> Unit,
    onRepair: () -> Unit,
    onBack: () -> Unit,
    onSleepHistory: () -> Unit = {},
    onAddTile: () -> Unit = {},
    onAddBatteryWidget: () -> Unit = {},
    onAddControlsWidget: () -> Unit = {},
) {
    ScreenColumn {
        TextButton(onClick = onBack) { Text("‹ Back") }
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Section("APPEARANCE") {
            Text(
                "System colors, with a quieter dark theme.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ThemeChoice.entries.forEach { choice ->
                    FilterChip(
                        selected = s.theme == choice,
                        onClick = { onTheme(choice) },
                        label = {
                            Text(choice.name.lowercase().replaceFirstChar { it.uppercase() })
                        },
                    )
                }
            }
        }
        Section("BACKGROUND & SHORTCUTS") {
            SettingSwitch(
                "Ready mode",
                "Helps tiles and automations work with the app closed. Android watches for your paired earbuds; Companion stays idle in the background during Sleep. Optional for normal app use.",
                s.readyMode,
                onReady,
            )
            SettingSwitch(
                "External automation",
                "Let apps such as Tasker or MacroDroid switch modes or refresh status using intents. Leave off if you do not use automations.",
                s.automationEnabled,
                onAutomation,
            )
            Text(
                "No periodic checks with the screen off. Keep normal battery optimization enabled unless you encounter missed actions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onAddTile) { Text("Add Quick Settings tile") }
            TextButton(onClick = onBatterySettings) { Text("Battery optimization settings") }
        }
        Section("HOME SCREEN WIDGETS") {
            Text(
                "Saved battery readings on your home screen. The larger widget also refreshes status and switches modes.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onAddBatteryWidget) { Text("Add battery rings") }
            TextButton(onClick = onAddControlsWidget) { Text("Add batteries & modes") }
        }
        Section("PRIVATE BY DESIGN") {
            Text(
                "No internet permission, account, analytics or advertising. Device association and last reported readings stay on this phone.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onExport) { Text("Save local diagnostic report") }
            Text(
                "Reports omit names, addresses, serial numbers and raw packets.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalButton(onClick = onSleepHistory, modifier = Modifier.fillMaxWidth()) {
            Text("Sleep history · Preview")
        }
        OutlinedButton(onClick = onRepair, modifier = Modifier.fillMaxWidth()) {
            Text("Charging repair · Experimental")
        }
        Text(
            "A20 Companion ${BuildConfig.VERSION_NAME}\nIndependent software. Not affiliated with Soundcore or Anker.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    detail: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}
