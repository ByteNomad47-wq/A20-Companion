# Implementation status

## 0.2.0 controls preview

Implemented: offline Expressive app, normal secure status transport, left/right/case
battery cache and ages, simplified phone connection display, supplied product
images, pull-to-refresh, themes, colored/shape mode selection, toggle tile, opt-in
Sleep/Bluetooth/toggle intents, optional companion ready mode, bounded operations
and local sanitized export. The controls sit at the bottom on tall screens;
short/large-text layouts scroll. Device dialogs use the same Expressive theme.
The slider controls phone media volume in Bluetooth and local sounds in Sleep.
Single-bud switching is enabled; it and audible Sleep-volume behavior need owner
acceptance. Sleep-volume command/range/state behavior passed 554 offline checks. The later 0.4.0 preview adds guided charging repair (below).

The exact 01.57 setter was identified and the owner confirmed four round trips.
The active earbud and any linked peer must report 01.57 for setting writes. The same gate, one-write
policy, deadline and readback confirmation apply to every entry point.

## Next acceptance work

Physically test tile and automation with the app closed/phone locked on Samsung.
Check permission revocation, process death, range loss and companion presence.
Validate single-bud primary mapping and battery/case behavior. Other firmware
requires its own evidence rather than a relaxed compatibility guard.

Measure matched phone/earbud overnight power with ready mode off/on before calling
it stable. No screen-off polling or held wake lock is implemented. Multiple pairs,
sounds and alarms remain future work.

## 0.3.0 widgets preview

The Bluetooth volume thumb moves continuously rather than snapping to each Android
media-volume step. Android's actual volume levels remain discrete.

Fixed-size 3×1 rings and 5×2 battery/mode widgets are implemented, with owner-reviewed
layouts. The large widget has percentages, stacked Bluetooth/Sleep controls and
refresh in the top-left. Neither widget shows Left/Right/Case labels; the compact
widget has no visible percentages. Native rendering, action dispatch, payload size,
cache-only lifecycle callbacks and slider dragging are covered by automated tests.
No polling timer or network dependency was added. Physical Samsung launcher/widget
action acceptance remains pending. See [widget details](WIDGETS.md).

## 0.3.1 launcher shortcuts

Two static app-icon shortcuts select Bluetooth or Sleep through the normal visible
activity and shared guarded runner. No automation/Ready setting is required. Widget
opening uses ordinary launcher task flags and the platform background marker for
widget transitions. Automated lifecycle/dispatch checks cover both; One UI shortcut
interaction and return-to-widget animation need owner acceptance.

## 0.3.2 direct shortcuts and home device management

Launcher actions now hand off to the private service through a transparent transient
activity, without showing the dashboard. The home connection card opens device management;
the picker owns local forget and Bluetooth settings. Your A20 was removed from Settings.
The owner confirmed both 0.3.1 shortcuts work; this release changes their presentation
and service ownership. Samsung no-flash and cold-service behavior need acceptance.

## 0.4.0 guided charging repair

Experimental per-ear diagnosis, supported-profile one-time clear, durable local
reports/ledger and read-only follow-up. No firmware installer or exported repair
action. Stock complete-record access is unqualified and stops safely; see the
[repair procedure](REPAIR.md). Native repair hardware acceptance remains pending.
The Settings heading now reads Settings.

## 0.4.1 direct reset entry

Reset charging records is visible immediately. It starts read-only diagnosis and
opens confirmation only after a supported, unused preparation; Check stays
read-only. Existing reset guards and one-use history are unchanged. Follow-up
results show the main counter and explain counts 0–2 versus the restriction
threshold, including for previously reset ears.
