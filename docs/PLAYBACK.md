# Play / pause

On Home, an icon button sits **to the left of the volume slider**, on the same
row, below the Bluetooth/Sleep controls and volume heading. It has one symbol:

- **Playing:** two pause bars inside a soft square; tap to pause.
- **Paused:** a play triangle inside a circle; tap to play.

The 56dp button keeps its position and touch size while Material 3 Expressive
motion animates the corner radius. The icon follows reported state; tapping does
not optimistically flip it. Unknown Sleep state disables the control. Colors use
the phone's Material theme. [Native previews](../design/playback/README.md).

## Sleep sounds

For the original A6611 on examined firmware **01.57**, each press reads fresh
state, keeps the currently selected saved sound slot, and requests the opposite
of its reported playing flag. A previous tap gesture therefore cannot make the
next request use an outdated UI guess. No sound is downloaded, replaced or
selected by this feature. A valid saved slot and reported playback state are
required; pull down to refresh if the control is unavailable.

The app sends the request once, then checks fresh state for the desired playback
flag and unchanged selected slot. It does not repeat an ambiguous write. The
normal action lock prevents competition with mode changes, volume, Find or repair.
Official 01.57 is sufficient; no custom firmware or diagnostic service is involved.

While **Home is visible in the foreground**, Companion keeps the Sleep control
socket attached to receive playback notifications, including gesture changes.
Opening Home can perform one fresh status read to attach; it never starts audio.
The connection is released on leaving Home, backgrounding or disposing the UI.
No periodic Bluetooth query, screen-off polling or background Sleep connection
is added. A competing action still uses the shared action lock.

An exact successful `[13,03]` notification with an 11-byte body supplies the saved
slot at0 and playing flag at9. It is applied only to a known 01.57 Sleep snapshot
and an available saved slot. Empty ACKs, failed/unknown packets and the 12-byte
invalid-slot form are ignored. Playback-only updates never refresh battery ages,
change volume, or masquerade as a full status read. Reconnect/refresh remains
necessary if the control connection drops. A confirmed state is device evidence,
not independent acoustic measurement.

## Bluetooth media

In Bluetooth mode the same button sends one Android **MEDIA_PLAY_PAUSE** key-down
and key-up pair. Android routes it to its current/recent media player, as with a
headset media button. The A20 audio connection must be reported connected. A
player with no media session or one that ignores media keys may not respond.
The icon observes Android's `isMusicActive` during the foreground page visit,
using the existing 500ms media-volume observation loop. It changes when Android
reports music activity, not when the key is sent. This reports system media
audio activity rather than a specific player's complete media-session state.
Android chooses the media app and audio route; Companion does not select a player
or request notification access. No vendor playback command is sent in this mode.

## Evidence and validation

The pinned 01.57 dispatch table maps `[13,02]` to internal0x38, callback0x1412b298.
Its two payload bytes are **saved slot (0..3), desired playing flag (0/1)**.
The local routine0x14130344 requires Sleep mode and a valid saved slot. It applies
the requested flag, synchronizes the peer and emits the internal0x39 state update.
The setter may acknowledge an ignored non-master request, so an ACK is insufficient.
Normal 131-byte status reports the selected slot at79, playback at80 and the
available-slot count at81, with 10-byte records starting at82. Unknown values or
malformed slot lists stay unavailable.

[Offline reproduction](../research/emulate_sleep_playback.py) executes the actual
stock setter, local state transition and getter with **1,124 checks**; audio,
TWS and slot-loading helpers are explicit models. It uses only a separately
supplied pinned firmware image, never a phone/radio or firmware write.
[Numeric results](../research/sleep-playback-handler-validation.json).
Unit/UI tests cover fresh-state toggling, selected-slot preservation, rejection,
unknown state, missing slots, lost ACKs, no retries, and Android media key routing.
The owner reports 0.4.6 play/pause works. The 0.4.7 animated layout, gesture
notification sync and specific Android-player behavior still need phone acceptance.
