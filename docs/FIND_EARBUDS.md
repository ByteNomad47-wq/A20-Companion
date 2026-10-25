# Find earbuds — 0.4.6 preview

Open **Find earbuds** beside Settings on Home. Matching left/right cards show
an earbud image and a large Play/Stop control. **Play both** starts both together;
**Stop all** always cancels both, including when pressing Stop on an earbud card.
Unavailable earbuds are dimmed and cannot start. The case itself cannot beep.
Below the controls, only the connection prompt remains when disconnected.
Loud-sound confirmation still explains the case/disconnection behavior; start
failures appear in a dialog rather than as persistent small text.

The loud-sound dialog has **Cancel** and **Play sound**, without a checkbox.
Confirm only after the selected earbuds are out of everyone's ears. Confirmation
is remembered **per ear for this page visit**: stop/replay of a confirmed ear
needs no repeat dialog. First use of the other ear still asks; Play both confirms
both. Leaving the page, backgrounding or recreating the app clears this consent.
It is never stored on disk. Returning requires confirmation again.

Requires the original A6611, known 01.57 firmware on the connected ear and linked
peer, and a reachable control connection. **Bluetooth and Sleep modes are both
allowed**, using the same find command without switching the listening mode.
A released idle control socket does not disable replay; each start reconnects
if necessary, reads fresh state and rechecks firmware and selected-side availability.

## Sound and stopping

- The phone requests cancellation after **30 seconds**, when leaving Find, or
  when the app enters the background. This is **not an earbud-side cutoff**.
- **Stop all** cancels both find flags, even when only one target was selected.
- The owner observed that putting a bud in the closed case silences it, but the
  sound resumes on removal. Closing the case is a pause, **not cancellation**.
- The owner observed that double/triple tapping either bud cancels both and
  updates the official app. Firmware tracing confirms global flag clearing and
  the stop notification; Companion consumes that notification to clear its UI.
- Bluetooth loss or process termination can prevent the phone's stop request.
  Sound may continue or resume on removal from the case. Do not wear a sounding
  earbud. Reconnect and press Stop, or use the observed earbud gesture to cancel.

The UI says **Sound requested**, not acoustically verified playback. A stop ACK
means **Stop request accepted**, not proof of silence. Missing ACKs are uncertain;
start is never retried. An uncertain session retains Stop. Reconnection only
requests cancellation; it never resumes sound automatically.

## State, lifecycle and exclusions

A private receipt stores the target and selected device before start. It survives
process loss and is never exported. Recovery is bound to that device. Starting
another target requires stopping the current session first. Unconfirmed ears
still require the dialog; previously confirmed ears can replay during this visit.
Normal mode/volume/repair operations cannot compete with an active find session.
Stop uses the owned channel and is not queued behind ordinary actions. A lost
connection releases the ordinary lock for reconnect/refresh, but other writes
remain blocked until cancellation. Receipt recovery never starts a sound.

The existing ACL-connected event triggers a stop for an unresolved session on the
selected A20. A fresh control refresh can also trigger stop recovery. No periodic
screen-off polling, new internet permission, telemetry or external find action is
added. Widgets, automation, shortcuts and tiles cannot start a find sound.

## Protocol evidence

The exact pinned official image SHA-256 is
`20f5f148be6f346677422679e78a251219a72ac9501ad959a01783fa8439df9f`.

- Dispatch maps **[0x10,0x89]** to internal0x35 and registered setter0x1412b1c4.
  Payload is two enable flags; local side0 selects the first and side1 the second.
  Stop uses both flags zero. TWS synchronization forwards both flags.
- **27 offline Unicorn checks** execute the actual stock setter for stop, left,
  right and both, including its mobile-master gate. Audio/TWS helpers are modeled;
  these are not hardware or acoustic tests. Script and numeric results are under
  `research/emulate_find_handler.py` and `research/find-handler-validation.json`.
- The setter can acknowledge success while ignoring a non-master request.
  Fresh normal status and firmware are checked, but an ACK is not acoustic proof.
- The getter **[0x10,0x09]**, internal0x36, returns three zero bytes in its stub.
  It is **not polled as a playing/stopped-state query**.
- Gesture handling around0x141326e4 stops the timer, synchronizes zero flags,
  clears the local find state and calls0x1412c5f4, which notifies internal0x36.
  Only the matching unsolicited successful GET notification with three zero bytes
  is treated as a device-side cancellation event. SET ACKs cannot masquerade as it.
- Local setter0x141308e8 and callback0x1412d208 rearm a **1000-ms repeating timer**.
  That is the repeat interval, not a finite timeout. No native 30-second cutoff
  has been established. The preview explicitly uses phone-controlled stopping,
  reflecting the owner's acceptance of the pause/resume behavior.

## Validation and remaining acceptance

Synthetic protocol, session, lifecycle, UI and repository tests cover packet flags,
per-ear page-lifetime confirmation/Cancel, Sleep-mode flags, standby replay,
a 30-second stop even after lost start ACK, no automatic start replay,
uncertainty/recovery, gesture cancellation, target availability, version checks,
normal-action exclusion, retained receipts and pair-bound recovery. No automated
test sends Bluetooth packets or plays sound. The commands were investigated in
stock 01.57; the owner's case/gesture observations came from the official app.
The owner reports Companion 0.4.4 finding works. The revised 0.4.6 layout,
page-lifetime confirmation and Sleep-mode finding still need phone acceptance.
