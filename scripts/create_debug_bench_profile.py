#!/usr/bin/env python3
"""Create a deterministic, synthetic Debug bench Profile ZIP.

The output is local test data, not an Apple/OEM/MFi-authorized Profile. It
contains no private key, signature, or real vehicle reading. UI Sync catalogs
are opt-in. The user must import and locally sign the ZIP through the Debug
bench workflow separately.
`--phone-override-metadata-probe` adds a minimal ResolvedTheme candidate and
compatible metadata for a developer-only iPhone override experiment. It does
not install a phone theme or establish retail CarPlay Ultra eligibility.
`--vehicle-motion-probe` adds synthetic parked-bench speed values with iOS 27
CAF unit types. They are not real vehicle speed or proof of phone acceptance.
"""

import argparse
import hashlib
import io
import json
import plistlib
from pathlib import Path
import sys
import zipfile


FIXED_ZIP_TIME = (1980, 1, 1, 0, 0, 0)
PHONE_ROOT = "CarPlayPhone.assets/"
CAF_PATH = PHONE_ROOT + "xcertplay-bench-caf-v1.json"
UI_SYNC_PATH = PHONE_ROOT + "xcertplay-bench-uisync-v1.json"
UI_SYNC_V2_PATH = PHONE_ROOT + "xcertplay-bench-uisync-v2.json"
UI_SYNC_V3_PATH = PHONE_ROOT + "xcertplay-bench-uisync-v3.json"
MAIN_SCREEN_UUID = "b7e6c5a0-1111-4000-8000-000000000001"
MAIN_CANVAS_CONTENT_ID = "canvas.main.bench"
MAIN_CAROUSEL_ZONE_ID = "main-carousel-zone"
BENCH_ASSET_IDENTIFIER = "bench.asset"
PHONE_BENCH_LAYOUT_STYLE_ID = "bench-style"
PHONE_BENCH_PALETTE_ID = "palette-a"
DEFAULT_MAIN_SIZE = (800, 480)
# Debug fixture budget: accommodates 4K panels while bounding a full-screen RGBA
# Local Canvas to 32 MiB. This is a logical size, not Android DisplayMetrics DPI.
MIN_MAIN_EDGE = 64
MAX_MAIN_EDGE = 4096
MAX_MAIN_PIXELS = 8_388_608


def _json(value: object) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":"),
                       ensure_ascii=True, allow_nan=False) + "\n").encode("ascii")


def _plist(value: dict) -> bytes:
    return plistlib.dumps(value, fmt=plistlib.FMT_BINARY, sort_keys=True)


def _zip(entries: list[tuple[str, bytes]]) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED,
                         allowZip64=False) as archive:
        for name, content in entries:
            info = zipfile.ZipInfo(name, date_time=FIXED_ZIP_TIME)
            info.compress_type = zipfile.ZIP_STORED
            info.create_system = 3  # Explicit Unix regular-file/directory mode.
            info.external_attr = ((0o40755 if name.endswith("/") else 0o100644) << 16)
            if name.endswith("/"):
                info.external_attr |= 0x10
            archive.writestr(info, content)
    return output.getvalue()


def _caf_spec(plugin_id: int, vehicle_motion_probe: bool) -> dict:
    # Mirrors the synthetic CAF_SPEC fixture, with the phone's first indexed plugin ID by default.
    spec = {
        "format": "xcertplay-debug-caf-v1",
        "source": "synthetic",
        "pluginID": plugin_id,
        "protocolVersion": "1.0",
        "pluginConfig": {"pluginID": plugin_id, "bench": True},
        "configTree": {"accessories": [{
            "type": 1, "iid": 1, "version": "bench",
            "services": [{
                "type": 2, "iid": 2,
                "characteristics": [
                    {"type": 3, "iid": 3, "format": 9, "priority": 0,
                     "initialValue": 21.5},
                    {"type": 4, "iid": 4, "format": 8, "priority": 1,
                     "initialValue": 1},
                ],
                "controls": [],
            }],
        }]},
        "bindings": [
            {"iid": 3, "propertyId": 100, "areaId": 0},
            {"iid": 4, "propertyId": 101, "areaId": 0},
        ],
        "errors": {
            "unknownIdentifier": -101,
            "invalidValue": -102,
            "operationFailed": -103,
            "generalFailure": -104,
        },
        "syntheticValues": [
            {"iid": 3, "value": 21.5},
            {"iid": 4, "value": 1},
        ],
    }
    if vehicle_motion_probe:
        # iOS 27 CarAccessoryFramework-known types; the values remain synthetic and unsafe for use
        # as vehicle telemetry. This variant must be explicitly requested on a parked bench.
        accessory = spec["configTree"]["accessories"][0]
        service = accessory["services"][0]
        accessory["type"] = 0x09000001  # VehicleMotion
        service["type"] = 0x1A000009  # DisplayedSpeed
        service["characteristics"][0]["type"] = 0x30000046  # SpeedKMH
        service["characteristics"][0]["units"] = 0x408  # kilometersPerHour
        service["characteristics"][1].update({
            "type": 0x30000047,  # SpeedMPH
            "format": 9,  # FLOAT
            "units": 0x409,  # milesPerHour
            "initialValue": 13.4,
        })
        spec["syntheticValues"][1]["value"] = 13.4
    return spec


