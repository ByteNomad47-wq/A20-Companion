# Privacy

A20 Companion has no `INTERNET` permission. There is no account, analytics,
advertising SDK, crash-reporting SDK, cloud service, remote font or in-app updater.
Opening Android settings or sharing a document uses the Android system UI.

## Local data

The selected Bluetooth address/name, companion association, preferences and last
reported battery readings/timestamps are stored locally. Android cloud backup
and device-transfer backup are disabled. Forgetting a device clears this app's
normal control state and association; repair reports and reset request history are
retained so earlier outcomes remain visible. It does not unpair the hardware or touch Soundcore
or the repair app.

## Permissions

- **Nearby Devices / BLUETOOTH_CONNECT:** access an already-paired A20 and its
  secure control and diagnostic connections. No app-maintained Bluetooth discovery is used.
- **Modify audio settings:** adjust Android media volume and send play/pause media keys.
- **Notifications:** optional ready-mode status and quiet action results.
- **Foreground service / connectedDevice:** a Bluetooth action or an explicitly
  enabled background control session, or a bounded charging diagnosis/reset.
- **Companion foreground-start permission:** run when the user-associated A20
  appears, subject to Android's restrictions. Association requires system consent.

No location, microphone, storage, contacts, accessibility, overlay, exact-alarm,
or battery-optimization exemption permission is requested. System companion
setup may itself ask for Location Services to be enabled on some Android versions;
that does not grant this app location access.

## Reports

Normal control reports are exported only when requested to a destination you choose.
It contains parsed battery/mode/availability, receipt times, firmware labels and
frame-size information. Reports also include the last eight local
mode requests, their fixed result messages and reported outcomes. It omits names, serial numbers, Bluetooth addresses,
account data, raw packet payloads and exception messages. Nothing uploads itself.

Charging repair automatically saves up to forty bounded diagnostic JSON reports in
private app storage. Reports include parsed charging records and operation outcomes,
not device addresses/names, serial numbers, unfiltered replies or arbitrary socket
exception messages. A local hashed per-pair/per-ear journal records each unique
confirmed reset request before its clear byte and retains the latest attempt's
time and outcome. A new manual reset is allowed with new consent; the same request
cannot be replayed automatically. Old one-use and research-baseline records remain
private compatibility history. Reports are never uploaded automatically. Export is
optional under Advanced. Clearing app data deletes this local history. See
[repair](docs/REPAIR.md).

Other local apps can request the small documented automation allowlist only after
**External automation** is enabled. It is off initially. There is no repair or
arbitrary-command endpoint. Android system-bound tile/companion services require
the platform's binding permissions; the worker service is not exported.

Home screen widgets display the local cache and update from device/app events.
No periodic widget timer or Bluetooth poll is scheduled. Their user-initiated mode
and refresh actions target the private shared service using immutable explicit
PendingIntents. They do not enable the separate External automation interface.

## Find earbuds

A private receipt stores the selected device address and find target before a
confirmed sound request. It permits stop-only recovery after disconnection or
process loss and is cleared after an accepted cancellation or device-side stop
notification. It is not exported or uploaded. Starting sound requires in-app
confirmation; no external automation action starts it. The 30-second stop is
phone-controlled and cannot be guaranteed after connection loss or app termination.

## Playback status

While Home is visible in the foreground, the app keeps the Sleep control channel
attached to receive playback notifications, including gesture changes. Leaving
Home or backgrounding releases it. Playback-only events do not refresh battery
receipt times. There is no periodic Bluetooth playback poll in the background.
Bluetooth mode observes Android music activity using the foreground volume loop;
it does not request notification access or read another app's track metadata.
