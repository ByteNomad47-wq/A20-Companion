# Fixed-size widgets

Included in 0.3.0-preview. Add either widget from **Settings → Home screen widgets**,
or long-press the launcher, open Widgets, then choose A20 Companion.
See the [native-rendered previews](../design/widget-mockups/README.md).

## Compact: three columns, one row

Three rings using the supplied left/right/case images, with no visible percentages.
The percentage and receipt time remain available in accessibility descriptions.
Tap a battery or background to open Companion. Unknown values stay empty; cached
readings do not establish a current connection.

## Large: five columns, two rows

The first three columns are Left, Right and Case, each spanning both rows.
The last two columns hold full-width Bluetooth on top and Sleep below. The selected
mode has a filled accent color and a different corner shape. Refresh sits in the
top-left corner above the battery columns. No timestamp is shown; individual
receipt times remain available in accessibility descriptions. No Left/Right/Case
labels are shown; order is left earbud, right earbud, case from left to right.

Both metadata definitions use resizeMode=none. Launcher grid sizes determine actual
dp dimensions; the target spans are 3×1 and 5×2. Pin buttons are implemented in
Settings; a launcher without pin support can add them through its widget picker.

## Data and actions

Widgets render the local battery cache; there is no periodic widget update timer,
Bluetooth query on creation/repaint, new background polling or held Sleep socket.
State changes repaint installed widgets, as do app theme/configuration/time-zone
events. Receipt times are retained in accessibility descriptions across long idle periods.

The large widget sends explicit immutable PendingIntents directly to the private
shared action service. A genuine widget tap supplies Android's user-interaction
foreground-service exemption; External automation and Ready mode are not prerequisites.
Mode writes retain the same fresh read, firmware guards, one write and readback.
Missing setup or Nearby Devices access opens Companion. Android force-stop remains
respected. Actual Samsung launcher appearance and widget actions still need testing.

## Opening and closing the app

Widget taps use a normal MAIN/LAUNCHER activity PendingIntent, without clearing the
existing task. Both widget backgrounds expose Android's standard `android.R.id.background`
and an outline for the launcher's widget transition. This supplies the platform
hooks for expanding from and shrinking back into the widget. The home launcher
controls the animation; Samsung One UI acceptance is pending. No custom app exit
animation is imposed on Home or Back.
