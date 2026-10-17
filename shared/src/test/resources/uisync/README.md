# iOS 27 UI Sync conformance vectors

These binary-plist fixtures are semantic conformance vectors generated independently of the Kotlin codec with
Python 3's `plistlib.dumps(value, fmt=plistlib.FMT_BINARY, sort_keys=False)`. They contain no Apple
code or captured user data.

Tests pin each fixture's own SHA-256, decode it independently, and compare the product encoder's
outer dictionary/Data choices and recursively decoded semantic trees. Whole bplist bytes are not
compared across encoders because valid serializers may choose different object-table layouts and
integer/data length encodings for the same wire value.

The fixtures pin the message layer only. iOS 27 `DBUISyncChannel` carries the outer
`{displayUUID, data}` dictionary as OPACK, so the tests re-encode each fixture's decoded outer
dictionary with OPACK and keep the inner `Data` bytes verbatim before decoding.

All carriers use `displayUUID = "instrument-cluster"`. Reset-complete messages use
`id = "vehicle-test"` and the logical payload:

```text
{"data": {"config": {"test": true}}}
```

The command payload is:

```text
{"type": 5, "data": {"layout": "default"}}
```

For protocol v2, the outer `data` value and every non-null message payload are independently
serialized binary-plist Data values. For protocol v3, those same values are dictionaries. The ACK
fixture omits `noAck`, which decodes as `false`.

The reset-complete hashes are intentionally pinned to:

- v2: `31f0ac25f48e04e81f226eaf5d69f600c477cdcb60409e0673508f08b7716113`
- v3: `1b602dd2255dcb1d9fb4ec173bc035c40fbfe6363189d37f0ea13407edd80e48`

Independent Reset vectors cover versions 1, 2, and 3. ACK vectors cover both the canonical omitted
`noAck` form and an explicit `noAck = false` dictionary; both must decode as false.
