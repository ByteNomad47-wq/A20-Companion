# Sleep history preview

Open **Settings → Sleep history · Preview → Sync sleep records** with the
original A6611 on firmware **01.57** connected. The first build imports stored
records; sleep-stage interpretation and nightly charts are not qualified yet.
Recording must already be enabled in Soundcore. Nothing runs automatically at
bedtime, changes the earbud clock, enables recording or erases earbud history.
Leave Soundcore closed while syncing so the apps do not compete for the channel.

The page shows per-ear event/summary counts and the last successful import time.
A linked peer may not respond; Sync details identifies which sources replied.
Missing records are not interpreted as zero sleep. An empty successful response
keeps previous imports. Unknown, incomplete or conflicting data does not replace
saved history. Imports are deduplicated by exact record content, kind and source,
with at most 3,000 unique records retained per selected pair.

## First device validation

1. After a night of use, note the corresponding sleep report in Soundcore.
2. Close Soundcore. In Companion, open Sleep history and tap Sync sleep records.
3. Expand Sync details and export a sleep report. It includes personal sleep
   records; inspect it before sharing. Device addresses, names and serials are
   excluded. Export happens only at your request using Android's file picker.
4. Compare against Soundcore. A second import should retain records without
   duplicating them. Check both single-ear and linked-ear operation.

Until timestamps, field meanings and source mapping have been compared on-device,
this preview deliberately shows no deep/light stages, night duration or sleep score.
Source header 0/1 is interpreted as left/right from the examined firmware; physical
confirmation remains pending. Do not treat this preview as sleep-stage analysis.

## Privacy and lifecycle

Records stay in private app storage under a hashed pair identifier. Forgetting a
pair does not erase its sleep archive; reconnecting the same pair restores it.
**Sync details → Delete local sleep history** deletes the selected pair's local
archive only. Clearing app storage removes all archives. Backgrounding/leaving
the page cancels its foreground sync. No sleep data is included in ordinary status
reports, widgets, telemetry or automation. Optional sleep exports contain opaque
6/14-byte records and import times; their internal timestamps are not yet labelled.

## Verified framing

The original 01.57 dispatch maps `[15,01]` to event getter `0x1412c65c` and
`[15,88]` to summary getter `0x1412c8a4`. Both queries have empty payloads. Responses
contain source, zero-based chunk index, chunk count, record count, then records.
Events use 6 bytes, up to 25 per chunk/100 per source. Summaries use 14 bytes,
up to 10 per chunk/50 per source. Empty history has a 4-byte header; an empty ACK
is unavailable data, not proof of an empty history. An entire source must arrive
before it can be saved. The collector accepts identical retransmissions and rejects
changed chunk counts, conflicting duplicates, wrong lengths and over-limit data.

The separate erase commands `[15,83]` and `[15,89]`, recording setter `[15,84]`,
and time-setting commands are not implemented here. Each getter requests peer
history through a distinct existing read-forwarding path. The phone sends no
extra TWS commands or acknowledgements that clear records.

Offline execution of the actual stock read callbacks passed 140 assertions across
20 source/count/boundary cases. Records remained unchanged in the emulator.
Logging, master selection, peer requests and response delivery were explicit
models: this does not verify radio timing, physical device behaviour or data
semantics. Live capture and comparison are required before full tracking UI.
