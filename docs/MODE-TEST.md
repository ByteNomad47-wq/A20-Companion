> Historical 0.1.2 instructions. Qualification succeeded on the owned pair.
> Use the current [0.2.0 preview](../README.md) for guarded UI, tile and intent controls.
> Statements below about ordinary releases describe the historical 0.1.x builds.

# A6611 mode-control qualification build

This is a separate **build type** of the same app, labelled **Mode control test**.
It installs as an update, keeps the same app ID/signer and includes the layout
fixes. The ordinary release build remains read-only. This is not a firmware update.

The historical mode-test APK is not included in this clean source export. It targets the original **A6611** with both
buds reporting **01.57**. The version-label check is not cryptographic hardware or
firmware attestation; select only the owned original model.

1. Close Soundcore after confirming that its own mode selector works. Keep both
   earbuds out of the case, awake and connected to the phone.
2. Install this APK over Companion. Keep Ready mode off for the first test.
3. Refresh status. Start with actual Bluetooth mode, then tap **Sleep** once and
   wait for the result. Check whether audio/mode behavior really changed.
4. If Sleep was confirmed, tap **Bluetooth** once and wait. Check the real behavior
   again. If either action fails, save the report at that point.
5. In Settings, choose **Save local diagnostic report**. The report retains both
   requests/results for this process, including whether the result was an actual
   confirmed transition or “already reports” the requested mode.

The normal tile still refreshes status, and automation mode requests remain
blocked. Only explicit foreground UI mode actions are enabled in this build.
No reset, charging-limit, calibration, firmware or arbitrary-command endpoint is
added. It never starts media or changes phone volume.

## Implementation and interpretation

The exact firmware mapping and offline evidence are in [research](../research/README.md).
The operation reads fresh state, checks both 01.57 labels/peer, avoids a write if
already in the desired mode, then sends exactly one `[01,A9]` packet with `00`
(Bluetooth) or `01` (Sleep). It uses the live socket from that pre-read.

An empty success acknowledgement is not sufficient proof: stock firmware can
acknowledge without switching when this phone is not its master. A negative
acknowledgement is a refusal. A missing acknowledgement is ambiguous; status is
read back. The coordinator allows up to six status reads, 500 ms apart, within
the shared ten-second action deadline. It never retries a mode write. If the
outcome is unconfirmed, refresh before deciding whether to try again.

The firmware may defer an accepted transition briefly. Once a request has reached
the earbuds, cancelling the phone operation cannot undo that request. This app
never queues or replays it on reconnection. It closes its socket while idle in
Sleep. Both direction tests and Sleep reconnection must pass before normal
release, Quick Settings toggles and automation mode controls are qualified.
