from pathlib import Path
import signal
import stat
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import ultra_ab_capture as capture


GENERATION = "11111111-2222-3333-4444-555555555555"


def prefs_xml(entries):
    rows = ['<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map>']
    for name, kind, value in entries:
        if kind == "string":
            rows.append(f'<string name="{name}">{value}</string>')
        else:
            rows.append(f'<{kind} name="{name}" value="{value}" />')
    rows.append("</map>")
    return "".join(rows).encode()


def airplay_xml(probe):
    return prefs_xml([
        ("carplay_ultra_enabled", "boolean", "true"),
        ("physical_display_panels_probe_enabled", "boolean", "true"),
        ("cluster_asset_catalog_probe_enabled", "boolean", str(probe).lower()),
        ("cluster_asset_catalog_probe_certificate_type", "string", "BAA"),
        ("cluster_asset_catalog_probe_identity_sha256", "string", "ab" * 32),
        ("cluster_asset_catalog_probe_asset_id", "string", "bench.asset"),
        ("cluster_asset_catalog_probe_sdk_version", "string", "27.0"),
        ("web_display_110_width", "int", "1280"),
        ("manual_hotspot_passphrase", "string", "private-fixture-passphrase"),
        ("unrelated_secret_field", "string", "never-read-this"),
    ])


BENCH_XML = prefs_xml([
    ("caf-only-explicit-opt-in", "boolean", "true"),
    ("signed-physical-uisync-explicit-opt-in", "boolean", "true"),
])


def snapshot(leg, *, profile=b"same-archive", width=1280):
    files = {
        capture.AIRPLAY_PREFS: airplay_xml(leg == "B").replace(b'width" value="1280"',
                                                               f'width" value="{width}"'.encode()),
        capture.BENCH_PREFS: BENCH_XML,
        capture.IMPORT_ROOT + "/active": (GENERATION + "\n").encode(),
        capture.IMPORT_ROOT + "/profile-" + GENERATION + "/profile.zip": profile,
        capture.IMPORT_ROOT + "/profile-" + GENERATION + "/p256-pin.der": b"public-pin",
        capture.IMPORT_ROOT + "/profile-" + GENERATION + "/signature.der": b"signature",
        capture.IMPORT_ROOT + "/profile-" + GENERATION + "/vehicle-id.txt": b"bench-vehicle",
    }
    with patch.object(capture, "_app_file", side_effect=lambda adb, serial, path, maximum: files[path]), \
            patch.object(capture, "_installed_apks", return_value=[
                {"name": "base.apk", "sha256": "11" * 32, "bytes": 123},
                {"name": "split_config.apk", "sha256": "22" * 32, "bytes": 45},
            ]):
        return capture._snapshot("fake-adb", "fake-android", "fake-iphone", leg, b"k" * 32)


