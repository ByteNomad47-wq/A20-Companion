# A20 Companion

A simple, offline Android companion for the **original Soundcore Sleep A20 (A6611)**,
with a Material 3 Expressive interface. No account, telemetry, internet permission
or root required.

## Download

[Get the latest release](https://github.com/ByteNomad47-wq/A20-Companion/releases/latest) · [APK checksum](artifacts/SHA256SUMS)


Requires **Android 12+**. Device controls target **A6611 firmware 01.57**.
This edition uses its own application ID and installs separately from earlier private previews.

## Features

- Left, right and case battery levels, with last-update times.
- Bluetooth/Sleep switching, volume and play/pause controls.
- Find earbuds with a loud-sound confirmation.
- Battery widgets, Quick Settings tile and app shortcuts.
- Optional mode automation and charging repair.

[Light and dark previews](design/playback/README.md)

## Get started

1. Pair your A20 in Android Bluetooth settings. Close Soundcore if it holds the connection.
2. Open Companion, grant Nearby Devices access and select your paired A20.
3. Use the controls on Home. Pull down to refresh; tap the connection card to manage your device.

Battery readings use the device's 10% steps. The case level is relayed by the earbuds
and may be cached. Find sounds are loud: remove the earbuds from your ears first.
Closing the case only pauses the finding sound; use Stop or the documented gesture
to cancel it. Charging repair is experimental and requires a separate confirmation.

## Learn more

- [Using the app](docs/USAGE.md)
- [Playback](docs/PLAYBACK.md), [Find earbuds](docs/FIND_EARBUDS.md) and [charging repair](docs/REPAIR.md)
- [Widgets](docs/WIDGETS.md) and [automation](docs/AUTOMATION.md)
- [Privacy](PRIVACY.md)
- [Build from source](docs/BUILD.md), [protocol research](docs/PROTOCOL.md) and [testing](docs/TESTING.md)

Independent software, not affiliated with Anker or Soundcore. Source code is
[GPL-3.0-only](LICENSE); product images have separate rights described in
[third-party notices](THIRD_PARTY.md).
