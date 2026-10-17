<div align="center">
  <img src="https://raw.githubusercontent.com/shilapi/xcertplay/refs/heads/master/asset/xcertplay_small.png" width="180" height="180" alt="xcertplay icon" />
<h1><strong><font size="6">xcertplay</font></strong></h1>
  <a href="README.md">English</a> | <a href="README.zh-CN.md">中文</a>
  <p>An Android head-unit CarPlay receiver. It supports connecting to the MFi chip through a CH341 I2C bridge or directly through the board's I2C controller, and supports both wired and wireless CarPlay connections.</p>
</div>

## Features

- CarPlay host applications for Android and Android Automotive OS.
- Support for MFI chips connected through a CH341 bridge or native
  `/dev/i2c-N` devices, local certificate/private-key files, and Remote MFI
  authentication (see the API below).
- Wired and wireless CarPlay connections.
- Experimental CarPlay Ultra interoperability: hardened multi-channel RCS,
  UI Sync, CAF/Vehicle Data foundations, and independently configurable
  instrument display output. Unsupported private features remain disabled.
- Voice, navigation, and music multi-channel audio output mapped to the
  corresponding Android channels.
- Dynamic Activity resizing with automatic re-handshaking to the new
  resolution.
- Separate web pages for the main and instrument displays (the browser decodes
  H.264/H.265 directly; the main display supports touch and audio).
- Vehicle head-unit location reporting.
- Android 9 (API 28) support.

## Usage

1. Pair your iPhone with the head unit via Bluetooth.
2. Before a CarPlay video stream starts, tap the Settings button in the lower-right corner. You can also swipe down with three fingers to open Settings.
3. Make sure all the settings are configured as desired.
   To enable another entry gesture, turn on `More gestures to Settings page`. Start with one finger in the upper quarter of the left eighth of the screen, slide down along that strip, and lift in the lower quarter.
4. Scroll to the bottom and select `Save & Reconnect`.
5. Connect your MFi chip using the method you selected.
6. Wait for the connection to complete, then enjoy.

## Web displays

1. In Settings, under `Web displays`, enable `Main display` and/or
   `Instrument display`, set the resolution, then tap `Save & Reconnect`.
2. Put the browser device on the same LAN as the Android device and open the
   address shown in Settings (`https://<IP>:8080/main` or `/cluster`, including
   the access key; it can be copied).
3. The certificate is self-signed: each browser warns once, choose to continue.
4. Main display page: touch with up to two fingers; while it is open the
   phone's audio plays in the page. Tap the screen once to turn on sound
   (a browser requirement). The microphone stays on Android.
5. The instrument display page is view-only and needs the iPhone to provide the
   second display stream.

Use a recent Chrome, Edge, or Safari. If the browser cannot play H.265, turn
off `HEVC (H.265)` in Settings.

## CarPlay Ultra

Settings can declare the instrument display independently of browser output.
The experimental UI Sync codec, per-display router, and presentation bridge are
implemented and tested, but the app does not advertise UI Sync until an integrator
supplies an authorized CarKit vehicle identity and matching theme provisioning.
Ordinary type-111 video, local preview, PIP, and the custom cluster WebDisplay do
not depend on that provisioning.

The repository includes strict profile/theme parsing, an exact-session MFi receipt seam,
a V2 multi-ingress/multi-output graph, acknowledged decoder Surface retirement, a bounded
UI Sync snapshot pipeline, direction-aware typed commands, a per-session Android resource
coordinator, and an API 28 EGL14/GLES2 external-OES backend. Logical-to-pixel mapping and
diagnostic theme scene geometry preserve CarPlay logical units without using Android density.
An issuer-bound compiled-runtime manifest now ties the verified profile to the exact UI Sync
schema/binding/theme program, renderer implementation, display/output/ingress plan, CAF schema,
decoder, and handler identities. The activation coordinator creates and owns the resulting
`AirPlayUltraRuntime` itself, so an AA05/profile revocation cannot be escaped by transferring raw
UI Sync or CAF provisioning to another owner.
The evidenced APAP packet, ChaCha20-Poly1305, TCP-length and APTransport control codecs are joined
by a detached, bounded ingress runtime for an already-authorized injected stream and key.
`mainBuffered` remains unadvertised until its live listener, SETUP/control transaction, decoder,
clocked audio sink and session-bound readiness are implemented.
The AA05 authorization lifecycle is wired into the product Host, but the default provider and
vehicle identity authority remain unavailable. These are not a complete Ultra implementation:
there is no authorized complete semantic/theme factory, UI Sync renderer/output Host wiring, concrete cross-screen
atomic binder, pinned OEM verifier, or authorized profile compiler. The product profile provider
remains `Disabled`; Vehicle State Protocol, UI Sync, theme transfer, UpdateData, LoggingData, and
other unconfirmed/private capabilities are not advertised.
See [the implementation and evidence matrix](docs/carplay-ultra.md).

