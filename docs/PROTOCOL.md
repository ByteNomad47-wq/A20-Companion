# A6611 protocol and qualification

## Evidence

Primary public reference, pinned revision:
[OpenSCQ30 A6611 fixture](https://github.com/Oppzippy/OpenSCQ30/blob/200d9e1657dff67d516ed1b6347f312d7a1b01a7/tools/soundcore-device-faker/devices/a6611.toml).
Related firsthand reports: [device-support issue 165](https://github.com/Oppzippy/OpenSCQ30/issues/165).
Those reports include different firmware/layouts; they are not universal format guarantees.

Previous research examined the exact original 1.57 binary (SHA-256
`20f5f148be6f346677422679e78a251219a72ac9501ad959a01783fa8439df9f`).
The separate repair repository records its provenance. It is not distributed here.
Read-only Ghidra inspection identified the internal mode transition routine at
`0x141309f4` and a Sleep-mode A2DP-disconnection path at `0x1412d85c`.
That confirms why audio-profile state must be separated from control availability.
The subsequent [exact command investigation](../research/README.md) identified
`[01,A9]` with one payload byte `00/01`. The earlier internal-routine evidence
alone did not establish that packet. No proprietary
pseudocode or personal capture is committed.

## First physical observation (2026-10-23)

The owned original A6611, reporting **01.57 on both earbuds**, successfully
answered the preview's normal-channel status request with a **131-byte body**.
The parsed reading was left/right/case **90/90/100%**, primary RIGHT and peer
linked. The owner also reports that the displayed mode tracks the actual mode.
This establishes a successful read on this pair. It does not yet validate battery
accuracy against another measurement, primary-ear mapping in single-bud tests,
overnight power use. Subsequent testing qualified both mode directions and Sleep reconnection on this pair (below). No personal report or
raw Bluetooth capture is committed.

## Transport and framing

Normal secure RFCOMM service:
`0cf12d31-fac3-4553-bd80-d6832e7b6611`.
Guided [charging repair](REPAIR.md) uses a separate, exclusive standard-SPP session.

The known framing uses five direction bytes, two command bytes, a two-byte
little-endian total length, body, then an unsigned byte-sum checksum.
Outbound marker: `08 ee 00 00 00`; inbound: `09 ff 00 00 01`.
Frames are bounded to 4096 bytes. The decoder handles fragmented/coalesced frames
and rejects invalid headers, lengths, checksums and truncated streams.

**Status command:** empty-body request `[01,01]`:
`08 ee 00 00 00 01 01 0a 00 02`.
This normal transport has no diagnostic ASCII command, arbitrary packet sender,
firmware write or repair endpoint. The separate private guided repair uses SPP. The additional setting writers are `[01,A9]` for mode and `[13,05]` for Sleep sound volume.

## Known reference body

The pinned fixture has **131 body bytes**. Byte offsets below are body offsets:

| Offset | Meaning / interpretation |
| --- | --- |
| 0 | Primary ear, using common host convention 0=left, 1=right; device check pending |
| 1 | Peer link flag, 0/1; unknown values stay unknown |
| 2, 3 | Left/right battery, known 0..9 → 10..100%; other values unavailable |
| 4..8, 9..13 | Five ASCII firmware-label bytes per side |
| 14..29 | Serial-bearing region; never exposed or stored |
| 65 | Mode flag, reference 0=Bluetooth / 1=Sleep; observed on the owned pair |
| 78 | Effective Sleep sound volume, raw 0..65; other values unavailable |
| 126 | Case battery in this exact layout |

A valid response is still a reported value, not proof of a live physical case
measurement. A missing peer battery does not establish that peer's connection.
Unknown layouts must not be decoded using guessed tail offsets. The preview
records received body length so a failing format can be investigated safely.
Only recognized state responses update the dashboard; unrelated packets are ignored.

## Mode-control qualification (0.2.0)

The exact 01.57 firmware mapping, dispatch registry and stock handler identify
`[01,A9]`, one payload byte: `00` Bluetooth, `01` Sleep. See
[research](../research/README.md) for addresses, acknowledgement semantics and
64 offline stock-handler checks. Emulation models hardware effects explicitly;
it does not establish radio or physical switching behavior.

The owner's report then recorded **four confirmed Sleep transitions and four
confirmed Bluetooth transitions**, all successful readbacks. The owner confirmed
the actual switching worked. Idle Sleep sockets were released, so subsequent
Bluetooth operations also exercised reconnection on this pair. The sanitized
[aggregate evidence](../research/mode-control-device-validation.json) contains no
personal identifiers or raw packets.

`ModeCapability.VERIFIED=true` now enables UI, tile and opt-in automation. Every
write still requires a fresh recognized 131-byte status reply, linked peer, known
primary and **01.57 on the active earbud and any linked peer**. An absent peer
need not supply a firmware label. These checks are compatibility guards, not
cryptographic device attestation. Other firmware and unknown host/peer state are
refused. Single-bud switching is enabled for owner testing; both primary-side
mappings still need physical confirmation.

A set is idempotent if the fresh mode already matches. Otherwise it transmits
one setter and checks readback (up to six reads, 500 ms apart within the ten-second
action deadline). A success ACK alone is insufficient: stock firmware can acknowledge
without applying. An explicit refusal fails; an ambiguous/lost ACK allows readback,
never a second write. Failed actions are not queued for reconnection.

This qualifies the owned pair's foreground switching. Quick Settings/background
intents, primary mapping with one bud and overnight power remain physical test work.

## Sleep sound volume

The exact firmware maps `[13,05]` to internal index `0x3B`, registered setter
`0x1412A374` (pointer at `0x1412C1D8`). Its one-byte payload invokes the local
music-volume routine `0x14130484` with manual-save flag 1. That routine rejects
values above **65** and updates both effective and stored local sound-volume
fields. The normal status handler `0x14129A1C` returns the effective field at
**body offset 78**. Unknown/out-of-range readings stay unavailable.

The callback is labelled prompt volume internally, but its called routine is
local music volume, also used by the Sleep-mode volume-up/down gesture path.
This evidence distinguishes it from `[01,B1]`, which changes adaptive volume;
Companion does not send that adaptive-setting command or change sound selection.

[Offline reproduction](../research/emulate_sleep_volume.py) executes the stock
setter and range check for every value 0..65, invalid boundary values and a
non-master case: [554 checks](../research/sleep-volume-handler-validation.json).
Audio application, peer sync and usage counters are explicitly modelled. This
establishes packet/range/state semantics, not audible physical behavior.

The preview sends this setting only in Sleep mode, on the same guarded live socket
as a fresh pre-read, once on slider release. It checks the effective volume in
readback and never blindly retries. Adaptive volume or another client can alter
the effective value; failure to match is reported as unconfirmed. Phone testing
must confirm both audible level and setting persistence. Android media volume
uses `AudioManager.STREAM_MUSIC` in Bluetooth mode and obeys system volume limits.

## Battery precision

The identified normal state encodes each battery with ten valid values. These
map to 10% increments, not a raw 0..100 measurement. Firmware/normal-channel
inspection has not established a finer battery API. Voltage diagnostics do not
supply a calibrated percentage; interpolation would invent precision.

## Sleep playback

Normal command `[13,02]` requests the selected saved slot and an absolute playback
flag, through internal0x38 / callback0x1412b298. The fresh normal state supplies
slot at79, playing at80 and valid-slot records at81/82. Companion computes a
toggle from that fresh state, sends once and confirms both slot and playback
afterward. [Full evidence and behavior](PLAYBACK.md). Bluetooth mode instead uses
Android media keys; it does not send this Sleep command.

Sleep playback notifications use `[13,03]` / internal0x39. For a valid selected
saved slot, the examined stock getter returns 11 bytes: slot at0, playback flag
at9. Companion handles only this exact successful form against a known Sleep
snapshot. The 12-byte invalid-slot response and empty ACKs are ignored. The event
updates playback fields separately from full battery-status receipt timestamps.
Home retains the channel only while foreground-visible; other Sleep operations
continue to release it afterward.
