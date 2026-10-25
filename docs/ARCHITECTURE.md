# Architecture and power policy

Three modules: pure Kotlin `core:protocol`, Android `core:device`, Compose `app`.
A process-wide repository owns the settings, independently dated battery cache,
status StateFlow, one secure transport and one action coordinator.

UI, Quick Settings and external intents converge on the same coordinator.
It rejects overlapping actions rather than queueing them, applies a ten-second
deadline, and never replays a request after reconnection. Blocking Bluetooth
connect/read calls run off the main thread; cancellation closes the owned socket.
A changed selected pair clears snapshots and stops the old connection/association.

The dashboard renders the cache immediately. Foreground refresh is at most once
per minute; a 15-second foreground-only UI timer updates age labels without any
Bluetooth queries. The visible media-volume slider samples Android's local media
stream twice per second to follow physical volume keys; this also stops with the
foreground lifecycle and performs no Bluetooth query. No background refresh timer, WorkManager task or alarm exists.
Packet events can update an existing live connection. Android audio-profile state
is observed separately from reported host/peer link state.

Ready mode starts off. Enabling it requires Nearby Devices, notification access
and system companion association. Android 36+ uses the association-ID presence
API; Android 31–35 uses the legacy address API. Device appearance can initiate
one bounded status read. No continuous scan or unlimited retry loop is used.

A `connectedDevice` foreground service owns an active background operation.
After a read, it retains a live session only when ready mode is enabled, the
association exists and the device reports Bluetooth mode. Sleep/unknown mode
releases the socket and stops the foreground worker. The saved ready preference
and system association allow a later explicit action to reconnect.

Bluetooth off, disappearance, revoked access, cancellation and service shutdown
close resources. Android force-stop is respected; there is no attempt to escape it.
If Android denies a background start, a quiet actionable result is shown rather
than storing a command for later. Battery exemption is never requested automatically.

Phone and earbud overnight impact remain unmeasured. See the physical checklist.
No service-lifecycle or emulator test can establish a battery-drain guarantee.

Phone connection uses observed Bluetooth ACL connection/disconnection events,
Android audio connection and successful control reads. Closing our own socket
never alone marks the phone disconnected. An unobserved initial state stays unknown.
Battery cards dim for unreported/absent earbuds or phone disconnection, retaining
cached percentages and receipt times. The case remains a relayed reading.

Two fixed-size RemoteViews widget providers render the shared repository cache.
Their updatePeriodMillis is zero: creation, host callbacks, configuration/time-zone
changes and state-driven repainting never query Bluetooth. A 200 ms debounce merges
bursts of repository changes. Ring bitmaps keep the IPC payload bounded.

Widget user actions use explicit immutable PendingIntents directly to the private
action service, sharing the existing coordinator and fresh read/write/readback path.
They do not depend on External automation or Ready mode being enabled. Missing
setup/permission routes the user to the app. Widgets display cached reports, not
an independently polled live connection.

## Launcher entry points

Two static launcher shortcuts target a transparent, excluded-from-recents activity
with a separate task affinity. It consumes one fixed mode action on resume, waits for
local settings and starts the private shared action service. It finishes without
building the dashboard; the service owns read/write/readback independently of activity
lifetime. Setup/permission failure discards the request with guidance. Activity recreation
never replays it. Legacy pinned MainActivity intents forward before UI creation.
These user-facing entry points are independent of the opt-in broadcast API. No deferred
queue, additional polling or exported service is added.

Device management lives on the home connection card. Its Material 3 picker shows the
saved device with a local forget action, other paired devices, and bottom Bluetooth
settings/Cancel actions. The picker is usable with Bluetooth off or no paired devices;
Nearby Devices permission is still needed to enumerate Android's paired-device list.

## Battery receipt ages

Each battery value and its phone-clock receipt timestamp are persisted together in
DataStore. Repository initialization restores all three independently. A missing
value in a subsequent status packet keeps the earlier value and its earlier time;
restoring a cache does not query Bluetooth or make an old value fresh.

The dashboard samples current wall time on each composition and keeps its existing
15-second foreground-only clock tick for advancing ages. The old display used only
that tick; a new receipt between ticks appeared to be in the future and was labeled
unknown until the next tick. Fresh receipt rendering no longer waits for that tick.
Small future skew clamps the displayed age to now without modifying the stored time.

Ages use now, Nmin ago, Nh ago and Nd ago. No recorded timestamp is an em dash.
One fixed text line prevents age transitions from resizing the battery cards.

## Charging repair

The private RepairService uses a separate secure diagnostic transport while holding
the normal repository action mutex. The normal socket closes before SPP. No normal
action is queued for execution after repair. The process-wide repair controller and
its in-memory preparation survive rotation but not process death. The per-ear
claim and diagnostic reports are durable and separate from ordinary device settings.
See [repair](REPAIR.md) for exact eligibility, bounds, acknowledgement and readback.
