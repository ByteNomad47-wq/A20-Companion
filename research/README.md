# Exact 01.57 mode-command evidence

Only independent protocol facts, original helper code and numeric validation
results are included here. Proprietary firmware, decompiler output and personal
captures are absent.

Source: original A6611 01.57 firmware, SHA-256
`20f5f148be6f346677422679e78a251219a72ac9501ad959a01783fa8439df9f`.
The earlier repair repository documents its provenance.

The command mapping table at `0x3426E290` contains `[01,14,17]` and
`[01,A9,18]`: group/command/internal dispatch index. The callback registry at
`0x1412BFF8` assigns internal `0x17` to `0x1412937C` (current-mode getter) and
`0x18` to `0x1412A264` (current-mode setter). The parser/dispatcher at
`0x141215A0` / `0x1412171C` resolves those table entries.

The setter consumes the first payload byte and invokes the internal mode routine
at `0x141309F4` with that value, `1`, `0`. The known absolute values are
`00=Bluetooth` and `01=Sleep`. Only these two values are encoded. The handler
returns an empty acknowledgement; response flag `1` means accepted and `0`
means refused. It can acknowledge without applying a change when the phone is
not its master, so fresh-state readback is required.

Exact outbound frames (existing normal-channel length and byte-sum checksum):

- Bluetooth: `08 EE 00 00 00 01 A9 0B 00 00 AB`
- Sleep: `08 EE 00 00 00 01 A9 0B 00 01 AC`

The stock handler refuses during OTA, a sleep-scene restriction, or its
closed/inactive box-state condition. These conditions and the mobile-master
check are observations of the firmware, not additional phone permissions.
The internal mode routine may defer a transition while another transition is busy.

## Optional offline reproduction

Install `unicorn==2.1.4` in an isolated Python environment, then supply your own
exact firmware file:

```sh
python research/emulate_mode_handler.py /path/to/A6611-01.57.bin --output /tmp/mode-validation.json
```

The script rejects any other SHA-256. It maps the image into an offline Thumb
emulator and executes the **stock setter callback** for both requested values,
OTA refusal, non-master behavior, inactive box state, sleep-scene restriction and
active-audio handling. It checks the real table and registered function pointers.
[Committed result](mode-handler-validation.json): **64 checks passed**.

Logging, hardware state and the internal transition side effects are explicitly
modelled. This does not emulate a complete earbud, radio, sensor or physical mode
transition. It performs no OTA or connected-device operation. The subsequent owner test confirmed four transitions in each direction and Sleep
reconnection on the owned pair. See [aggregate physical evidence](mode-control-device-validation.json).
Quick Settings/background automation and overnight power remain pending physical checks.

## Sleep-volume follow-up

[Stock Sleep-volume emulation](emulate_sleep_volume.py) validates `[13,05]`, raw
0..65 and the local volume state writes, with [554 checks](sleep-volume-handler-validation.json).
Hardware application and synchronization are explicit stubs; audible validation
is pending. See [protocol notes](../docs/PROTOCOL.md) for the status offset and guards.

## Sleep playback

[Stock Sleep-playback emulation](emulate_sleep_playback.py) validates `[13,02]`,
the selected slot and desired playing flag, actual local state transitions and
the actual getter, with [1,124 checks](sleep-playback-handler-validation.json).
Audio/TWS/slot-loading are modeled. [Playback protocol and limits](../docs/PLAYBACK.md).
