# Debug Ultra asset-probe A/B capture (parked bench only)

`scripts/ultra_ab_capture.py` is a **read-only** preflight and supervised log capture. It does
not install an APK, write Android preferences, start a CarPlay session, send AA05, or operate the
iPhone. A user must separately authorize each parked/bench reconnect. A user-signed Debug Profile
and the asset probe do not establish Apple/OEM authorization or complete CarPlay Ultra.

Prerequisites: the signed MAIN-only Debug Profile is already active; experimental Ultra, MAIN
DDP probe, CAF bench opt-in, and signed physical UI Sync opt-in are enabled. Populate the asset
probe's ID, SDK version, certificate type, and current MFi identity SHA-256 pin **before** A, but
leave its switch off. Exactly one trusted USB iPhone and one authorized ADB Android device must
be visible. `adb exec-out run-as com.shilapi.xcertplay` must be able to read the Debug app's
own preferences and active imported Profile. Neither raw logs nor credentials are printed.

Run A first with the asset probe **off**:

```sh
python3 scripts/ultra_ab_capture.py capture --leg A --run-dir /private/tmp/ultra-asset-a
```

Wait for `READY` before manually reconnecting once. The tool starts both fresh capture files,
checks that `idevicesyslog` is live and has emitted a line, reads Android's clock, then starts
`adb logcat -v epoch -T <future-epoch>.000` and requires an epoch-formatted event at or after
that threshold. A replayed old line or logcat divider cannot satisfy READY. Keep it in the
foreground; after the
session press Ctrl-C. It also stops automatically after three minutes. A quick Ctrl-C or timeout
without **post-READY** growth on both streams and a bounded Android AA05 or AirPlay SETUP marker
is recorded as `inconclusive`, never as a completed run. On failure, **do not reconnect**;
inspect the condition, while keeping private raw logs out of chats and commits. SIGTERM and
repeated Ctrl-C are handled during cleanup; an exact capture child that ignores SIGTERM is
killed and reaped. An unretirable child leaves its PID and `retirement-uncertain` status.

Only after A has stopped, turn **only** the asset-probe switch on in the app and save. Do not
re-stage or re-sign the Profile or change any other setting. Run B separately:

```sh
python3 scripts/ultra_ab_capture.py capture --leg B --baseline /private/tmp/ultra-asset-a \
  --run-dir /private/tmp/ultra-asset-b
```

B refuses to start if the exact active Profile generation, ZIP, signing pin, detached signature,
vehicle ID, installed Debug APK base or any split APK, known non-probe settings, Debug bench
opt-ins, device identities, or pinned MFi certificate identity changed from A. Each APK is
streamed and hashed read-only, without saving APK bytes. The tool also repeats its snapshot at
the end of each leg; a mid-run input change makes that leg inconclusive. Certificate rotation
is therefore detectable if the configured pin changes; the captured identity
pin must also match the newly presented certificate in the real handshake, which this read-only
preflight cannot itself prove. B waits for fresh dual capture output before reporting `READY`.
Compare the completed manifests:

```sh
python3 scripts/ultra_ab_capture.py compare --a /private/tmp/ultra-asset-a \
  --b /private/tmp/ultra-asset-b
```

The run directories are mode `0700`; raw logs, stderr, comparison key and manifests are mode
`0600`. The manifest contains hashes/HMACs and the public MFi identity pin, not log lines,
Wi-Fi credentials, or Profile bytes. The tool intentionally leaves logs on disk after stopping;
review them locally, sanitize specific excerpts before sharing, and remove them later under the
user's own retention policy. An incomplete/failed run is never comparable.

After B, use the app's own settings/cover-aware Save to turn off CAF and UI Sync bench opt-ins,
Ultra, DDP and asset probe, and restore ordinary CarPlay. The script does not attempt that
mutation. Verify ordinary type-110 and type-111 dimensions and first frames before declaring
recovery. The assetless leg must be first: iOS may persist asset identity after B, so a B→A
sequence is not an equivalent clean control without phone-side proof.

Limitations: readiness establishes that newly launched capture processes produced output before
reconnect, not that the iPhone accepted a theme asset. If Android `pm path` or read-only APK
streaming is unavailable to the shell user, preflight fails rather than comparing an unknown
installed build. The comparison covers the explicitly
allowlisted AirPlay and Debug-bench preferences plus active signed import; unknown preference
keys and other device/external state are not equality evidence. A strict comparison may fail
closed because pairing or runtime preferences changed naturally between sessions. Resolve that
cause rather than weakening the equality check or changing multiple inputs at once. The live
`0x4301` must still be checked separately: a saved probe switch can be silently omitted when
the exact MFi identity pin does not match. `SCREEN_CANDIDATE` Debug markers distinguish zero
TCP candidates, wrong peers, pre-auth stalls, and authenticated frames without logging payload.
