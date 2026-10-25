# Testing and acceptance

## Automated

Run `./gradlew :core:protocol:test :core:device:testDebugUnitTest :app:testDebugUnitTest :app:lintRelease`
and `python3 scripts/check_repository.py`. The protocol suite exercises all split
points, bytewise fragmentation, combined frames, every one-byte corruption,
length limits, all battery/host/mode byte values and malformed automation actions.
The repository integration test covers immediate state updates, missing-value cache
retention, sanitized export and unsupported-firmware refusal. Coordinator tests cover gating, fresh state, idempotence, one write, failed
readback, lost acknowledgement, deadline, concurrency, cancellation and stale cache.
Compose UI tests run under Robolectric with native graphics on API35, exercising
missing/known values, selected-mode semantics, pull-to-refresh, automation opt-in, the guided experimental
repair flow and empty-dashboard rendering. Robolectric screenshot outputs are
development aids; native text rendering can be incomplete and they are not phone
visual acceptance evidence.
The automated tests include the fixed 01.57 mode frames, positive/negative
acknowledgement parsing, qualification firmware/peer checks, delayed transitions,
readback deadlines without replay, explicit UI mode requests and sanitized
two-direction result records, fixed Sleep-volume frames/ranges, single-bud write
guards, volume readback/refusal, real tile/receiver dispatch, dimmed-peer semantics,
Expressive picker confirmation and slider behavior. Simulated mode writes are test doubles only; they
cannot send Bluetooth packets. The optional stock-handler emulation passes 64
checks with explicit hardware/transition models; see [research](../research/README.md).

Release verification checks manifest privacy/exports, bundled licenses, APK identity
and checksums. The API37 software emulator accepted installation, but its launch timed out;
interactive emulator behavior is not validated. UI tests use API35 Robolectric,
not physical Bluetooth or power use. Exact completed checks and pending
items are recorded in the protocol research validation records.

## Observed hardware

The owner confirmed four Sleep/Bluetooth round trips on both A6611/01.57 earbuds,
including reconnecting after the Sleep socket is released. See aggregate evidence
in `research/mode-control-device-validation.json`. This is one pair's foreground
switching evidence, not background execution or battery-drain validation.

## Remaining original-A6611 tests

- Both buds, left alone, right alone; case open and closed; primary-ear changes.
- Battery readings against the official app at the same time, with last-received
  labels distinguished from actual measurement freshness.
- Normal and diagnostic 1.57 compatibility; do not reflash working hardware merely
  to fill this matrix. Record untested firmware combinations honestly.
- One bud at a time: mode changes and primary-side mapping; Sleep volume audible level, persistence, physical keys and adaptive-volume interaction.
- Regress both mode directions with new app versions; other firmware needs qualification.
- Tile with app closed/phone locked; explicit intents with opt-in on/off and ready
  mode on/off; no queued execution after earbuds reconnect.
- Bluetooth off/on, range loss, process death, reboot, permission revocation,
  denied association and Android force-stop.
- Vendor app closed/absent, and a useful failure if another client holds the service.
- TalkBack, 200% font size, light/dark and rotation.

## Power and responsiveness

Target cached dashboard <1 second on a normal phone; already-connected actions
<3 seconds; cold actions bounded to 10 seconds. Emulator software-rendering times
are not phone benchmarks.

Compare ready mode off/on across matched eight-hour idle periods (repeat to reduce
noise). Record phone total drain and attributed app use, and both earbuds' charge
loss in Bluetooth/Sleep modes. Target <=1 additional phone-battery percentage point
across eight hours, not a guarantee. No periodic app Bluetooth traffic or held wake
lock is permitted during screen-off idle. Investigate any earbud-drain regression
before calling ready mode stable. Battery optimization stays enabled initially.

## Owner check for 0.2.0

1. Refresh with both earbuds connected. Confirm the images, battery ages and
   bottom control panel; pull-to-refresh replaces the old button.
2. In Bluetooth mode with normal phone audio, move Media volume and press the
   phone volume keys. Both should update the same Android media stream.
3. In Sleep mode with an existing saved sound playing, move Sleep sound volume
   and release. Confirm the audible change and the next reported slider position.
   Companion does not start a sound or change the selected sound. Export the local
   report if the volume is refused or remains unconfirmed.
4. Test one earbud at a time, leaving the other in the case. Refresh, check that
   only the absent side is dimmed, and try both mode directions. Repeat for the
   other ear. This is the first physical acceptance of these single-bud paths.
5. Add the tile in Settings and try two taps. Separately enable External automation
   if wanted and test the explicit Sleep/Bluetooth broadcasts. Ready mode is optional;
   test closed-app execution with it off/on and retain normal battery optimization.

## 0.3.0 widget and slider coverage

Native RemoteViews rendering verifies fixed 3×1 and 5×2 layouts and generates
synthetic previews. Tests cover missing/cached values, immutable distinct service
PendingIntents, busy/permission/setup states, the IPC payload limit, and cache-only
widget creation/host callbacks with a gateway that fails any Bluetooth request.
A drag regression keeps a finger down past the media-volume refresh interval and
checks that the Bluetooth thumb retains a fractional position without snapping.

On the Samsung, add both widgets, check the fixed spans and tap the large widget's
refresh/Bluetooth/Sleep actions with Companion closed. Confirm device state before
and after a process restart. Pixel density, launcher padding and actual background
execution need this physical acceptance; synthetic previews do not establish it.

