import hashlib
import io
import json
import plistlib
from pathlib import Path
import tempfile
import unittest
import zipfile

from create_debug_bench_profile import (
    BENCH_ASSET_IDENTIFIER, CAF_PATH, MAIN_CANVAS_CONTENT_ID,
    MAIN_CAROUSEL_ZONE_ID, MAIN_SCREEN_UUID, PHONE_BENCH_LAYOUT_STYLE_ID,
    PHONE_BENCH_PALETTE_ID, UI_SYNC_PATH, UI_SYNC_V2_PATH, UI_SYNC_V3_PATH,
    build_profile, main,
)


class DebugBenchProfileGeneratorTest(unittest.TestCase):
    def test_is_deterministic_and_has_exact_bounded_caf_only_shape(self):
        first = build_profile()
        self.assertEqual(first, build_profile())
        self.assertLess(len(first), 32 * 1024 * 1024)

        with zipfile.ZipFile(io.BytesIO(first)) as archive:
            self.assertIsNone(archive.testzip())
            expected = {
                "CarPlayPhone.assets/",
                "CarPlayPhone.assets/package.json",
                "CarPlayPhone.assets/theme.json",
                "CarPlayPhone.assets/Info.plist",
                CAF_PATH,
                "CarPlayAccessory.assets.zip",
                "Certificate.chain.pem",
                "CarPlayAccessory.assets.sha256",
                "AccessoryAdditions",
                "Info.plist",
            }
            self.assertEqual(expected, set(archive.namelist()))
            self.assertEqual(len(expected), len(archive.namelist()))
            self.assertTrue(all(entry.compress_type == zipfile.ZIP_STORED
                                and entry.date_time == (1980, 1, 1, 0, 0, 0)
                                and not entry.extra and not entry.comment
                                for entry in archive.infolist()))
            self.assertTrue(all((entry.external_attr >> 16) & 0o170000 ==
                                (0o40000 if entry.is_dir() else 0o100000)
                                for entry in archive.infolist()))

            manifest = plistlib.loads(archive.read("Info.plist"))
            self.assertEqual("bench.asset", manifest["AssetIdentifier"])
            self.assertEqual("debug-ios-1", manifest["iOSContentVersion"])
            self.assertEqual("debug-accessory-1", manifest["AccessoryContentVersion"])
            self.assertEqual("DEBUG.BENCH", plistlib.loads(
                archive.read("CarPlayPhone.assets/Info.plist"))["Bundle"])
            self.assertEqual({"Bench": True}, plistlib.loads(archive.read("AccessoryAdditions")))
            accessory = archive.read("CarPlayAccessory.assets.zip")
            self.assertEqual(hashlib.sha256(accessory).hexdigest(),
                             archive.read("CarPlayAccessory.assets.sha256").decode("ascii"))
            with zipfile.ZipFile(io.BytesIO(accessory)) as inner:
                self.assertEqual(["BenchAccessory/theme.json"], inner.namelist())
                self.assertEqual(b"{}", inner.read("BenchAccessory/theme.json"))

            spec = json.loads(archive.read(CAF_PATH))
            self.assertEqual("xcertplay-debug-caf-v1", spec["format"])
            self.assertEqual("synthetic", spec["source"])
            self.assertEqual(0, spec["pluginID"])
            self.assertEqual("1.0", spec["protocolVersion"])
            characteristic_rows = spec["configTree"]["accessories"][0]["services"][0][
                "characteristics"]
            self.assertTrue(all("units" not in row for row in characteristic_rows))
            self.assertEqual({0, 1}, {row["priority"] for row in characteristic_rows})
            self.assertEqual({3, 4}, {row["iid"] for row in characteristic_rows})
            self.assertEqual({3, 4}, {row["iid"] for row in spec["bindings"]})
            self.assertEqual({3, 4}, {row["iid"] for row in spec["syntheticValues"]})
            self.assertEqual(4, len(set(spec["errors"].values())))
            self.assertFalse(any(name.endswith("-uisync-v1.json") or
                                 name.endswith("-uisync-v2.json") or
                                 name.endswith("-uisync-v3.json")
                                 for name in archive.namelist()))

    def test_default_and_v1_v2_v3_profiles_remain_byte_stable(self):
        self.assertEqual("c1af32df3e407d6c226c3e1573424e2eff9de367f294692bdfabd8ca588c199b",
                         hashlib.sha256(build_profile()).hexdigest())
        self.assertEqual("a75f406e9785a5441ddf90e1c9d04b4ebd6e24892304354c160be126138ad002",
                         hashlib.sha256(build_profile(main_uisync=True)).hexdigest())
        self.assertEqual("722bba11ff61a023277e06555d416bf9406d983b883690b5d671bb17e7ed1c36",
                         hashlib.sha256(build_profile(main_local_canvas=True)).hexdigest())
        self.assertEqual("1d6e29ff8c54cca07c8503599484d36be3b6426782d5c75b99e31b10749dabc5",
                         hashlib.sha256(build_profile(main_carousel_canvas=True)).hexdigest())

    def test_opt_in_phone_override_resolved_theme_has_strict_inferred_shape(self):
        baseline = build_profile(main_carousel_canvas=True)
        candidate = build_profile(main_carousel_canvas=True,
                                  phone_override_metadata_probe=True)
        self.assertEqual(candidate, build_profile(main_carousel_canvas=True,
                                                  phone_override_metadata_probe=True))
        self.assertNotEqual(baseline, candidate)
        with zipfile.ZipFile(io.BytesIO(candidate)) as archive:
            self.assertIsNone(archive.testzip())
            metadata = plistlib.loads(archive.read("Info.plist"))
            self.assertEqual({"AssetIdentifier", "iOSContentVersion",
                              "AccessoryContentVersion", "CFBundleVersion",
                              "MinimumCompatibilityVersion", "MinimumSDKVersion"},
                             set(metadata))
            self.assertEqual(BENCH_ASSET_IDENTIFIER, metadata["AssetIdentifier"])
            self.assertEqual("1", metadata["iOSContentVersion"])
            self.assertIs(type(metadata["AccessoryContentVersion"]), int)
            self.assertEqual(1, metadata["AccessoryContentVersion"])
            self.assertIs(type(metadata["CFBundleVersion"]), str)
            self.assertEqual("1", metadata["CFBundleVersion"])
            self.assertIs(type(metadata["MinimumCompatibilityVersion"]), int)
            self.assertIs(type(metadata["MinimumSDKVersion"]), str)

            package = json.loads(archive.read("CarPlayPhone.assets/package.json"))
            self.assertIs(type(package["version"]), int)
            self.assertEqual(metadata["AccessoryContentVersion"], package["version"])
            self.assertEqual(metadata["CFBundleVersion"], str(package["version"]))
            self.assertEqual(metadata["iOSContentVersion"], str(package["version"]))
            self.assertEqual(metadata["AssetIdentifier"], package["identifier"])
            self.assertEqual(metadata["MinimumSDKVersion"], package["minimumSDKVersion"])
            self.assertEqual(metadata["MinimumCompatibilityVersion"], package["version"])
            self.assertEqual(PHONE_BENCH_LAYOUT_STYLE_ID,
                             package["displays"][0]["layouts"][0]["layoutStyleID"])
            phone_info = plistlib.loads(archive.read("CarPlayPhone.assets/Info.plist"))
            self.assertEqual(str(package["version"]), phone_info["Version"])

            # These are the four non-optional ResolvedTheme root dictionaries in
            # iOS 27's DocumentReader. The nested ColorID dictionary may be empty;
            # this is a static decode candidate, not a demonstrated phone install.
            resolved = json.loads(archive.read("CarPlayPhone.assets/theme.json"))
            self.assertIs(type(resolved), dict)
            self.assertEqual({"palettes", "layoutStyles", "defaultPaletteIDs",
                              "defaultWallpaperIDs"}, set(resolved))
            for field in resolved.values():
                self.assertIs(type(field), dict)
            self.assertEqual({PHONE_BENCH_PALETTE_ID}, set(resolved["palettes"]))
            palette = resolved["palettes"][PHONE_BENCH_PALETTE_ID]
            self.assertIs(type(palette), dict)
            self.assertEqual({"id", "displayName", "colors", "representedColorScheme",
                              "sortIndex", "prominent"}, set(palette))
            self.assertEqual(PHONE_BENCH_PALETTE_ID, palette["id"])
            self.assertEqual("Bench", palette["displayName"])
            self.assertEqual({}, palette["colors"])
            self.assertEqual({"single": {"color": "#101820"}},
                             palette["representedColorScheme"])
            self.assertIs(type(palette["sortIndex"]), int)
            self.assertEqual(0, palette["sortIndex"])
            self.assertIs(type(palette["prominent"]), bool)
            self.assertFalse(palette["prominent"])
            self.assertEqual({
                PHONE_BENCH_LAYOUT_STYLE_ID: {PHONE_BENCH_PALETTE_ID: {}},
            }, resolved["layoutStyles"])
            self.assertEqual({PHONE_BENCH_LAYOUT_STYLE_ID: PHONE_BENCH_PALETTE_ID},
                             resolved["defaultPaletteIDs"])
            self.assertEqual({}, resolved["defaultWallpaperIDs"])
            self.assertIn(UI_SYNC_V3_PATH, archive.namelist())
            with zipfile.ZipFile(io.BytesIO(baseline)) as old_archive:
                self.assertEqual(set(old_archive.namelist()), set(archive.namelist()))
                changed = {"Info.plist", "CarPlayPhone.assets/package.json",
                           "CarPlayPhone.assets/theme.json"}
                for name in archive.namelist():
                    if name not in changed:
                        self.assertEqual(old_archive.read(name), archive.read(name), name)
                old_package = json.loads(old_archive.read("CarPlayPhone.assets/package.json"))
                self.assertEqual("bench", old_package["identifier"])
                self.assertNotIn("layoutStyleID",
                                 old_package["displays"][0]["layouts"][0])
                self.assertEqual(b"{}", old_archive.read("CarPlayPhone.assets/theme.json"))
        with zipfile.ZipFile(io.BytesIO(baseline)) as archive:
            old = plistlib.loads(archive.read("Info.plist"))
        self.assertEqual("debug-accessory-1", old["AccessoryContentVersion"])
        self.assertEqual("debug-ios-1", old["iOSContentVersion"])
        self.assertNotIn("CFBundleVersion", old)

    def test_opt_in_phone_resolved_theme_binds_each_main_catalog_to_same_style(self):
        modes = [
            {"main_uisync": True},
            {"main_local_canvas": True},
            {"main_carousel_canvas": True},
        ]
        themes = []
        for mode in modes:
            with self.subTest(mode=mode), zipfile.ZipFile(io.BytesIO(build_profile(
                    **mode, phone_override_metadata_probe=True))) as archive:
                package = json.loads(archive.read("CarPlayPhone.assets/package.json"))
                self.assertEqual("centerConsole", package["displays"][0]["type"])
                self.assertEqual(PHONE_BENCH_LAYOUT_STYLE_ID,
                                 package["displays"][0]["layouts"][0]["layoutStyleID"])
                self.assertEqual(BENCH_ASSET_IDENTIFIER, package["identifier"])
                themes.append(archive.read("CarPlayPhone.assets/theme.json"))
                with zipfile.ZipFile(io.BytesIO(build_profile(**mode))) as baseline:
                    self.assertEqual(baseline.read(CAF_PATH), archive.read(CAF_PATH))
        self.assertEqual([themes[0]] * len(themes), themes)

    def test_phone_override_metadata_probe_never_bundles_synthetic_speed(self):
        with self.assertRaises(ValueError):
            build_profile(phone_override_metadata_probe=True)
        with self.assertRaises(ValueError):
            build_profile(main_uisync=True, vehicle_motion_probe=True,
                          phone_override_metadata_probe=True)
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "phone-metadata-probe.zip"
            self.assertEqual(0, main([str(output), "--main-carousel-canvas",
                                      "--phone-override-metadata-probe"]))
            self.assertEqual(build_profile(main_carousel_canvas=True,
                                           phone_override_metadata_probe=True),
                             output.read_bytes())

    def test_cli_requires_explicit_new_file_and_never_overwrites(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "profile.zip"
            self.assertEqual(0, main([str(output)]))
            original = output.read_bytes()
            self.assertEqual(build_profile(), original)
            self.assertEqual(1, main([str(output)]))
            self.assertEqual(original, output.read_bytes())

    def test_nonzero_plugin_routing_probe_changes_only_signed_caf_spec(self):
        with zipfile.ZipFile(io.BytesIO(build_profile(plugin_id=7))) as archive:
            spec = json.loads(archive.read(CAF_PATH))
        self.assertEqual(7, spec["pluginID"])
        self.assertEqual(7, spec["pluginConfig"]["pluginID"])
        self.assertNotEqual(build_profile(), build_profile(plugin_id=7))
        with self.assertRaises(ValueError):
            build_profile(plugin_id=-1)

    def test_optional_main_uisync_has_full_screen_type_110_video_and_exact_ddp_id(self):
        candidate = build_profile(main_uisync=True)
        self.assertEqual(candidate, build_profile(main_uisync=True))
        with zipfile.ZipFile(io.BytesIO(candidate)) as archive:
            self.assertIsNone(archive.testzip())
            self.assertIn(CAF_PATH, archive.namelist())
            self.assertIn(UI_SYNC_PATH, archive.namelist())
            package = json.loads(archive.read("CarPlayPhone.assets/package.json"))
            display = package["displays"][0]
            self.assertEqual("centerConsole", display["type"])
            slot = display["layouts"][0]["zones"][0]["components"][0]["slots"][0]
            self.assertEqual([[0, 0], [800, 480]], slot["frame"])
            self.assertEqual("video.main", slot["instrument"]["kind"]["inputStream"]["_0"])
            spec = json.loads(archive.read(UI_SYNC_PATH))
            row = spec["displays"][0]
            self.assertEqual(MAIN_SCREEN_UUID, row["advertisedScreenId"])
            self.assertEqual(MAIN_SCREEN_UUID, row["topologyDisplayId"])
            self.assertEqual("xcertplay-main", row["uiSyncDisplayId"])
            self.assertEqual("MAIN", row["destinationRole"])
            self.assertEqual("ingress.main", row["layouts"][0]["slotIngresses"][0]["ingressId"])

    def test_opt_in_main_local_canvas_v2_is_an_empty_full_screen_slot(self):
        candidate = build_profile(main_local_canvas=True)
        self.assertEqual(candidate, build_profile(main_local_canvas=True))
        self.assertNotEqual(build_profile(), candidate)
        with zipfile.ZipFile(io.BytesIO(candidate)) as archive:
            self.assertIsNone(archive.testzip())
            self.assertIn(CAF_PATH, archive.namelist())
            self.assertIn(UI_SYNC_V2_PATH, archive.namelist())
            self.assertNotIn(UI_SYNC_PATH, archive.namelist())
            package = json.loads(archive.read("CarPlayPhone.assets/package.json"))
            display = package["displays"][0]
            self.assertEqual("main", display["id"])
            self.assertEqual("centerConsole", display["type"])
            self.assertEqual([800, 480], display["size"])
            layout = display["layouts"][0]
            self.assertEqual("main-local-canvas", layout["id"])
            self.assertEqual("#101820", layout["backgroundColor"])
            self.assertEqual([[0, 0], [800, 480]], layout["zones"][0]["frame"])
            component = layout["zones"][0]["components"][0]
            self.assertEqual([800, 480], component["size"])
            slot = component["slots"][0]
            self.assertEqual({"style", "frame", "zIndex", "positionId"}, set(slot))
            self.assertEqual({"rectangular": {}}, slot["style"])
            self.assertEqual([[0, 0], [800, 480]], slot["frame"])
            self.assertNotIn("instrument", slot)

            catalog = json.loads(archive.read(UI_SYNC_V2_PATH))
            self.assertEqual("xcertplay-debug-uisync-v2", catalog["format"])
            row = catalog["displays"][0]
            self.assertEqual(0, row["profileDisplayIndex"])
            self.assertEqual("main", row["profileDisplayId"])
            self.assertEqual(MAIN_SCREEN_UUID, row["advertisedScreenId"])
            self.assertEqual(MAIN_SCREEN_UUID, row["topologyDisplayId"])
            self.assertEqual("MAIN", row["destinationRole"])
            self.assertEqual("default", row["destinationInstanceId"])
            self.assertEqual({"kind": "android", "value": "host.main"}, row["outputId"])
            self.assertEqual("xcertplay-main", row["uiSyncDisplayId"])
            signed_layout = row["layouts"][0]
            self.assertEqual(0, signed_layout["layoutIndex"])
            self.assertEqual(layout["id"], signed_layout["layoutID"])
            self.assertEqual({}, signed_layout["activeComponents"])
            self.assertEqual([{"zoneIndex": 0, "componentIndex": 0}],
                             signed_layout["activePrimarySelections"])
            self.assertEqual([], signed_layout["slotIngresses"])
            self.assertEqual([{
                "zoneIndex": 0, "componentIndex": 0, "slotIndex": 0,
                "contentId": MAIN_CANVAS_CONTENT_ID,
            }], signed_layout["localCanvasSlots"])

            # The unchanged CAF fixture has inert synthetic protocol values, not vehicle
            # motion/speed schema or a binding from the local Canvas to any CAF IID.
            caf = json.loads(archive.read(CAF_PATH))
            with zipfile.ZipFile(io.BytesIO(build_profile())) as baseline:
                self.assertEqual(caf, json.loads(baseline.read(CAF_PATH)))
            accessory = caf["configTree"]["accessories"][0]
            service = accessory["services"][0]
            self.assertEqual(1, accessory["type"])
            self.assertEqual(2, service["type"])
            self.assertEqual({3, 4}, {item["type"] for item in service["characteristics"]})
            self.assertFalse(any(item["type"] in {0x30000046, 0x30000047}
                                 for item in service["characteristics"]))

    def test_main_local_canvas_requires_separate_opt_in_without_motion_probe(self):
        with self.assertRaises(ValueError):
            build_profile(main_uisync=True, main_local_canvas=True)
        with self.assertRaises(ValueError):
            build_profile(main_local_canvas=True, vehicle_motion_probe=True)
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "local-main.zip"
            self.assertEqual(0, main(["--main-local-canvas", str(output)]))
            self.assertEqual(build_profile(main_local_canvas=True), output.read_bytes())
            self.assertEqual(1, main(["--main-local-canvas", str(output)]))
            self.assertEqual(build_profile(main_local_canvas=True), output.read_bytes())
            rejected = Path(directory) / "rejected.zip"
            self.assertEqual(1, main(["--main-local-canvas", "--vehicle-motion-probe",
                                      str(rejected)]))
            self.assertFalse(rejected.exists())

    def test_v2_main_dimensions_update_full_screen_logical_geometry_only(self):
        candidate = build_profile(main_local_canvas=True, main_width=1920, main_height=720)
        self.assertEqual(candidate, build_profile(main_local_canvas=True,
                                                  main_width=1920, main_height=720))
        self.assertNotEqual(build_profile(main_local_canvas=True), candidate)
        with zipfile.ZipFile(io.BytesIO(candidate)) as archive:
            self.assertIsNone(archive.testzip())
            package = json.loads(archive.read("CarPlayPhone.assets/package.json"))
            display = package["displays"][0]
            self.assertEqual([1920, 720], display["size"])
            self.assertEqual(1, display["density"])
            zone = display["layouts"][0]["zones"][0]
            self.assertEqual([[0, 0], [1920, 720]], zone["frame"])
            component = zone["components"][0]
            self.assertEqual([1920, 720], component["size"])
            slot = component["slots"][0]
            self.assertEqual([[0, 0], [1920, 720]], slot["frame"])
            self.assertNotIn("instrument", slot)
            with zipfile.ZipFile(io.BytesIO(build_profile(main_local_canvas=True))) as baseline:
                for name in archive.namelist():
                    if name != "CarPlayPhone.assets/package.json":
                        self.assertEqual(baseline.read(name), archive.read(name), name)

    def test_v2_main_dimensions_are_paired_bounded_and_cli_only(self):
        invalid = [
            {"main_width": 1920}, {"main_height": 720},
            {"main_width": 63, "main_height": 720},
            {"main_width": 4097, "main_height": 720},
            {"main_width": 4096, "main_height": 4096},
            {"main_width": True, "main_height": 720},
            {"main_width": 1920.0, "main_height": 720},
        ]
        for dimensions in invalid:
            with self.subTest(dimensions=dimensions), self.assertRaises(ValueError):
                build_profile(main_local_canvas=True, **dimensions)
        for mode in ({}, {"main_uisync": True}, {"vehicle_motion_probe": True}):
            with self.subTest(mode=mode), self.assertRaises(ValueError):
                build_profile(main_width=1920, main_height=720, **mode)
        with self.assertRaises(ValueError):
            build_profile(main_local_canvas=True, vehicle_motion_probe=True,
                          main_width=1920, main_height=720)
        self.assertIsInstance(build_profile(main_local_canvas=True,
                                            main_width=4096, main_height=2048), bytes)
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "sized.zip"
            self.assertEqual(0, main(["--main-local-canvas", "--main-width", "1920",
                                      "--main-height", "720", str(output)]))
            self.assertEqual(build_profile(main_local_canvas=True,
                                           main_width=1920, main_height=720), output.read_bytes())
            rejected = Path(directory) / "rejected.zip"
            for options in (["--main-width", "1920"],
                            ["--main-local-canvas", "--main-width", "1920"],
                            ["--main-uisync", "--main-width", "1920",
                             "--main-height", "720"],
                            ["--main-local-canvas", "--main-width", "4096",
                             "--main-height", "4096"],
                            ["--main-local-canvas", "--vehicle-motion-probe",
                             "--main-width", "1920", "--main-height", "720"]):
                with self.subTest(options=options):
                    self.assertEqual(1, main([*options, str(rejected)]))
                    self.assertFalse(rejected.exists())

    def test_v3_carousel_canvas_variants_are_signed_distinct_and_inert(self):
        candidate = build_profile(main_carousel_canvas=True)
        self.assertEqual(candidate, build_profile(main_carousel_canvas=True))
        self.assertNotEqual(build_profile(main_local_canvas=True), candidate)
        with zipfile.ZipFile(io.BytesIO(candidate)) as archive:
            self.assertIsNone(archive.testzip())
            self.assertIn(UI_SYNC_V3_PATH, archive.namelist())
            self.assertNotIn(UI_SYNC_PATH, archive.namelist())
            self.assertNotIn(UI_SYNC_V2_PATH, archive.namelist())
            package = json.loads(archive.read("CarPlayPhone.assets/package.json"))
            layout = package["displays"][0]["layouts"][0]
            self.assertEqual("main-carousel-canvas", layout["id"])
            zone = layout["zones"][0]
            self.assertEqual(MAIN_CAROUSEL_ZONE_ID, zone["id"])
            self.assertEqual({"mapsMedia": {}}, zone["type"])
            self.assertEqual(["map", "media"],
                             [row["id"] for row in zone["components"]])
            for component in zone["components"]:
                self.assertEqual([[0, 0], [800, 480]], component["slots"][0]["frame"])
                self.assertNotIn("instrument", component["slots"][0])

            catalog = json.loads(archive.read(UI_SYNC_V3_PATH))
            self.assertEqual("xcertplay-debug-uisync-v3", catalog["format"])
            rows = catalog["displays"][0]["layouts"]
            self.assertEqual([0, 0, 0], [row["layoutIndex"] for row in rows])
            self.assertEqual([
                ("map", "palette-a", MAIN_CANVAS_CONTENT_ID),
                ("media", "palette-a", "canvas.main.carousel.media"),
                ("map", "palette-b", "canvas.main.carousel.map.alt"),
            ], [(row["activeComponents"][MAIN_CAROUSEL_ZONE_ID], row["paletteID"],
                  row["localCanvasSlots"][0]["contentId"]) for row in rows])
            self.assertEqual([0, 1, 0],
                             [row["activePrimarySelections"][0]["componentIndex"]
                              for row in rows])
            self.assertTrue(all(row["slotIngresses"] == [] for row in rows))
            self.assertEqual(3, len({
                (row["layoutID"], tuple(sorted(row["activeComponents"].items())),
                 row["paletteID"]) for row in rows
            }))

            caf = json.loads(archive.read(CAF_PATH))
            with zipfile.ZipFile(io.BytesIO(build_profile())) as baseline:
                self.assertEqual(caf, json.loads(baseline.read(CAF_PATH)))
            self.assertEqual("synthetic", caf["source"])
            self.assertEqual(1, caf["configTree"]["accessories"][0]["type"])
            self.assertFalse(any(item["type"] in {0x30000046, 0x30000047}
                                 for item in caf["configTree"]["accessories"][0]
                                 ["services"][0]["characteristics"]))

    def test_v3_carousel_opt_in_dimensions_and_motion_exclusion(self):
        with self.assertRaises(ValueError):
            build_profile(main_carousel_canvas=True, vehicle_motion_probe=True)
        with self.assertRaises(ValueError):
            build_profile(main_carousel_canvas=True, main_local_canvas=True)
        with self.assertRaises(ValueError):
            build_profile(main_carousel_canvas=True, main_uisync=True)
        with self.assertRaises(ValueError):
            build_profile(main_carousel_canvas=True, main_width=4096, main_height=4096)
        with self.assertRaises(ValueError):
            build_profile(main_carousel_canvas=True, main_width=1920)
        sized = build_profile(main_carousel_canvas=True, main_width=1920, main_height=720)
        with zipfile.ZipFile(io.BytesIO(sized)) as archive:
            display = json.loads(archive.read("CarPlayPhone.assets/package.json"))["displays"][0]
            self.assertEqual([1920, 720], display["size"])
            self.assertEqual(1, display["density"])
            zone = display["layouts"][0]["zones"][0]
            self.assertEqual([[0, 0], [1920, 720]], zone["frame"])
            self.assertTrue(all(component["slots"][0]["frame"] ==
                                [[0, 0], [1920, 720]] for component in zone["components"]))
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "carousel.zip"
            self.assertEqual(0, main(["--main-carousel-canvas", str(output)]))
            self.assertEqual(build_profile(main_carousel_canvas=True), output.read_bytes())
            rejected = Path(directory) / "rejected.zip"
            self.assertEqual(1, main(["--main-carousel-canvas", "--vehicle-motion-probe",
                                      str(rejected)]))
            self.assertFalse(rejected.exists())

    def test_vehicle_motion_probe_is_explicit_and_synthetic(self):
        candidate = build_profile(vehicle_motion_probe=True)
        self.assertEqual(candidate, build_profile(vehicle_motion_probe=True))
        self.assertNotEqual(build_profile(), candidate)
        with zipfile.ZipFile(io.BytesIO(candidate)) as archive:
            spec = json.loads(archive.read(CAF_PATH))
            with zipfile.ZipFile(io.BytesIO(build_profile())) as baseline:
                self.assertEqual(set(baseline.namelist()), set(archive.namelist()))
                for name in archive.namelist():
                    if name != CAF_PATH:
                        self.assertEqual(baseline.read(name), archive.read(name), name)
        self.assertEqual("synthetic", spec["source"])
        accessory = spec["configTree"]["accessories"][0]
        service = accessory["services"][0]
        self.assertEqual(0x09000001, accessory["type"])
        self.assertEqual(0x1A000009, service["type"])
        self.assertEqual(
            [(0x30000046, 9, 0, 0x408), (0x30000047, 9, 1, 0x409)],
            [(item["type"], item["format"], item["priority"], item["units"])
             for item in service["characteristics"]],
        )
        self.assertTrue(all(type(item["units"]) is int
                            for item in service["characteristics"]))
        self.assertEqual([21.5, 13.4], [row["value"] for row in spec["syntheticValues"]])

    def test_only_vehicle_motion_probe_emits_caf_units(self):
        for options in ({}, {"main_uisync": True}, {"main_local_canvas": True},
                        {"main_carousel_canvas": True}, {"plugin_id": 7},
                        {"main_uisync": True, "phone_override_metadata_probe": True}):
            with self.subTest(options=options):
                with zipfile.ZipFile(io.BytesIO(build_profile(**options))) as archive:
                    spec = json.loads(archive.read(CAF_PATH))
                characteristics = spec["configTree"]["accessories"][0]["services"][0][
                    "characteristics"]
                self.assertTrue(all("units" not in row for row in characteristics))

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "synthetic-speed.zip"
            self.assertEqual(0, main(["--vehicle-motion-probe", str(output)]))
            with zipfile.ZipFile(output) as archive:
                spec = json.loads(archive.read(CAF_PATH))
            characteristics = spec["configTree"]["accessories"][0]["services"][0][
                "characteristics"]
            self.assertEqual([0x408, 0x409], [row["units"] for row in characteristics])


if __name__ == "__main__":
    unittest.main()
