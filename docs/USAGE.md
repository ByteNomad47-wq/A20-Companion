# Using A20 Companion

## Connection and battery

Choose your already-paired original Sleep A20 from the Home connection card.
Grant Nearby Devices access; no Bluetooth discovery, account or root is needed.
Close Soundcore if it is holding the device's control connection.

The connection card opens the device picker. Its delete button forgets the device
in Companion, leaving Android Bluetooth pairing intact. Bluetooth settings can
also be opened from the picker. Repair history is retained separately.

Battery cards show the last reported percentage and update age. Unavailable buds
are dimmed while keeping their cached reading. The A20 reports percentages in
10% steps. The case level is relayed through an earbud, not read from a direct
case connection. Update times survive app restarts; a dash means none is recorded.
Pull down on Home to request a fresh reading. Releasing the control socket does
not by itself mean the earbuds disconnected from the phone.

## Listening controls

Choose Bluetooth or Sleep. Mode selection follows device readback. Supported
controls require firmware 01.57 on the active earbud and any linked peer.

The volume slider adjusts Android media volume in Bluetooth mode and saved-sound
volume in Sleep mode. Sleep volume is sent on release. Play/pause sits left of
the slider: a triangle in a circle when paused, pause bars in a soft square while
playing. Sleep requests preserve the selected saved sound and check fresh state.
Home listens for Sleep gesture notifications only while foreground-visible.
Bluetooth uses Android's media keys and reported music activity; behavior depends
on the media player. See [playback details](PLAYBACK.md).

## Find earbuds

Open Find earbuds from Home and select the left, right or both earbuds. Remove
them from everyone's ears before confirming the loud sound. Confirmation is
remembered per ear until you leave the page or background the app.

Stop all cancels both. Closing the case only pauses the sound, which can resume
when a bud is removed. Double/triple tapping either bud was observed to cancel
both. The phone requests cancellation after 30 seconds or when leaving the page,
but disconnection or process termination can prevent that request. Use the
explicit Stop control or the earbud gesture. See [Find behavior](FIND_EARBUDS.md).

## Shortcuts and background settings

- **App shortcuts:** long-press the app icon for Bluetooth or Sleep mode. Neither
  Ready mode nor External automation is needed for these shortcuts.
- **Quick Settings:** add the A20 Sleep mode tile from Settings. A tap toggles the
  freshly read mode; long-press opens the app.
- **Widgets:** add battery rings (3×1) or batteries and modes (5×2). They use cached
  readings, with user-initiated refresh and mode controls. See [widgets](WIDGETS.md).
- **External automation:** off by default. Enable it to let other apps on the
  phone use the fixed mode/refresh intent allowlist. See [automation](AUTOMATION.md).
- **Ready mode:** optional and off by default. Uses Android companion association
  to help actions run when the app is closed. It does not periodically poll with
  the screen off. The Sleep socket is released when Home is hidden. Keep normal
  battery optimization initially; overnight and locked-phone behavior still needs
  device-specific testing.

## Charging repair

Settings → Charging repair offers a separately confirmed, one-earbud-at-a-time
manual reset. A count check or custom-firmware baseline is not required for the
supported official-firmware reset. Each request gets a durable local receipt;
there are no automatic retries. Repeating a reset requires fresh consent, with
an additional acknowledgement for uncertain earlier outcomes.

“Reset accepted” means the device acknowledged the request; it does not prove a
zero main counter or battery health. Advanced contains optional count checking
(which requires the diagnostic firmware) and report export. No firmware installer
or external reset endpoint is included. Read [the procedure and limits](REPAIR.md)
before using it.

## Developer documentation

[Build and signing](BUILD.md) · [Architecture and power policy](ARCHITECTURE.md) ·
[Protocol](PROTOCOL.md) · [Tests](TESTING.md) · [Roadmap](ROADMAP.md)