AirPlay physical millimetres affect the UI scale chosen by the phone. An experimental
migration briefly changed width to longest-edge semantics without converting the stored value,
which could make portrait CarPlay text and icons much larger. The current version repairs that test
preference and uses a rotation-stable shortest-edge calibration by default; it does not modify
Android's global DPI.

## Current progress

Ordinary CarPlay works 👍; this statement does not mean the disabled Ultra stack is product-ready.
The ordinary path has been tested on car head units and phones. If you encounter
an incompatible car head unit, please open an issue and attach your log from
`/sdcard/Android/data/com.shilapi.xcertplay/files/logs/xcertplay.log`.

Adapter board: [CH341-to-MFI](https://github.com/shilapi/ch341-to-mfi-chip)

## Local MFI files

Choose `Local files` under `MFI certificate & signing target`, then use the two
`Choose` buttons to select the certificate and private key with Android's system
document picker. The supported formats are a DER PKCS#7 certificate (`.p7b`)
and its matching, unencrypted DER PKCS#8 private key (`.pk8`). The app validates
that the files match before starting the phone connection and reloads them on
MFI reconnect.

Store the private key in a protected location. Neither file is copied into app
preferences; only Android's persistent read permission and document URI are
saved.

## Project structure

| Path | Purpose |
| --- | --- |
| `common/` | Shared CarPlay host activity, settings UI, persistence, and app resources used by both targets. |
| `mobile/` | Standard Android target using the shared CarPlay host UI. |
| `automotive/` | Android Automotive OS target with the shared host UI and advanced audio channel mapping. |
| `shared/` | Car App Library code plus the CH341, I2C, MFi, iPhone, iAP2, NCM, VPN, AirPlay, and media implementations. |

## Remote MFI

The Remote MFi client treats a remote service as an MFi chip for remote calls,
or uses BAA authentication. Remote authentication avoids the process of
connecting to a local MFi chip for authentication.

### Endpoints

| Method | Path | Purpose | Request body | Success response | Failure response |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/mfi/certificate` | Get the MFi chip version, certificate type, and certificate contents; cached by the client after the first call | None | Certificate JSON | `{"detail":"..."}` |
| `POST` | `/mfi/sign` | Sign the challenge | `{"challenge":"...","requestId":"..."}` | `{"signature":"..."}` | `{"detail":"..."}` |
| `POST` | `/mfi/reset` | Request a reset of the remote MFi chip | `{}` | `{"detail":""}` | `{"detail":"..."}` |

(Optional) Standard Bearer Authentication can be used for verification.

**Currently, only BAA Authentication has been tested.**

## Requirements

- A working JDK 17 or newer to launch Gradle. The daemon uses the JDK selected
  by `JAVA_HOME` (or the Java executable on `PATH`); CI selects its own JDK.
- Android SDK Platform 37.
- Android 9 (API 28) or newer.
  On Android 9, Wi-Fi P2P 5 GHz mode is unavailable and LocalOnlyHotspot is used instead.
- Android NDK `28.2.13676358`.
- A physical USB Host/OTG Android device and MFi hardware are required for
  hardware validation.

## Build

On Windows PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest :common:lintDebug :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug
```

On macOS or Linux:

```bash
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :common:lintDebug :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug
```

Unsigned release APKs:

```powershell
.\gradlew.bat :mobile:assembleRelease :automotive:assembleRelease
```

## Acknowledgements

Thanks to [LIVI](https://github.com/f-io/LIVI) for providing important
reference for this project.
Thanks to the [showcase](https://github.com/amineross/showcase) project for
providing important reference for the BAA authentication in this project.

## License

Licensed under the [GNU General Public License v3.0](LICENSE).