## Owner check for 0.3.1

1. Long-press the app icon: Bluetooth mode and Sleep mode should both appear.
2. With Ready mode and External automation off, try each shortcut from a closed
   app and with Companion already open. Confirm actual mode changes in both directions.
3. Rotate or reopen the app after a shortcut. It should refresh status without
   issuing the previous mode request again. Missing setup/permission should show
   instructions without saving a mode action for later.
4. Open Companion by tapping a battery widget. Return Home and check that One UI
   animates back toward that widget. Try both compact and large widgets, from a
   fresh launch and with an existing task. These transitions require launcher
   support and cannot be verified by Robolectric.

Automated coverage checks the packaged shortcut metadata, both cold mode launches,
new-intent handling, recreation without replay, missing setup/permission and unknown
actions. Widget tests check the platform background marker and normal launcher task
flags on actual inflated RemoteViews click actions. Bluetooth actions use fake gateways.

## Owner check for 0.3.2

1. Tap each app-icon shortcut from Home, with Companion closed and already open.
   The dashboard should stay closed (or remain in its previous task), with no new
   recents entry. Confirm both actual mode changes, with Ready/automation off.
2. Try a pinned shortcut made before this update as well as a newly pinned one.
   Check for any launcher flash or blocked background-service notification on One UI.
3. Tap the home **[device name] connected** card. Confirm that Choose your A20 opens,
   the selected device has a delete icon and Bluetooth settings is opposite Cancel.
4. Delete the saved device and confirm. Companion should clear its selection/cache;
   Android should retain the Bluetooth pairing. Re-select the pair from the home card.
5. Repeat opening the picker with Bluetooth off and after forgetting the saved device.
   It should still offer Bluetooth settings. Settings should start with Appearance.

Automated shortcut tests cover actual private-service dispatch without starting the
dashboard, no replay on recreation/resume, setup/permission failure, unknown actions,
legacy pinned-intent forwarding and service completion after activity destruction.
UI tests cover the connection-card callback, removal from Settings and the picker’s
forget confirmation and opposite bottom actions with an empty paired-device list.

## Owner check for 0.3.3

Open the home device picker. Check the wider compact dialog, left-aligned device
name/Selected text, delete action inside the selected row, and aligned Bluetooth
settings/Cancel footer. Long names should wrap within the row. The list scrolls
when space is limited. The native-rendered preview uses synthetic data; it is not
a Samsung screenshot. Existing picker tests check text-edge alignment, separation
from delete, footer alignment, and the same forget/settings actions.

## Owner check for 0.3.5

Refresh: each reported battery should show **now** immediately, without alternating
to unknown. With no new report, ages advance to **1min ago**, **1h ago** and **1d ago**.
The existing foreground status refresh can keep connected readings current.

Close the app, disconnect the earbuds, then reopen: cached percentages should keep
their original receipt ages. A missing peer/case in a later response must retain its
earlier value and time. **—** means no update timestamp has been recorded. The cards
should keep their height throughout.

Regression tests cover a receipt ahead of the previous display tick, minute/hour/day
labels, stable card heights including missing timestamps, and independent battery
value/time restoration after repository recreation without another gateway read.

## Charging repair

The reused generic 1.15 suites test malformed/fragmented streams, complete history,
translated generic fixtures, strict eligibility, changed records/ear, durable-claim
failure, cancellation, lost/wrong acknowledgements and ineffective clears.
Integration checks cover timestamp expiry, process recreation, previous resets,
atomic-save failure, normal-control contention and unsupported stock replies.
See [repair](REPAIR.md). Physical native-app acceptance is read-only on the already
reset owner pair; no second reset should be used as a test.

The direct reset entry is also tested end-to-end with fake links: a read-only
service handoff, confirmation only on eligible completion, no automatic clear,
Cancel, ordinary checks without confirmation, and an already-reset ear with a
main counter of one. Numerical reset eligibility and the durable one-use ledger
remain unchanged.

## Playback follow-up (0.4.6)

Sleep playback uses the selected saved slot and a fresh playing flag, then requires
readback; tests cover gesture changes between presses, no-effect ACKs, refusal and
no automatic retry. Repository tests include repeat use after releasing the idle
Sleep connection. Native UI tests assert the control sits above the slider and
verify the Bluetooth path emits balanced Android media keys without a Sleep command.
Unknown playback/slot state disables Sleep control. No background polling was added.
[1,124 stock firmware checks](../research/sleep-playback-handler-validation.json)
run offline with audio/TWS/slot-loading helpers modeled. Physical audible Sleep
play/pause and individual Android media-player behavior still need phone testing.

## Stateful playback control (0.4.7)

The icon is left of the slider, with circle/play when paused and soft-square/pause
when playing. Native tests check matching centers and unchanged bounds across
states, both themes, no optimistic icon flip, and Android media-activity updates.
Lifecycle tests limit notification attachment to foreground Home. Strict `[13,03]`
notification tests reject ACKs, invalid lengths, failed packets, unknown flags and
unsupported snapshots. Repository tests verify gesture updates do not change
battery readings or receipt timestamps, leaving Home releases the Sleep socket,
and re-entry attaches with one read and no audio request. Returning Home while
Find is still stopping preserves the Sleep notification channel after its ACK. The narrower volume
slider still sends exactly once on release. Physical gesture/UI sync remains
pending; the owner reported 0.4.6 playback itself works.
