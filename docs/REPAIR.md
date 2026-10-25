# Charging repair — direct manual reset

For the original **Sleep A20 / A6611**, examined firmware **01.57**, with either
the official image or our compact diagnostic variant. The main action works
without reading the fault count, importing a baseline or installing custom firmware.
The firmware label alone does not establish compatibility with other builds/models.

## Reset one earbud

1. Open **Settings → Charging repair** and select **Left** or **Right**.
2. Take that earbud out. Leave the other in the closed case, and connect the
   selected earbud to your phone. Close Soundcore if it is holding the connection.
3. Tap **Reset left/right earbud**. This opens a confirmation; it sends no commands.
4. Read the explanation and last attempt's time/outcome, if available. Confirm
   the physical model, acknowledge the record erasure and tap **Reset left/right**.
5. **Reset accepted** means the earbud sent the exact acknowledgement. It does
   not claim the main count is zero, permanent storage was updated, the cell is
   healthy, or charging was repaired. Charge normally under supervision and
   check the behavior. Stop for unusual heat or damage.

A reset erases both saved charging records, including the fault history, and can
restore the higher normal charging target if the reduced-target condition was
active. It does not change future fault detection or repair battery damage.
A count of **1 or 2** is below the examined greater-than-two restriction threshold;
a returning nonzero count alone is not a reason to keep clearing records.

## Multiple resets: fresh decisions, no lifetime lock

Another **manual** reset is allowed with a new confirmation, including on an ear
reset with previous Morphe/Companion versions. There is no arbitrary cooldown,
fault-count prerequisite or lifetime attempt cap. Old history is preserved.

Each confirmation creates a unique request tied to the pair, selected ear and
previous outcome. It expires after ten minutes, device/ear/history changes or
process loss. Cancel sends nothing. Overlapping actions are blocked. A durable
receipt is committed before the only clear command; the same request cannot be
replayed, and a timeout, service restart or repeated intent never retries it.

If an earlier attempt has an **unknown** outcome, it may already have worked.
The next confirmation requires a separate acknowledgement of this uncertainty
before the user can deliberately start a new request. Opening the app never
resumes a reset. The previous attempt's actual time is retained for new requests;
older versions without a timestamp show **time unavailable**, not an invented date.
Forgetting a device retains receipts and reports. Clearing app data deletes them.

## Hidden diagnostics and export

Expand **Advanced** to reveal **Check left/right fault count**, **Export latest
report** and technical details. The section starts collapsed. Checks only read;
they never open a reset confirmation or send a reset.

A complete main-count read requires our compact diagnostic firmware. Official
01.57 exposes no qualified complete main-count reader here; the UI says
**Count unavailable**, not zero. The app never falls back to the unsafe selector1
formatter or test selectors5/6. Companion contains no firmware installer.

Reports save privately and automatically; export is optional. Up to forty bounded
reports are kept. They include operation receipts, parsed numeric records,
acknowledgements and verification limits, without names, serials, Bluetooth
addresses or unfiltered socket errors. A record copy cannot restore erased history.

## Device checks and wire behavior

Before either operation, the private worker uses the normal action mutex and
checks the active ear, absence of a linked peer, and active firmware **01.57**.
It releases the normal socket, opens secure SDP-discovered SPP UUID
`00001101-0000-1000-8000-00805f9b34fb`, and enforces a 40-second overall deadline
with socket-close cancellation. No insecure/channel fallback or background reset.

Direct reset uses bounded **4 → 2 → 3 → 2** only: cached voltage/ear identity,
a valid unlocked timing record, the existing one-shot clear, then separate timing
readback. The examined cached-voltage guard remains **3400–4200**, interpreted mV;
it is a qualification range, not a battery-health diagnosis. It does not read the
main count (selector7). The acknowledgement must exactly match
`EARSIDE {L|R}CLEAR_CHARGE_INF`. Zero timing data never substitutes for a main count.
A follow-up failure preserves acknowledgement evidence and does not resend.

Advanced inspection uses the existing bounded **4 → 7 → 2** complete-history
reader. The UI no longer uses three-fault eligibility or old lifetime claims to
permit or deny a direct manual reset. Those old parsers/validators and immutable
release archives remain available for research and regression tests.

## Evidence and limits

On the owner's original pair, the 1.17 Morphe/custom test read **left 1 → 0**
immediately, and zero remained after a case cycle. Companion 0.4.2 captured right
**2**, then received the existing clear acknowledgement on official 01.57 with
immediate zero timing readback. After diagnostic reinstall, the right main count
and all records were **0**, and remained zero after a case cycle.

Reflashing intervened before the right main readback. A case cycle is not proof
of complete power loss. These results support the existing reset on the examined
firmware; they do not prove every repeated reset is safe, all cells are healthy,
or all firmware builds behave identically. The routine's immediate NV writes
appear to be no-ops; a later normal store may persist the cleared RAM records.

The simplified 0.4.3 flow reuses that clear command, but does not require the
research baseline/stock-marker exchange. Its new UI, per-request journal and
no-main-read sequence have offline/controller/UI validation, with fresh physical
acceptance pending. No automated test connects to earbuds.

Reset is never exposed through intents, widgets, shortcuts, tiles or automation.
The foreground worker is private and consumes UI-staged requests only.