class UltraAbCaptureTest(unittest.TestCase):
    def test_preflight_accepts_one_device_and_rejects_two_or_unauthorized(self):
        with patch.object(capture, "_command_output", side_effect=[
            b"List of devices attached\nFAKE\tdevice\n", b"FAKEPHONE\n",
        ]):
            self.assertEqual("FAKE", capture._single_adb_device("fake-adb"))
            self.assertEqual("FAKEPHONE", capture._single_iphone("fake-idevice-id"))
        for rows in (
            b"List of devices attached\nA\tdevice\nB\tdevice\n",
            b"List of devices attached\nA\tunauthorized\n",
        ):
            with patch.object(capture, "_command_output", return_value=rows):
                with self.assertRaises(capture.PreflightError):
                    capture._single_adb_device("fake-adb")
        with patch.object(capture, "_command_output", return_value=b"A\nB\n"):
            with self.assertRaises(capture.PreflightError):
                capture._single_iphone("fake-idevice-id")

    def test_future_epoch_disambiguates_logcat_tail_and_rejects_invalid_clock(self):
        with patch.object(capture, "_command_output", return_value=b"2000000000\n"):
            self.assertEqual(2_000_000_003, capture._android_future_logcat_epoch("adb", "device"))
        with patch.object(capture, "_command_output", return_value=b"unknown\n"):
            with self.assertRaisesRegex(capture.PreflightError, "wall-clock"):
                capture._android_future_logcat_epoch("adb", "device")

    def test_allowlist_ignores_unknown_xml_and_hmacs_approved_secret(self):
        values = capture._parse_preferences(airplay_xml(False), capture.AIRPLAY_KEYS, b"k" * 32)
        self.assertNotIn("unrelated_secret_field", values)
        self.assertEqual(["boolean", "false"], values[capture.PROBE_KEY])
        self.assertEqual("hmac-sha256", values["manual_hotspot_passphrase"][0])
        self.assertNotIn("private-fixture-passphrase", repr(values))
        self.assertNotIn("never-read-this", repr(values))

    def test_snapshot_compares_same_profile_and_only_probe_delta(self):
        a = {"leg": "A", **snapshot("A")}
        b = {"leg": "B", **snapshot("B")}
        capture._compare_inputs(a, b)
        self.assertNotIn("private-fixture-passphrase", repr(a))
        self.assertNotIn("never-read-this", repr(a))
        self.assertEqual("ab" * 32, a["mfi_identity_pin"])

    def test_non_probe_change_or_reversed_order_fails_closed(self):
        a = {"leg": "A", **snapshot("A")}
        for b in (
            {"leg": "B", **snapshot("B", profile=b"changed")},
            {"leg": "B", **snapshot("B", width=1920)},
            {"leg": "B", **snapshot("B"), "installed_apks": [
                {"name": "base.apk", "sha256": "99" * 32, "bytes": 123},
            ]},
        ):
            with self.assertRaisesRegex(capture.PreflightError, "non-probe input changed"):
                capture._compare_inputs(a, b)
        with self.assertRaisesRegex(capture.PreflightError, "A must precede"):
            capture._compare_inputs({"leg": "B", **snapshot("B")}, a)

    def test_unready_probe_state_and_missing_required_option_fail(self):
        with self.assertRaisesRegex(capture.PreflightError, "probe state"):
            self._snapshot_with_wrong_leg()
        files = {capture.AIRPLAY_PREFS: airplay_xml(False), capture.BENCH_PREFS: b"<map />"}
        with patch.object(capture, "_app_file", side_effect=lambda adb, serial, path, maximum: files[path]):
            with self.assertRaisesRegex(capture.PreflightError, "bench setting"):
                with patch.object(capture, "_installed_apks", return_value=[]):
                    capture._snapshot("a", "b", "c", "A", b"k" * 32)

    def _snapshot_with_wrong_leg(self):
        files = {capture.AIRPLAY_PREFS: airplay_xml(False), capture.BENCH_PREFS: BENCH_XML}
        with patch.object(capture, "_app_file", side_effect=lambda adb, serial, path, maximum: files[path]), \
                patch.object(capture, "_installed_apks", return_value=[]):
            capture._snapshot("a", "b", "c", "B", b"k" * 32)

    def test_snapshot_refuses_a_racing_signature_replacement(self):
        base = capture.IMPORT_ROOT + "/profile-" + GENERATION + "/"
        files = {
            capture.AIRPLAY_PREFS: airplay_xml(False), capture.BENCH_PREFS: BENCH_XML,
            capture.IMPORT_ROOT + "/active": (GENERATION + "\n").encode(),
            base + "profile.zip": b"profile", base + "p256-pin.der": b"pin",
            base + "signature.der": b"old", base + "vehicle-id.txt": b"vehicle",
        }
        reads = {base + "signature.der": 0}

        def app_file(adb, serial, path, maximum):
            if path == base + "signature.der":
                reads[path] += 1
                return b"new" if reads[path] == 2 else b"old"
            return files[path]

        with patch.object(capture, "_app_file", side_effect=app_file), \
                patch.object(capture, "_installed_apks", return_value=[]):
            with self.assertRaisesRegex(capture.PreflightError, "changed during"):
                capture._snapshot("a", "b", "c", "A", b"k" * 32)

    def test_failed_readiness_retires_both_fake_captures_and_marks_run_failed(self):
        class FakeProcess:
            def __init__(self, pid):
                self.pid = pid
                self.stopped = False

            def poll(self):
                return 0 if self.stopped else None

            def terminate(self):
                self.stopped = True

            def wait(self, timeout):
                return 0

        android, iphone = FakeProcess(1234), FakeProcess(1235)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "run"
            args = SimpleNamespace(
                leg="A", baseline=None, run_dir=root, adb="fake-adb",
                idevice_id="fake-idevice-id", idevicesyslog="fake-idevicesyslog",
                ready_seconds=1, capture_seconds=10,
            )
            with patch.object(capture, "_single_adb_device", return_value="fake-android"), \
                    patch.object(capture, "_single_iphone", return_value="fake-iphone"), \
                    patch.object(capture, "_snapshot", return_value=snapshot("A")), \
                    patch.object(capture, "_android_future_logcat_epoch", return_value=2_000_000_001), \
                    patch.object(capture, "_start_capture", side_effect=[android, iphone]), \
                    patch.object(capture, "_await_ready", side_effect=capture.PreflightError("no fresh output")):
                with self.assertRaisesRegex(capture.PreflightError, "no fresh output"):
                    capture._capture(args)
            self.assertTrue(android.stopped)
            self.assertTrue(iphone.stopped)
            self.assertEqual("failed", capture._read_manifest(root)["status"])
            self.assertEqual(0o700, stat.S_IMODE(root.stat().st_mode))
            self.assertEqual(0o600, stat.S_IMODE((root / "manifest.json").stat().st_mode))
            self.assertEqual(0o600, stat.S_IMODE((root / "comparison-key.bin").stat().st_mode))

    def test_immediate_timeout_after_ready_remains_inconclusive_without_new_session_evidence(self):
        class FakeProcess:
            def __init__(self, pid):
                self.pid, self.stopped = pid, False

            def poll(self):
                return 0 if self.stopped else None

            def terminate(self):
                self.stopped = True

            def wait(self, timeout=None):
                return 0

        class NoEvidence:
            qualified = False

            def observe(self):
                pass

            def summary(self):
                return {"post_ready_android_lines": 0, "post_ready_iphone_lines": 0,
                        "session_marker": None}

        with tempfile.TemporaryDirectory() as temp:
            run_dir = Path(temp) / "run"
            args = SimpleNamespace(
                leg="A", baseline=None, run_dir=run_dir, adb="fake-adb",
                idevice_id="fake-idevice-id", idevicesyslog="fake-idevicesyslog",
                ready_seconds=1, capture_seconds=0,
            )
            android, iphone = FakeProcess(1234), FakeProcess(1235)
            with patch.object(capture, "_single_adb_device", return_value="fake-android"), \
                    patch.object(capture, "_single_iphone", return_value="fake-iphone"), \
                    patch.object(capture, "_snapshot", return_value=snapshot("A")), \
                    patch.object(capture, "_android_future_logcat_epoch", return_value=2_000_000_001), \
                    patch.object(capture, "_start_capture", side_effect=[android, iphone]), \
                    patch.object(capture, "_await_ready"), \
                    patch.object(capture, "PostReadyEvidence", return_value=NoEvidence()):
                with self.assertRaisesRegex(capture.PreflightError, "post-READY growth"):
                    capture._capture(args)
            self.assertEqual("inconclusive", capture._read_manifest(run_dir)["status"])
            self.assertTrue(android.stopped and iphone.stopped)

    def test_end_snapshot_detects_mid_run_apk_change(self):
        class FakeProcess:
            def __init__(self, pid):
                self.pid, self.stopped = pid, False

            def poll(self):
                return 0 if self.stopped else None

            def terminate(self):
                self.stopped = True

            def wait(self, timeout=None):
                return 0

        class Evidence:
            qualified = True

            def observe(self):
                pass

            def summary(self):
                return {"post_ready_android_lines": 1, "post_ready_iphone_lines": 1,
                        "session_marker": "AA05"}

        with tempfile.TemporaryDirectory() as temp:
            run_dir = Path(temp) / "run"
            args = SimpleNamespace(
                leg="A", baseline=None, run_dir=run_dir, adb="fake-adb",
                idevice_id="fake-idevice-id", idevicesyslog="fake-idevicesyslog",
                ready_seconds=1, capture_seconds=0,
            )
            before = snapshot("A")
            after = {**before, "installed_apks": [{"name": "base.apk", "sha256": "99" * 32, "bytes": 123}]}
            with patch.object(capture, "_single_adb_device", return_value="fake-android"), \
                    patch.object(capture, "_single_iphone", return_value="fake-iphone"), \
                    patch.object(capture, "_snapshot", side_effect=[before, after]), \
                    patch.object(capture, "_android_future_logcat_epoch", return_value=2_000_000_001), \
                    patch.object(capture, "_start_capture", side_effect=[FakeProcess(1234), FakeProcess(1235)]), \
                    patch.object(capture, "_await_ready"), \
                    patch.object(capture, "PostReadyEvidence", return_value=Evidence()):
                with self.assertRaisesRegex(capture.PreflightError, "changed during capture"):
                    capture._capture(args)
            self.assertEqual("inconclusive", capture._read_manifest(run_dir)["status"])

    def test_only_qualified_capture_with_same_end_snapshot_completes(self):
        class FakeProcess:
            def __init__(self, pid):
                self.pid, self.stopped = pid, False

            def poll(self):
                return 0 if self.stopped else None

            def terminate(self):
                self.stopped = True

            def wait(self, timeout=None):
                return 0

        class Evidence:
            qualified = True

            def observe(self):
                pass

            def summary(self):
                return {"post_ready_android_lines": 1, "post_ready_iphone_lines": 1,
                        "session_marker": "AIRPLAY_SETUP"}

        with tempfile.TemporaryDirectory() as temp:
            run_dir = Path(temp) / "run"
            args = SimpleNamespace(
                leg="A", baseline=None, run_dir=run_dir, adb="fake-adb",
                idevice_id="fake-idevice-id", idevicesyslog="fake-idevicesyslog",
                ready_seconds=1, capture_seconds=0,
            )
            state = snapshot("A")
            android, iphone = FakeProcess(1234), FakeProcess(1235)
            with patch.object(capture, "_single_adb_device", return_value="fake-android"), \
                    patch.object(capture, "_single_iphone", return_value="fake-iphone"), \
                    patch.object(capture, "_snapshot", return_value=state), \
                    patch.object(capture, "_android_future_logcat_epoch", return_value=2_000_000_001), \
                    patch.object(capture, "_start_capture", side_effect=[android, iphone]), \
                    patch.object(capture, "_await_ready"), \
                    patch.object(capture, "PostReadyEvidence", return_value=Evidence()):
                capture._capture(args)
            manifest = capture._read_manifest(run_dir)
            self.assertEqual("complete", manifest["status"])
            self.assertEqual("AIRPLAY_SETUP", manifest["session_marker"])
            self.assertTrue(android.stopped and iphone.stopped)

    def test_fake_capture_processes_prove_new_lines_and_stop(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            android = capture._start_capture(
                [sys.executable, "-u", "-c", "import time;print('2000000001.100 I fake: new');time.sleep(5)"],
                root / "android.log", root / "android.stderr",
            )
            iphone = capture._start_capture(
                [sys.executable, "-u", "-c", "import time;print('live');time.sleep(5)"],
                root / "iphone.log", root / "iphone.stderr",
            )
            try:
                capture._await_ready(android, iphone, root / "android.log", root / "iphone.log",
                                     2_000_000_001, 2)
                for name in ("android.log", "android.stderr", "iphone.log", "iphone.stderr"):
                    self.assertEqual(0o600, stat.S_IMODE((root / name).stat().st_mode))
            finally:
                capture._stop_capture(iphone)
                capture._stop_capture(android)
            self.assertIsNotNone(android.poll())
            self.assertIsNotNone(iphone.poll())

    def test_ignored_term_is_killed_and_exact_child_reaped(self):
        process = subprocess.Popen(
            [sys.executable, "-u", "-c",
             "import signal,time;signal.signal(signal.SIGTERM,signal.SIG_IGN);"
             "print('ready',flush=True);time.sleep(30)"],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        )
        try:
            self.assertEqual(b"ready\n", process.stdout.readline())
            capture._stop_capture(process, term_seconds=0.1, kill_seconds=2)
            self.assertIsNotNone(process.poll())
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=2)
            process.stdout.close()

    def test_post_ready_requires_both_growth_and_new_session_marker(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            android_log, iphone_log = root / "android.log", root / "iphone.log"
            capture._private_file(android_log, b"old iap2 mfi rx=0xaa05 authentication-succeeded\nold\n")
            capture._private_file(iphone_log, b"old\n")
            evidence = capture.PostReadyEvidence(android_log, iphone_log)
            evidence.observe()
            self.assertFalse(evidence.qualified)
            with android_log.open("ab") as output:
                output.write(b"unrelated post-ready line\n")
            evidence.observe()
            self.assertFalse(evidence.qualified)
            with iphone_log.open("ab") as output:
                output.write(b"new phone line\n")
            evidence.observe()
            self.assertFalse(evidence.qualified)
            with android_log.open("ab") as output:
                output.write(b"xcertplay: iap2 mfi rx=0xaa05 authentication-succeeded\n")
            evidence.observe()
            self.assertTrue(evidence.qualified)
            self.assertEqual("AA05", evidence.marker)
            self.assertEqual(2, evidence.lines[0])
            self.assertEqual(1, evidence.lines[1])

    def test_private_log_reader_rejects_symlink_and_insecure_mode(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            real = root / "android.log"
            capture._private_file(real, b"a\nb\n")
            alias = root / "alias.log"
            alias.symlink_to(real)
            with self.assertRaises(capture.PreflightError):
                capture._fresh_lines(alias, 1)
            with patch.object(capture.os, "getuid", return_value=capture.os.getuid() + 1):
                with self.assertRaisesRegex(capture.PreflightError, "owner"):
                    capture._fresh_lines(real, 1)
            real.chmod(0o644)
            with self.assertRaisesRegex(capture.PreflightError, "permissions"):
                capture._fresh_lines(real, 1)

    def test_bounded_preflight_command_refuses_oversized_output(self):
        with self.assertRaisesRegex(capture.PreflightError, "exceeded"):
            capture._command_output(
                [sys.executable, "-c", "import sys;sys.stdout.buffer.write(b'x'*1024)"],
                maximum=64, timeout=2,
            )

    def test_installed_apk_inventory_hashes_base_and_all_splits(self):
        paths = (
            b"package:/data/app/~~abc/com.shilapi.xcertplay-abc/base.apk\n"
            b"package:/data/app/~~abc/com.shilapi.xcertplay-abc/split_config.arm64.apk\n"
        )
        with patch.object(capture, "_command_output", side_effect=[paths, paths]), \
                patch.object(capture, "_bounded_command", side_effect=[("11" * 32, 123), ("22" * 32, 45)]) as hashed:
            entries = capture._installed_apks("fake-adb", "fake-device")
        self.assertEqual(["base.apk", "split_config.arm64.apk"], [entry["name"] for entry in entries])
        self.assertEqual([123, 45], [entry["bytes"] for entry in entries])
        self.assertEqual(2, hashed.call_count)
        self.assertTrue(all(call.kwargs.get("digest_only") for call in hashed.call_args_list))
        with patch.object(capture, "_command_output", return_value=b"package:/data/app/../../bad.apk\n"):
            with self.assertRaisesRegex(capture.PreflightError, "path inventory"):
                capture._installed_apks("fake-adb", "fake-device")

    def test_stale_or_dead_fake_capture_never_reports_ready(self):
        class FakeProcess:
            def poll(self):
                return None
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            capture._private_file(root / "android.log", b"2000000000.100 I fake: old\n--------- beginning of main\n")
            capture._private_file(root / "iphone.log", b"live-line\n")
            with self.assertRaisesRegex(capture.PreflightError, "fresh output"):
                capture._await_ready(FakeProcess(), FakeProcess(), root / "android.log", root / "iphone.log",
                                     2_000_000_001, 0.15)

    def test_compare_requires_complete_captures_and_matching_inputs(self):
        with tempfile.TemporaryDirectory() as temp:
            a_dir, b_dir = Path(temp) / "a", Path(temp) / "b"
            a_dir.mkdir(mode=0o700)
            b_dir.mkdir(mode=0o700)
            evidence = {"post_ready_android_lines": 1, "post_ready_iphone_lines": 1,
                        "session_marker": "AA05", "android_since_epoch": 2_000_000_001}
            a = {"schema": capture.SCHEMA, "leg": "A", "status": "complete", **snapshot("A"), **evidence}
            b = {"schema": capture.SCHEMA, "leg": "B", "status": "complete", **snapshot("B"), **evidence}
            capture._replace_manifest(a_dir, a)
            capture._replace_manifest(b_dir, b)
            for directory in (a_dir, b_dir):
                capture._private_file(directory / "android.log", b"2000000001.100 I fake: new\n")
                capture._private_file(directory / "iphone.log", b"live\n")
            args = type("Args", (), {"a": a_dir, "b": b_dir})()
            capture._compare(args)
            b["status"] = "failed"
            capture._replace_manifest(b_dir, b)
            with self.assertRaisesRegex(capture.PreflightError, "must be complete"):
                capture._compare(args)


if __name__ == "__main__":
    unittest.main()