def _main_video_zone() -> dict:
    # A full-screen type-110 InputStream; there is no local UI overlay or blank-only scene.
    return {
        "id": "main-video-zone", "frame": [[0, 0], [800, 480]],
        "type": {"single": {}},
        "components": [{
            "id": "primary", "type": "primary", "size": [800, 480],
            "slots": [{
                "style": {"rectangular": {}},
                "frame": [[0, 0], [800, 480]],
                "zIndex": 0, "positionId": "phone-video",
                "instrument": {
                    "kind": {"inputStream": {"_0": "video.main"}},
                    "backgroundColor": "#00000000", "clipsToShape": False,
                },
            }],
        }],
    }


def _main_local_canvas_zone(width: int, height: int) -> dict:
    # An Empty slot is assigned to local drawing by the signed v2 catalog. No instrument,
    # vehicle value, or InputStream is encoded in this theme layout.
    return {
        "id": "main-canvas-zone", "frame": [[0, 0], [width, height]],
        "type": {"single": {}},
        "components": [{
            "id": "primary", "type": "primary", "size": [width, height],
            "slots": [{
                "style": {"rectangular": {}},
                "frame": [[0, 0], [width, height]],
                "zIndex": 0, "positionId": "local-main",
            }],
        }],
    }


def _main_carousel_canvas_zone(width: int, height: int) -> dict:
    # The selected mapsMedia primary is named by UI Sync activeComponents. The two
    # components are mutually exclusive; no unsupported deck or vehicle data is implied.
    def component(component_id: str) -> dict:
        return {
            "id": component_id, "type": "primary", "size": [width, height],
            "slots": [{
                "style": {"rectangular": {}},
                "frame": [[0, 0], [width, height]],
                "zIndex": 0, "positionId": "local-" + component_id,
            }],
        }

    return {
        "id": MAIN_CAROUSEL_ZONE_ID, "frame": [[0, 0], [width, height]],
        "type": {"mapsMedia": {}},
        "components": [component("map"), component("media")],
    }


def _main_ui_sync_catalog() -> dict:
    # Mirrors the exact MAIN/DDP/stream selectors checked by BenchUiSyncProfileCompiler.
    return {
        "format": "xcertplay-debug-uisync-v1",
        "displays": [{
            "profileDisplayIndex": 0, "profileDisplayId": "main",
            "advertisedScreenId": MAIN_SCREEN_UUID,
            "destinationRole": "MAIN", "destinationInstanceId": "default",
            "topologyDisplayId": MAIN_SCREEN_UUID,
            "outputId": {"kind": "android", "value": "host.main"},
            "uiSyncDisplayId": "xcertplay-main",
            "layouts": [{
                "layoutIndex": 0, "layoutID": "main-video",
                "paletteID": "palette-a", "activeComponents": {},
                "activePrimarySelections": [{"zoneIndex": 0, "componentIndex": 0}],
                "slotIngresses": [{
                    "zoneIndex": 0, "componentIndex": 0, "slotIndex": 0,
                    "streamIdentifier": "video.main", "ingressId": "ingress.main",
                }],
            }],
        }],
    }


def _main_local_canvas_catalog() -> dict:
    catalog = _main_ui_sync_catalog()
    catalog["format"] = "xcertplay-debug-uisync-v2"
    layout = catalog["displays"][0]["layouts"][0]
    layout["layoutID"] = "main-local-canvas"
    layout["slotIngresses"] = []
    layout["localCanvasSlots"] = [{
        "zoneIndex": 0, "componentIndex": 0, "slotIndex": 0,
        "contentId": MAIN_CANVAS_CONTENT_ID,
    }]
    return catalog


def _main_carousel_canvas_catalog() -> dict:
    catalog = _main_ui_sync_catalog()
    catalog["format"] = "xcertplay-debug-uisync-v3"
    row = catalog["displays"][0]

    def variant(component_id: str, component_index: int, palette_id: str,
                content_id: str) -> dict:
        return {
            "layoutIndex": 0, "layoutID": "main-carousel-canvas",
            "paletteID": palette_id,
            "activeComponents": {MAIN_CAROUSEL_ZONE_ID: component_id},
            "activePrimarySelections": [{
                "zoneIndex": 0, "componentIndex": component_index,
            }],
            "slotIngresses": [],
            "localCanvasSlots": [{
                "zoneIndex": 0, "componentIndex": component_index,
                "slotIndex": 0, "contentId": content_id,
            }],
        }

    row["layouts"] = [
        variant("map", 0, "palette-a", MAIN_CANVAS_CONTENT_ID),
        variant("media", 1, "palette-a", "canvas.main.carousel.media"),
        variant("map", 0, "palette-b", "canvas.main.carousel.map.alt"),
    ]
    return catalog


