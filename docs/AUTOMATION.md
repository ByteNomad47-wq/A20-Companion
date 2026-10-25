# Shortcuts, Quick Settings and automation

## App icon shortcuts

Long-press **A20 Companion** in your launcher and choose **Bluetooth mode** or
**Sleep mode**. Each requests that exact mode without opening the dashboard; it does
not toggle. Supported launchers also let you drag either shortcut onto the home screen.

Select your paired A20 from the home connection card and grant Nearby Devices access
first. Missing setup or permission shows a brief message and discards the request;
it does not open the dashboard, ask for permission in the background or save a command
for later. External automation and Ready mode can both stay off.

Android launcher shortcuts must target an activity. ModeShortcutActivity uses a
transparent window, has no content or recents entry, and uses a separate task affinity
so the launcher's shortcut task flags do not clear the dashboard task. After resuming,
it hands one fixed action to the private ActionService and finishes. The service owns
the fresh read, firmware check, single write and confirmed readback. Failures use the
existing quiet result notification. Android may briefly show its normal service indicator.

Only `.shortcut.BLUETOOTH` and `.shortcut.SLEEP` are accepted; addresses, raw packets
and mode extras are ignored. Recreation does not repeat a request. Older pinned entries
that still target MainActivity forward before building the dashboard. Launcher appearance,
no-flash behavior and background service exemptions need Samsung phone acceptance.
Other local apps can launch this activity like any launcher; the separate broadcast
automation API below remains opt-in.

## Quick Settings

Open **Settings → Background & shortcuts → Add Quick Settings tile** on Android
13+. On Android 12, edit Quick Settings manually and add **A20 Sleep mode**.
Each tap reads current mode, writes the opposite mode once and confirms readback.
Active means confirmed Sleep; inactive means Bluetooth or an unknown/stale state.
The subtitle distinguishes these. Bluetooth off makes the tile unavailable.
Long press opens Companion. Opening the panel does not query Bluetooth.
Repeated taps while an operation is busy are ignored.

Mode writes require the active original A6611 earbud and any linked peer to report 01.57.
No operation is queued for reconnect or blindly retried. A failed operation leaves
its result in the app/quiet notification. A mode action does not start audio,
change volume or alter Smart Switch.

## Explicit broadcast intents

Enable **External automation** in app Settings first; default is off. Any local
app able to send explicit broadcasts can invoke the fixed allowlist after this
opt-in. No address, raw packet, firmware or reset extra is accepted.

Package: `io.github.bytenomad47.a20`

Receiver: `io.github.bytenomad47.a20.automation.AutomationReceiver`

| Action | Extra | Behavior |
| --- | --- | --- |
| `io.github.bytenomad47.a20.action.SLEEP` | None | Set Sleep |
| `io.github.bytenomad47.a20.action.BLUETOOTH` | None | Set Bluetooth |
| `io.github.bytenomad47.a20.action.TOGGLE_MODE` | None | Toggle from fresh state |
| `io.github.bytenomad47.a20.action.SET_MODE` | String `mode`: `sleep` or `bluetooth` | Explicit set (compatible alternative) |
| `io.github.bytenomad47.a20.action.REFRESH_STATUS` | None | Read status |

For Tasker/MacroDroid use a **broadcast**, with the explicit package/receiver and
one action above. For example, via ADB:

```sh
adb shell am broadcast \
  -n io.github.bytenomad47.a20/.automation.AutomationReceiver \
  -a io.github.bytenomad47.a20.action.SLEEP

adb shell am broadcast \
  -n io.github.bytenomad47.a20/.automation.AutomationReceiver \
  -a io.github.bytenomad47.a20.action.BLUETOOTH
```

Broadcast delivery is not Bluetooth-operation success. Results appear in the app
and quiet notifications; there is no asynchronous machine-readable result API yet.
Android can reject background service startup. Associated ready mode supplies the
companion exemption; otherwise open the app and retry. Force-stopped apps must be
opened again. No overlay/accessibility workaround or deferred command queue exists.

Dispatch paths have automated coverage. Actual Samsung tile, lock-screen and
Tasker/MacroDroid acceptance is pending; foreground in-app switching is confirmed.
