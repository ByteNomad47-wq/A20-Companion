# A20 Reset Lab — experimental public build

A separate Android app for an informed manual charging-record reset experiment on
**original Soundcore Sleep A20 (A6611)**. It installs alongside normal Companion.

**Firmware 01.58 compatibility is unverified.** The reset was examined and tested
physically on 01.57. This build removes the manual-reset firmware-version gate;
it does not establish compatibility with another firmware. Official 01.50 lacks
the diagnostic interface this repair uses. No firmware is flashed or downgraded.

## Download

[Download the APK](https://github.com/ByteNomad47-wq/A20-Companion/releases/download/reset-lab-v1.0.0/a20-reset-lab-1.0.0.apk)

[Matching source archive](https://github.com/ByteNomad47-wq/A20-Companion/releases/download/reset-lab-v1.0.0/a20-reset-lab-1.0.0-source.zip)

The public package is `io.github.bytenomad47.a20.resetlab`. It installs separately
from an earlier research copy with a different package name. Save any report from
an earlier copy before removing it. This does not replace the normal Companion app.

## Use

1. Confirm A6611 on the case/box; Bluetooth-name filtering is not independent proof
   of hardware identity. Pair the A20 in Android Bluetooth settings.
2. Close Soundcore and Companion so their control connections are released.
3. Open **A20 Reset Lab**, grant Nearby Devices access, and choose the A20.
4. Open **Experimental reset**, select **one earbud**, and leave the other in the
   closed case. Read the warning and explicitly confirm the manual reset.
5. Afterwards open **Advanced → Export latest report** and share the JSON privately.

The app retains fresh single-ear status, known reply-format, cached-voltage and
charging-time-record checks. A changed protocol can be refused. There is one clear
command per confirmed request, no automatic retry, and no reset on launch.

An acknowledgement does **not** prove main-counter zero, power-loss persistence,
normal battery capacity or charging recovery. Cleared records cannot be restored
by this app. Fault-count reading is unavailable in this research build. A lost
reply can leave an unknown outcome even if the clear was delivered.

Clearing charging records can remove a restriction and permit a higher charging
target. Do not use on a hot or damaged earbud; supervise the next charge. This
cannot repair a worn battery. Repeated faults need investigation.

## Validation and privacy

33 repair tests passed, including simulated 01.58 and wrong-ear/peer, consent,
replay and unknown-outcome cases. Signed release build, lint, package, permissions
and signature checks passed. Phone installation and real 01.58 reset behavior have
not been verified here.

No Internet permission, telemetry, widgets, shortcuts or automation entry point.
No credentials, signing keys, personal captures or device identifiers are included.
Matching GPL-3.0-only source is supplied as the separate archive; the repository's
main app source/release continues to describe normal Companion.
