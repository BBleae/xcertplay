#!/usr/bin/env python3
"""Read-only, foreground capture for a parked Debug Ultra asset-probe A/B trial.

This tool never changes Android or iPhone state. It stops both child captures on
Ctrl-C or at its time limit. The READY line is deliberately withheld until both
new capture files have received output and both capture processes remain alive.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import selectors
import signal
import stat
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


PACKAGE = "com.shilapi.xcertplay"
AIRPLAY_PREFS = "shared_prefs/xcertplay_airplay.xml"
BENCH_PREFS = "shared_prefs/debug-ultra-bench-v1.xml"
IMPORT_ROOT = "no_backup/ultra-bench-import-v1"
PROBE_KEY = "cluster_asset_catalog_probe_enabled"
MAX_PREFS = 1_048_576
MAX_PROFILE = 32 * 1024 * 1024
MAX_APK = 256 * 1024 * 1024
MAX_SMALL_FILE = 2_048
MAX_POST_READY_BYTES = 16 * 1024 * 1024
SCHEMA = 1
CAPTURE_TIMEOUT_MIN = 10
CAPTURE_TIMEOUT_MAX = 600
DEVICE_ID = re.compile(r"[A-Za-z0-9._:-]{1,128}\Z")
IMPORT_GENERATION = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\Z")
HEX_DIGEST = re.compile(r"[0-9a-fA-F]{64}\Z")
SAFE_APK_PATH = re.compile(r"/data/app/[A-Za-z0-9_./~+=-]+\.apk\Z")
EPOCH_LOG_LINE = re.compile(rb"^\s*([0-9]{10,11})\.([0-9]{1,6})\s")
AIRPLAY_KEYS = frozenset({
    "identity_private", "identity_public", "pairing_id", "pairing_ids",
    "lockdown_host_id", "lockdown_system_buid", "lockdown_wifi_mac",
    "lockdown_device_public", "lockdown_device_cert", "lockdown_host_private",
    "lockdown_host_cert", "lockdown_root_private", "lockdown_root_cert",
    "display_scale_tenths", "cluster_display_enabled", "carplay_ultra_enabled",
    "physical_display_panels_probe_enabled", PROBE_KEY,
    "cluster_asset_catalog_probe_asset_id", "cluster_asset_catalog_probe_sdk_version",
    "cluster_asset_catalog_probe_certificate_type", "cluster_asset_catalog_probe_identity_sha256",
    "hevc_enabled", "hevc_software_decoder", "advanced_audio_channel_mapping",
    "audio_focus_enabled", "main_media_audio_buffer_duration_ms", "microphone_gain_percent",
    "wireless_enabled", "wireless_hotspot_mode", "manual_hotspot_ssid",
    "manual_hotspot_passphrase", "manual_hotspot_band", "manual_hotspot_channel",
    "manual_hotspot_security", "video_frame_logs_enabled", "debug_logs_enabled",
    "media_metrics_enabled", "audio_packet_capture_enabled", "more_gestures_to_settings",
    "manufacturer", "model", "oem_label", "display_fps", "display_width_physical_mm",
    "display_physical_size_basis", "display_physical_size_basis_version",
    "display_max_detected_width", "display_max_detected_height", "right_hand_drive",
    "hide_top_bar", "hide_bottom_bar", "safe_area_draw_outside", "auto_start_on_boot",
    "location_reporting_enabled", "mfi_target", "mfi_i2c_path", "remote_mfi_server",
    "remote_mfi_token", "local_mfi_certificate_uri", "local_mfi_private_key_uri",
    "web_display_key",
    "vehicle_data_platform",
    *("vehicle_data_" + feature for feature in (
        "ALTITUDE", "GPS_SPEED", "COURSE", "ACCURACY", "WHEEL_SPEED", "EV_STATUS",
        "OUTSIDE_TEMPERATURE", "BAROMETRIC_PRESSURE", "PASSENGER_SEAT", "WIPERS",
    )),
    *("web_display_" + str(screen) + "_" + field for screen in (110, 111)
      for field in ("enabled", "width", "height", "preview")),
})
BENCH_KEYS = frozenset(("caf-only-explicit-opt-in", "signed-physical-uisync-explicit-opt-in"))
SECRET_KEYS = frozenset({
    "identity_private", "lockdown_host_private", "lockdown_root_private",
    "manual_hotspot_passphrase", "remote_mfi_token", "web_display_key",
})
SAFE_AREA_KEY = re.compile(r"safe_area_[0-9]{1,5}x[0-9]{1,5}\Z")


class PreflightError(Exception):
    """A condition that makes the proposed comparison unsafe or inconclusive."""


def _utc_now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _sha256(data):
    return hashlib.sha256(data).hexdigest()


def _tag(key, data):
    return hmac.new(key, data, hashlib.sha256).hexdigest()


def _private_file(path, data):
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    fd = os.open(path, flags, 0o600)
    with os.fdopen(fd, "wb") as output:
        output.write(data)
        output.flush()
        os.fsync(output.fileno())


def _replace_manifest(run_dir, manifest):
    payload = (json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n").encode()
    staged = run_dir / ("manifest-" + secrets.token_hex(8) + ".tmp")
    _private_file(staged, payload)
    os.replace(staged, run_dir / "manifest.json")


def _read_private(path, maximum):
    flags = os.O_RDONLY
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        fd = os.open(path, flags)
    except OSError as error:
        raise PreflightError("required private run file is missing or unsafe") from error
    with os.fdopen(fd, "rb") as source:
        metadata = os.fstat(source.fileno())
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.getuid() or \
                stat.S_IMODE(metadata.st_mode) & 0o077:
            raise PreflightError("private run file owner or permissions are unsafe")
        if metadata.st_size > maximum:
            raise PreflightError("private run file exceeds its size limit")
        content = source.read(maximum + 1)
        if len(content) > maximum:
            raise PreflightError("private run file exceeds its size limit")
        return content


def _read_manifest(run_dir):
    if (run_dir.is_symlink() or not run_dir.is_dir() or
        run_dir.stat().st_uid != os.getuid() or stat.S_IMODE(run_dir.stat().st_mode) & 0o077):
        raise PreflightError("run directory permissions are unsafe")
    try:
        data = json.loads(_read_private(run_dir / "manifest.json", 32_768))
    except (ValueError, UnicodeError) as error:
        raise PreflightError("run manifest is invalid") from error
    if not isinstance(data, dict) or data.get("schema") != SCHEMA:
        raise PreflightError("run manifest schema is invalid")
    return data


def _bounded_command(argv, maximum, timeout, digest_only=False):
    output = bytearray() if not digest_only else None
    digest = hashlib.sha256()
    total = 0
    process = None
    try:
        process = subprocess.Popen(argv, stdin=subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        assert process.stdout is not None
        os.set_blocking(process.stdout.fileno(), False)
        deadline = time.monotonic() + timeout
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise PreflightError("read-only device preflight command timed out")
                if not selector.select(remaining):
                    raise PreflightError("read-only device preflight command timed out")
                try:
                    chunk = os.read(process.stdout.fileno(), min(65_536, maximum + 1 - total))
                except BlockingIOError:
                    continue
                if not chunk:
                    break
                total += len(chunk)
                if total > maximum:
                    raise PreflightError("read-only device preflight output exceeded its limit")
                digest.update(chunk)
                if output is not None:
                    output.extend(chunk)
        if process.wait(timeout=max(0.1, deadline - time.monotonic())) != 0:
            raise PreflightError("read-only device preflight command failed")
    except (OSError, subprocess.TimeoutExpired) as error:
        raise PreflightError("read-only device preflight command failed") from error
    finally:
        if process is not None:
            if process.poll() is None:
                process.kill()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired as error:
                raise PreflightError("read-only preflight child retirement is uncertain") from error
            if process.stdout is not None:
                process.stdout.close()
    return (digest.hexdigest(), total) if digest_only else bytes(output)


def _command_output(argv, maximum=MAX_PREFS, timeout=8):
    # Never print command output: it may contain a provider token or Wi-Fi credential.
    return _bounded_command(argv, maximum, timeout)


def _installed_apks(adb, serial):
    paths_output = _command_output(
        [adb, "-s", serial, "shell", "pm", "path", PACKAGE], maximum=16_384,
    )
    try:
        rows = paths_output.decode("ascii", "strict").splitlines()
    except UnicodeError as error:
        raise PreflightError("installed APK path inventory is invalid") from error
    paths = []
    for row in rows:
        if not row.startswith("package:"):
            raise PreflightError("installed APK path inventory is invalid")
        path = row[len("package:"):]
        if (not SAFE_APK_PATH.fullmatch(path) or "/../" in path or "/./" in path or
            path in paths):
            raise PreflightError("installed APK path inventory is invalid")
        paths.append(path)
    if not 1 <= len(paths) <= 16 or sum(Path(path).name == "base.apk" for path in paths) != 1:
        raise PreflightError("installed Debug APK base/split inventory is invalid")
    entries = []
    for path in sorted(paths):
        digest, size = _bounded_command(
            [adb, "-s", serial, "exec-out", "cat", path], MAX_APK, timeout=60,
            digest_only=True,
        )
        if size == 0:
            raise PreflightError("installed APK is empty")
        entries.append({"name": Path(path).name, "sha256": digest, "bytes": size})
    if _command_output([adb, "-s", serial, "shell", "pm", "path", PACKAGE],
                       maximum=16_384) != paths_output:
        raise PreflightError("installed APK paths changed during the read-only snapshot")
    return entries


def _single_adb_device(adb):
    output = _command_output([adb, "devices"], maximum=16_384).decode("ascii", "strict")
    rows = [line.split() for line in output.splitlines()[1:] if line.strip()]
    if len(rows) != 1 or len(rows[0]) < 2 or rows[0][1] != "device":
        raise PreflightError("exactly one authorized Android device is required")
    serial = rows[0][0]
    if not DEVICE_ID.fullmatch(serial):
        raise PreflightError("Android device ID is invalid")
    return serial


def _single_iphone(idevice_id):
    output = _command_output([idevice_id, "-l"], maximum=16_384).decode("ascii", "strict")
    rows = [line.strip() for line in output.splitlines() if line.strip()]
    if len(rows) != 1 or not DEVICE_ID.fullmatch(rows[0]):
        raise PreflightError("exactly one trusted USB iPhone is required")
    return rows[0]


def _android_future_logcat_epoch(adb, serial):
    # The installed logcat help defines decimal sssss.mmm for -T TIME. The decimal point
    # disambiguates a future timestamp from `-T N`'s recent-line-count form.
    value = _command_output(
        [adb, "-s", serial, "shell", "date", "+%s"], maximum=64,
    ).strip()
    if not re.fullmatch(rb"[0-9]{10,11}", value):
        raise PreflightError("Android wall-clock probe was invalid")
    seconds = int(value)
    if not 1_600_000_000 <= seconds <= 4_102_444_800:
        raise PreflightError("Android wall clock is outside the supported epoch range")
    return seconds + 3


def _valid_since_epoch(value):
    return type(value) is int and 1_600_000_003 <= value <= 4_102_444_803


def _app_file(adb, serial, relative_path, maximum):
    # run-as requires a Debug APK. No root, shell, write, or SharedPreferences mutation.
    output = _command_output(
        [adb, "-s", serial, "exec-out", "run-as", PACKAGE, "cat", relative_path],
        maximum=maximum,
        timeout=30 if maximum == MAX_PROFILE else 8,
    )
    if not output:
        raise PreflightError("required Debug app state is empty")
    return output


def _parse_preferences(data, allowed, key):
    try:
        root = ET.fromstring(data)
    except ET.ParseError as error:
        raise PreflightError("Debug app preferences are invalid") from error
    if root.tag != "map":
        raise PreflightError("Debug app preference root is invalid")
    values = {}
    for item in root:
        name = item.attrib.get("name")
        # XML parsing is necessary, but never extract or retain any non-allowlisted value.
        if name not in allowed and not (allowed is AIRPLAY_KEYS and
                                        name is not None and SAFE_AREA_KEY.fullmatch(name)):
            continue
        if not name or name in values or item.tag not in ("boolean", "int", "long", "float", "string", "set"):
            raise PreflightError("Debug app preference entry is invalid")
        if item.tag == "set":
            if any(child.tag != "string" for child in item):
                raise PreflightError("Debug app preference set is invalid")
            value = sorted(child.text or "" for child in item)
        elif item.tag == "string":
            value = item.text or ""
        else:
            value = item.attrib.get("value")
            if value is None:
                raise PreflightError("Debug app preference value is missing")
        if name in SECRET_KEYS:
            value = _tag(key, json.dumps(value, sort_keys=True).encode())
            values[name] = ["hmac-sha256", value]
        else:
            values[name] = [item.tag, value]
    return values


def _pref_bool(values, key):
    if values.get(key) == ["boolean", "true"]:
        return True
    if values.get(key) == ["boolean", "false"]:
        return False
    raise PreflightError("required Debug bench setting is absent or invalid")


def _pref_string(values, key):
    value = values.get(key)
    if value is None or value[0] != "string" or not isinstance(value[1], str):
        raise PreflightError("required Debug bench setting is absent or invalid")
    return value[1]


def _snapshot(adb, serial, iphone_id, leg, key):
    installed_apks = _installed_apks(adb, serial)
    airplay_data = _app_file(adb, serial, AIRPLAY_PREFS, MAX_PREFS)
    bench_data = _app_file(adb, serial, BENCH_PREFS, MAX_PREFS)
    airplay = _parse_preferences(airplay_data, AIRPLAY_KEYS, key)
    bench = _parse_preferences(bench_data, BENCH_KEYS, key)
    for field in ("carplay_ultra_enabled", "physical_display_panels_probe_enabled"):
        if not _pref_bool(airplay, field):
            raise PreflightError("signed MAIN DDP bench is not enabled")
    for field in ("caf-only-explicit-opt-in", "signed-physical-uisync-explicit-opt-in"):
        if not _pref_bool(bench, field):
            raise PreflightError("signed MAIN UI Sync bench is not enabled")
    if _pref_bool(airplay, PROBE_KEY) != (leg == "B"):
        raise PreflightError("asset probe state does not match the requested A/B leg")
    mfi_type = _pref_string(airplay, "cluster_asset_catalog_probe_certificate_type")
    mfi_pin = _pref_string(airplay, "cluster_asset_catalog_probe_identity_sha256")
    if mfi_type not in ("MFI", "BAA") or not HEX_DIGEST.fullmatch(mfi_pin):
        raise PreflightError("catalog probe MFi identity pin is absent or invalid")
    if not _pref_string(airplay, "cluster_asset_catalog_probe_asset_id").strip() or not _pref_string(
        airplay, "cluster_asset_catalog_probe_sdk_version"
    ).strip():
        raise PreflightError("catalog probe metadata is incomplete")
    generation = _app_file(adb, serial, IMPORT_ROOT + "/active", 64).decode("ascii").strip()
    if not IMPORT_GENERATION.fullmatch(generation):
        raise PreflightError("active signed Profile generation is invalid")
    profile_dir = IMPORT_ROOT + "/profile-" + generation + "/"
    profile = _app_file(adb, serial, profile_dir + "profile.zip", MAX_PROFILE)
    profile_pin = _app_file(adb, serial, profile_dir + "p256-pin.der", MAX_SMALL_FILE)
    signature = _app_file(adb, serial, profile_dir + "signature.der", MAX_SMALL_FILE)
    vehicle_id = _app_file(adb, serial, profile_dir + "vehicle-id.txt", MAX_SMALL_FILE)
    if (_app_file(adb, serial, IMPORT_ROOT + "/active", 64).decode("ascii").strip() != generation or
        _app_file(adb, serial, profile_dir + "signature.der", MAX_SMALL_FILE) != signature or
        _app_file(adb, serial, AIRPLAY_PREFS, MAX_PREFS) != airplay_data or
        _app_file(adb, serial, BENCH_PREFS, MAX_PREFS) != bench_data):
        raise PreflightError("Debug bench inputs changed during the read-only snapshot")
    if _installed_apks(adb, serial) != installed_apks:
        raise PreflightError("installed Debug APK changed during the read-only snapshot")
    # Compare the explicit known-setting allowlist, excluding only the deliberate probe bit.
    # Unknown keys and external device state remain outside this equality proof.
    airplay_without_probe = dict(airplay)
    airplay_without_probe.pop(PROBE_KEY, None)
    canonical = lambda value: json.dumps(value, sort_keys=True, separators=(",", ":")).encode()
    return {
        "android_device_tag": _tag(key, serial.encode()),
        "iphone_device_tag": _tag(key, iphone_id.encode()),
        "airplay_prefs_tag": _tag(key, canonical(airplay_without_probe)),
        "bench_prefs_tag": _tag(key, canonical(bench)),
        "vehicle_id_tag": _tag(key, vehicle_id),
        "profile_generation": generation,
        "profile_sha256": _sha256(profile),
        "profile_pin_sha256": _sha256(profile_pin),
        "signature_sha256": _sha256(signature),
        "installed_apks": installed_apks,
        "mfi_certificate_type": mfi_type,
        "mfi_identity_pin": mfi_pin.lower(),
    }


COMPARISON_FIELDS = (
    "android_device_tag", "iphone_device_tag", "airplay_prefs_tag", "bench_prefs_tag",
    "vehicle_id_tag", "profile_generation", "profile_sha256", "profile_pin_sha256",
    "signature_sha256", "installed_apks", "mfi_certificate_type", "mfi_identity_pin",
)


def _compare_inputs(a, b):
    if a.get("leg") != "A" or b.get("leg") != "B":
        raise PreflightError("assetless A must precede asset-probe B")
    changed = [field for field in COMPARISON_FIELDS if not a.get(field) or a.get(field) != b.get(field)]
    if changed:
        raise PreflightError("non-probe input changed: " + ", ".join(changed))


def _fresh_lines(path, minimum):
    with _open_private_log(path) as source:
        # Line counts only; neither bytes nor excerpts are printed or copied into the manifest.
        return source.read(65_536).count(b"\n") >= minimum


def _fresh_android_event(path, since_epoch):
    with _open_private_log(path) as source:
        for line in source.read(65_536).splitlines():
            match = EPOCH_LOG_LINE.match(line)
            if match and int(match.group(1)) >= since_epoch:
                return True
    return False


def _open_private_log(path):
    flags = os.O_RDONLY
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        fd = os.open(path, flags)
    except OSError as error:
        raise PreflightError("private capture log is missing or unsafe") from error
    try:
        metadata = os.fstat(fd)
        if (not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.getuid() or
            stat.S_IMODE(metadata.st_mode) & 0o077):
            raise PreflightError("private capture log owner or permissions are unsafe")
        return os.fdopen(fd, "rb")
    except BaseException:
        os.close(fd)
        raise


class PostReadyEvidence:
    """Bounded, local-only event scan; no raw line or identifier enters the manifest."""

    MARKERS = (
        (b"iap2 mfi rx=0xaa05 authentication-succeeded", "AA05"),
        (b"airplay setup keys=", "AIRPLAY_SETUP"),
        (b"airplay datastream setup prepared", "AIRPLAY_SETUP"),
    )

    def __init__(self, android_log, iphone_log):
        self.paths = (android_log, iphone_log)
        self.offsets = []
        self.identities = []
        self.lines = [0, 0]
        self.scanned = [0, 0]
        self.marker = None
        self.android_tail = b""
        for path in self.paths:
            with _open_private_log(path) as source:
                metadata = os.fstat(source.fileno())
                self.offsets.append(metadata.st_size)
                self.identities.append((metadata.st_dev, metadata.st_ino))

    def observe(self):
        for index, path in enumerate(self.paths):
            with _open_private_log(path) as source:
                metadata = os.fstat(source.fileno())
                if (metadata.st_dev, metadata.st_ino) != self.identities[index] or metadata.st_size < self.offsets[index]:
                    raise PreflightError("a capture log was replaced or truncated after READY")
                if self.qualified:
                    continue
                if metadata.st_size - self.offsets[index] > MAX_POST_READY_BYTES - self.scanned[index]:
                    raise PreflightError("post-READY evidence exceeded its bounded scan")
                source.seek(self.offsets[index])
                remaining = metadata.st_size - self.offsets[index]
                while remaining:
                    chunk = source.read(min(65_536, remaining))
                    if not chunk:
                        raise PreflightError("capture log changed during the evidence scan")
                    remaining -= len(chunk)
                    self.offsets[index] += len(chunk)
                    self.scanned[index] += len(chunk)
                    self.lines[index] += chunk.count(b"\n")
                    if index == 0 and self.marker is None:
                        haystack = self.android_tail + chunk.lower()
                        for needle, name in self.MARKERS:
                            found = haystack.find(needle)
                            if found >= 0 and b"\n" in haystack[found + len(needle):]:
                                self.marker = name
                                break
                        self.android_tail = haystack[-128:]

    @property
    def qualified(self):
        return self.lines[0] > 0 and self.lines[1] > 0 and self.marker is not None

    def summary(self):
        return {
            "post_ready_android_lines": self.lines[0],
            "post_ready_iphone_lines": self.lines[1],
            "session_marker": self.marker,
        }


def _start_capture(command, stdout_path, stderr_path):
    for path in (stdout_path, stderr_path):
        _private_file(path, b"")
    with stdout_path.open("ab", buffering=0) as output, stderr_path.open("ab", buffering=0) as errors:
        return subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=output, stderr=errors)


def _await_ready(android, iphone, android_log, iphone_log, android_since_epoch, seconds):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if android.poll() is not None or iphone.poll() is not None:
            raise PreflightError("a capture process exited before fresh output")
        # A divider or replayed old line cannot satisfy this epoch-formatted event check.
        if _fresh_android_event(android_log, android_since_epoch) and _fresh_lines(iphone_log, 1):
            return
        time.sleep(0.1)
    raise PreflightError("both capture streams did not produce fresh output before the deadline")


def _stop_capture(process, term_seconds=2, kill_seconds=3):
    if process is None:
        return
    if process.poll() is not None:
        process.wait()
        return
    try:
        process.terminate()
    except ProcessLookupError:
        process.wait()
        return
    try:
        process.wait(timeout=term_seconds)
        return
    except subprocess.TimeoutExpired:
        pass
    # This Popen still owns the exact unreaped child PID. No broad pkill/process-name match.
    if process.poll() is None:
        try:
            process.kill()
        except ProcessLookupError:
            pass
    try:
        process.wait(timeout=kill_seconds)
    except subprocess.TimeoutExpired as error:
        raise PreflightError("an exact capture process could not be reaped") from error


def _interrupt_capture(signum, frame):
    raise KeyboardInterrupt


def _capture(args):
    if args.leg == "B" and args.baseline is None:
        raise PreflightError("B requires the completed A run directory")
    if args.leg == "A" and args.baseline is not None:
        raise PreflightError("A cannot use a baseline")
    baseline = None
    if args.baseline is not None:
        baseline_dir = args.baseline.resolve(strict=True)
        baseline = _read_manifest(baseline_dir)
        if baseline.get("leg") != "A" or baseline.get("status") != "complete":
            raise PreflightError("B requires a completed assetless A capture")
        if (not _valid_since_epoch(baseline.get("android_since_epoch")) or
            not _fresh_android_event(baseline_dir / "android.log", baseline["android_since_epoch"]) or
            not _fresh_lines(baseline_dir / "iphone.log", 1)):
            raise PreflightError("A capture lacks its dual-stream readiness evidence")
        key = _read_private(baseline_dir / "comparison-key.bin", 64)
        if len(key) != 32:
            raise PreflightError("A comparison key is invalid")
    else:
        key = secrets.token_bytes(32)
    android_id = _single_adb_device(args.adb)
    iphone_id = _single_iphone(args.idevice_id)
    snapshot = _snapshot(args.adb, android_id, iphone_id, args.leg, key)
    if baseline is not None:
        _compare_inputs(baseline, {"leg": "B", **snapshot})
    run_dir = args.run_dir
    if run_dir.exists() or run_dir.is_symlink():
        raise PreflightError("run directory must be a new path")
    run_dir.mkdir(mode=0o700, parents=False)
    os.chmod(run_dir, 0o700)
    if args.leg == "A":
        _private_file(run_dir / "comparison-key.bin", key)
    manifest = {"schema": SCHEMA, "leg": args.leg, "status": "starting", "started_utc": _utc_now(),
                **snapshot}
    _replace_manifest(run_dir, manifest)
    android = iphone = None
    evidence = None
    failure = None
    previous_term_handler = signal.signal(signal.SIGTERM, _interrupt_capture)
    try:
        android_since_epoch = _android_future_logcat_epoch(args.adb, android_id)
        manifest["android_since_epoch"] = android_since_epoch
        _replace_manifest(run_dir, manifest)
        android = _start_capture(
            [args.adb, "-s", android_id, "logcat", "-v", "epoch", "-T", f"{android_since_epoch}.000"],
            run_dir / "android.log", run_dir / "android.stderr",
        )
        manifest["android_capture_pid"] = android.pid
        _replace_manifest(run_dir, manifest)
        iphone = _start_capture(
            [args.idevicesyslog, "-u", iphone_id, "-x", "--no-colors"],
            run_dir / "iphone.log", run_dir / "iphone.stderr",
        )
        manifest["iphone_capture_pid"] = iphone.pid
        _replace_manifest(run_dir, manifest)
        _await_ready(android, iphone, run_dir / "android.log", run_dir / "iphone.log",
                     android_since_epoch, args.ready_seconds)
        evidence = PostReadyEvidence(run_dir / "android.log", run_dir / "iphone.log")
        manifest["status"] = "ready"
        manifest["ready_utc"] = _utc_now()
        _replace_manifest(run_dir, manifest)
        print("READY: both new raw captures are receiving output. Only now manually reconnect once.", flush=True)
        print("Press Ctrl-C after the session, or wait for the bounded capture timeout.", flush=True)
        deadline = time.monotonic() + args.capture_seconds
        while time.monotonic() < deadline:
            if android.poll() is not None or iphone.poll() is not None:
                raise PreflightError("a capture process exited during the session")
            evidence.observe()
            time.sleep(0.2)
    except KeyboardInterrupt:
        if manifest["status"] != "ready":
            failure = PreflightError("capture interrupted before READY")
    except Exception as error:
        failure = error
    finally:
        # A second Ctrl-C or SIGTERM must not interrupt exact-child retirement or the final
        # manifest publication. Each child is reaped, or its PID remains in an uncertain run.
        previous_int_handler = signal.signal(signal.SIGINT, signal.SIG_IGN)
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        try:
            uncertain_pids = []
            for process in (iphone, android):
                try:
                    _stop_capture(process)
                except (OSError, PreflightError) as error:
                    if process is not None:
                        uncertain_pids.append(process.pid)
                    failure = failure or PreflightError("capture process retirement is uncertain")
            if uncertain_pids:
                manifest["retirement_uncertain_pids"] = uncertain_pids
                manifest["status"] = "retirement-uncertain"
            elif failure is not None:
                manifest["status"] = "failed"
            elif evidence is None:
                failure = PreflightError("capture never reached READY")
                manifest["status"] = "failed"
            else:
                try:
                    evidence.observe()
                    if not evidence.qualified:
                        raise PreflightError(
                            "post-READY growth on both streams and an Android AA05/SETUP marker are required",
                        )
                    if (_single_adb_device(args.adb) != android_id or
                        _single_iphone(args.idevice_id) != iphone_id or
                        _snapshot(args.adb, android_id, iphone_id, args.leg, key) != snapshot):
                        raise PreflightError("device or signed bench inputs changed during capture")
                    manifest["status"] = "complete"
                except Exception as error:
                    failure = error
                    manifest["status"] = "inconclusive"
            if evidence is not None:
                manifest.update(evidence.summary())
            manifest["ended_utc"] = _utc_now()
            _replace_manifest(run_dir, manifest)
        finally:
            signal.signal(signal.SIGINT, previous_int_handler)
            signal.signal(signal.SIGTERM, previous_term_handler)
    if failure is not None:
        raise failure
    print("Capture stopped; raw logs remain private in the run directory.")


def _compare(args):
    a_dir = args.a.resolve(strict=True)
    b_dir = args.b.resolve(strict=True)
    a = _read_manifest(a_dir)
    b = _read_manifest(b_dir)
    if a.get("status") != "complete" or b.get("status") != "complete":
        raise PreflightError("both capture runs must be complete")
    for manifest in (a, b):
        if (manifest.get("post_ready_android_lines", 0) <= 0 or
            manifest.get("post_ready_iphone_lines", 0) <= 0 or
            manifest.get("session_marker") not in ("AA05", "AIRPLAY_SETUP")):
            raise PreflightError("a capture lacks post-READY session evidence")
    _compare_inputs(a, b)
    for root in (a_dir, b_dir):
        manifest = a if root == a_dir else b
        since_epoch = manifest.get("android_since_epoch")
        if (not _valid_since_epoch(since_epoch) or
            not _fresh_android_event(root / "android.log", since_epoch) or
            not _fresh_lines(root / "iphone.log", 1)):
            raise PreflightError("a capture log is missing its readiness evidence")
    print("Comparable tracked A/B inputs: only the asset-probe switch differed at preflight.")
    print("This does not prove the iPhone accepted an asset or entered CarPlay Ultra.")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    capture = sub.add_parser("capture", help="read-only preflight, then supervised capture")
    capture.add_argument("--leg", choices=("A", "B"), required=True)
    capture.add_argument("--run-dir", type=Path, required=True)
    capture.add_argument("--baseline", type=Path, help="completed A run directory for B")
    capture.add_argument("--ready-seconds", type=int, default=20)
    capture.add_argument("--capture-seconds", type=int, default=180)
    capture.add_argument("--adb", default="adb")
    capture.add_argument("--idevice-id", default="idevice_id")
    capture.add_argument("--idevicesyslog", default="idevicesyslog")
    compare = sub.add_parser("compare", help="compare completed A/B non-probe inputs")
    compare.add_argument("--a", type=Path, required=True)
    compare.add_argument("--b", type=Path, required=True)
    args = parser.parse_args(argv)
    if args.command == "capture" and (
        not CAPTURE_TIMEOUT_MIN <= args.capture_seconds <= CAPTURE_TIMEOUT_MAX or
        not 1 <= args.ready_seconds <= 120
    ):
        parser.error("capture timeout must be 10..600 s; readiness timeout must be 1..120 s")
    try:
        if args.command == "capture":
            _capture(args)
        else:
            _compare(args)
    except (OSError, UnicodeError, PreflightError) as error:
        # No captured stderr, XML, credentials, identifiers, or free-form exception text.
        if isinstance(error, PreflightError):
            print("Not ready: " + str(error), file=sys.stderr)
        else:
            print("Not ready: local capture operation failed (" + type(error).__name__ + ")", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