def _phone_resolved_theme_candidate() -> dict:
    # iOS 27's phone-side DocumentReader expects a ResolvedTheme, not the
    # editable LayoutStyle array used by the accessory compiler. These four
    # maps are the smallest statically inferred decoder candidate. The empty
    # ColorID map supplies no instrument or vehicle data and does not prove
    # that a retail phone will install or render the asset.
    return {
        "palettes": {
            PHONE_BENCH_PALETTE_ID: {
                "id": PHONE_BENCH_PALETTE_ID,
                "displayName": "Bench",
                "colors": {},
                "representedColorScheme": {"single": {"color": "#101820"}},
                "sortIndex": 0,
                "prominent": False,
            },
        },
        "layoutStyles": {
            PHONE_BENCH_LAYOUT_STYLE_ID: {PHONE_BENCH_PALETTE_ID: {}},
        },
        "defaultPaletteIDs": {
            PHONE_BENCH_LAYOUT_STYLE_ID: PHONE_BENCH_PALETTE_ID,
        },
        "defaultWallpaperIDs": {},
    }


def build_profile(
        main_uisync: bool = False,
        plugin_id: int = 0,
        vehicle_motion_probe: bool = False,
        main_local_canvas: bool = False,
        main_width: int | None = None,
        main_height: int | None = None,
        main_carousel_canvas: bool = False,
        phone_override_metadata_probe: bool = False) -> bytes:
    if not 0 <= plugin_id <= (1 << 64) - 1:
        raise ValueError("CAF plugin ID must fit UInt64")
    if sum((main_uisync, main_local_canvas, main_carousel_canvas)) > 1:
        raise ValueError("Choose only one MAIN UI Sync bench catalog")
    if (main_local_canvas or main_carousel_canvas) and vehicle_motion_probe:
        raise ValueError("Local Canvas bench cannot include synthetic vehicle speed")
    if (main_width is None) != (main_height is None):
        raise ValueError("MAIN width and height must be specified together")
    if main_width is not None:
        if not (main_local_canvas or main_carousel_canvas):
            raise ValueError("Custom MAIN dimensions require a Local Canvas catalog")
        if (type(main_width) is not int or type(main_height) is not int or
            not MIN_MAIN_EDGE <= main_width <= MAX_MAIN_EDGE or
            not MIN_MAIN_EDGE <= main_height <= MAX_MAIN_EDGE or
            main_width * main_height > MAX_MAIN_PIXELS):
            raise ValueError("MAIN dimensions exceed the bounded Debug Canvas budget")
    width, height = (DEFAULT_MAIN_SIZE if main_width is None else
                     (main_width, main_height))
    main_display = main_uisync or main_local_canvas or main_carousel_canvas
    if phone_override_metadata_probe and (not main_display or vehicle_motion_probe):
        raise ValueError("Phone override metadata probe needs MAIN and no synthetic speed")
    accessory = _zip([("BenchAccessory/theme.json", b"{}")])
    accessory_sha = hashlib.sha256(accessory).hexdigest().encode("ascii")
    package = {
        "identifier": "bench",
        "version": 1,
        "minimumSDKVersion": "1",
        "buildType": "debug",
        "partnerID": "user-bench",
        "driveModeLinkedByDefault": False,
        "displays": [{
            "id": "main" if main_display else "bench-cluster",
            "type": "centerConsole" if main_display else "cluster",
            "size": [width, height],
            "density": 1, "allowManufacturerAppIcon": False,
            "requiresDarkAppearance": False,
            "layouts": [{
                "id": ("main-carousel-canvas" if main_carousel_canvas else
                       "main-local-canvas" if main_local_canvas else
                       "main-video" if main_uisync else "bench-layout"),
                "layoutType": "primary",
                "zones": ([_main_carousel_canvas_zone(width, height)] if
                          main_carousel_canvas else
                          [_main_local_canvas_zone(width, height)] if main_local_canvas else
                          [_main_video_zone()] if main_uisync else []),
                "backgroundColor": "#101820", "safeAreaInsets": [0, 0, 0, 0],
                "driveModeThemeConfigurations": {},
            }],
        }],
    }
    if phone_override_metadata_probe:
        # Align the optional phone candidate with the root asset identity and
        # bind its MAIN layout to the ResolvedTheme style. Legacy ZIPs remain
        # byte-for-byte unchanged when the probe is absent.
        package["identifier"] = BENCH_ASSET_IDENTIFIER
        package["displays"][0]["layouts"][0]["layoutStyleID"] = (
            PHONE_BENCH_LAYOUT_STYLE_ID
        )
    entries = [
        (PHONE_ROOT, b""),
        (PHONE_ROOT + "package.json", _json(package)),
        (PHONE_ROOT + "theme.json", _json(_phone_resolved_theme_candidate())
         if phone_override_metadata_probe else b"{}"),
        (PHONE_ROOT + "Info.plist", _plist({"Bundle": "DEBUG.BENCH", "Version": "1"})),
        (CAF_PATH, _json(_caf_spec(plugin_id, vehicle_motion_probe))),
    ]
    if main_uisync:
        entries.append((UI_SYNC_PATH, _json(_main_ui_sync_catalog())))
    if main_local_canvas:
        entries.append((UI_SYNC_V2_PATH, _json(_main_local_canvas_catalog())))
    if main_carousel_canvas:
        entries.append((UI_SYNC_V3_PATH, _json(_main_carousel_canvas_catalog())))
    root_metadata = {
        "AssetIdentifier": BENCH_ASSET_IDENTIFIER,
        "iOSContentVersion": "debug-ios-1",
        "AccessoryContentVersion": "debug-accessory-1",
    }
    if phone_override_metadata_probe:
        # iOS 27's developer override metadata reader requires a numeric
        # AccessoryContentVersion, or falls back to a string CFBundleVersion.
        # Keep all candidate version fields aligned to package.version without
        # treating that consistency as a proven iOS hard gate.
        root_metadata.update({
            "iOSContentVersion": str(package["version"]),
            "AccessoryContentVersion": package["version"],
            "CFBundleVersion": str(package["version"]),
            "MinimumCompatibilityVersion": package["version"],
            "MinimumSDKVersion": package["minimumSDKVersion"],
        })
    entries.extend([
        ("CarPlayAccessory.assets.zip", accessory),
        ("Certificate.chain.pem", b"DEBUG BENCH ONLY"),
        ("CarPlayAccessory.assets.sha256", accessory_sha),
        ("AccessoryAdditions", _plist({"Bench": True})),
        ("Info.plist", _plist(root_metadata)),
    ])
    return _zip(entries)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="new ZIP path; never overwritten")
    main_catalog = parser.add_mutually_exclusive_group()
    main_catalog.add_argument("--main-uisync", action="store_true",
                              help="also include a MAIN InputStream UI Sync v1 trial")
    main_catalog.add_argument("--main-local-canvas", action="store_true",
                              help="include a MAIN Empty-slot Local Canvas UI Sync v2 trial")
    main_catalog.add_argument("--main-carousel-canvas", action="store_true",
                              help="include a MAIN mapsMedia Local Canvas UI Sync v3 trial")
    parser.add_argument("--main-width", type=int, metavar="LOGICAL_WIDTH",
                        help="Local Canvas logical width, 64..4096 (requires --main-height)")
    parser.add_argument("--main-height", type=int, metavar="LOGICAL_HEIGHT",
                        help="Local Canvas logical height, 64..4096 (requires --main-width)")
    parser.add_argument("--plugin-id", type=int, default=0,
                        help="CAF plugin ID for a local routing probe (default: 0)")
    parser.add_argument("--vehicle-motion-probe", action="store_true",
                        help="explicit parked-bench synthetic SpeedKMH/MPH schema probe; "
                             "not real vehicle speed or phone acceptance proof")
    parser.add_argument("--phone-override-metadata-probe", action="store_true",
                        help="MAIN-only developer override metadata types; not a retail install")
    args = parser.parse_args(argv)
    try:
        payload = build_profile(
            main_uisync=args.main_uisync,
            plugin_id=args.plugin_id,
            vehicle_motion_probe=args.vehicle_motion_probe,
            main_local_canvas=args.main_local_canvas,
            main_width=args.main_width,
            main_height=args.main_height,
            main_carousel_canvas=args.main_carousel_canvas,
            phone_override_metadata_probe=args.phone_override_metadata_probe,
        )
        with args.output.open("xb") as destination:
            destination.write(payload)
    except (OSError, ValueError) as error:
        print(f"Cannot create Profile ZIP: {type(error).__name__}", file=sys.stderr)
        return 1
    print(f"Created synthetic Debug bench Profile: {args.output}")
    print(f"SHA-256: {hashlib.sha256(payload).hexdigest()}")
    if args.phone_override_metadata_probe:
        print("Developer-only phone ResolvedTheme candidate; no retail install or acceptance proof")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
