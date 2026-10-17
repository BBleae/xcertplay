# CarPlay Ultra interoperability

xcertplay contains an experimental, clean-room CarPlay Ultra interoperability stack. It preserves
the ordinary CarPlay main display and type-111 instrument display, including the Android preview,
view/safe areas, and custom HTTPS/WebSocket display, while adding separately gated vehicle-data and
UI-synchronization control planes.

This is not an Apple-certified or vehicle-production implementation. Private OEM/MFi contracts,
vehicle profiles, and safety validation are still required for a complete production integration.

## Architecture

```text
AirPlay control session
|- type 110/111 video -> session-bound AndroidMediaSink -> local Surface / WebDisplay
|                                      `-> EGL14/GLES2 OES compositor (Debug signed static bench only)
|- type 103 main-buffered audio -> detached APAP codec + PCM/AAC ingress (product disabled)
|- type 130 Vehicle Data -> authenticated RCS -> APTransport -> CAF (Debug signed bench only)
|- type 130 ClusterControl -> authenticated RCS -> UI Sync -> presentation snapshot
|                                                     `-> scene-guarded RequestLayout outbox
|                                                     (Debug signed static test path; no full gauge renderer)
|- type 130 MatchPoint -> diagnostic runtime + detached Media3/HLS and TLS-PSK sources
|                         (unregistered; no product playback authority)
|- iAP2 0x4300/0x4301 -> optional, evidence-backed cluster-asset metadata
`- iAP2 0xAD00/0xAD01/0xAD02 -> explicit Debug AppLinks metadata subscription
                                      `- optional 0xAD03/0xAD04 + FileTransfer type 8 -> one verified Debug icon

Independent vehicle-essential domain (not an AirPlay/CarPlay child)
`- real-source + connector/occlusion/expiry contracts -> semantic Overlay supervisor
                                                     (DENY_ALL without OEM hardware issuers)
```

`AirPlayConfig.cluster` is the only type-111 geometry definition. Browser output is only an output
destination and no longer decides whether the instrument display is declared to the phone.

## Feature evidence matrix

| Capability | Implemented | Unit tested | Enabled in the app | Interoperability status |
| --- | --- | --- | --- | --- |
| Ordinary main display, audio, touch, view areas | Yes | Yes | Yes | Existing behavior preserved |
| Ordinary type-111 cluster and UI context | Yes | Yes | User setting | Existing behavior preserved |
| Multi-channel encrypted RCS transport | Yes | Yes | Experimental UpdateData handler and separately opted-in Debug CAF bench; other product handlers off | UpdateData has encrypted loopback tests but no real-phone capture; Debug CAF reached phone RCS SETUP and exchanged one configuration request/response |
| UI Sync carrier, per-display router, passive phone-first reset, sequencing and ACK | Yes | Yes | Debug-only signed physical bench candidate; Release off | A signed P-256 MAIN-only local AA05 child, DDP/UI Sync negotiation, type-130 encrypted loopback Reset/READY and Local Canvas first-frame cover reveal now pass together offline. A separate real TCP same-control loopback reaches SETUP-enabled `uiSync`, `/info.uiSyncInfo` plus MAIN DDP, then type-130 SETUP/TEARDOWN; its renderer, physical output and RCS dataPort are fixtures. A live iPhone syslog shows only the phone's own Reset; no phone response to our Reset, ResetComplete, ACK or foreground acceptance captured. Handshake follows the 24A446 binary trace (see "UI Sync handshake") |
| UI Sync type-2 / authenticated type-111 frame pairing | Detached one-shot diagnostic gate | Eight direct tests | Off; no live renderer wiring | Requires an exact reducer result and snapshot, authenticated frame identity, fresh common-clock calibration and independent fully opaque region/transform proof. A paired result is neither a presentation permit nor a video scheduling command. No default calibration/geometry issuer or real-phone vector |
| Custom UI Sync presentation snapshots | Infrastructure | Yes | Debug bench candidate only | Revisioned WebDisplay adapter plus direction-aware typed semantic state; the Debug signed owner conditionally attaches the exact CLUSTER display snapshot to an existing cluster WebDisplay after physical activation. No real-phone semantic capture or product WebDisplay activation |
| Renderer-neutral presentation hub and display binding | Infrastructure | Yes | Off | Per-control-session hubs/factories, complete-renderer receipts, and bounded non-inline dispatch exist |
| Multi-output renderer | EGL backend, signed static-subset compiler, exact post-bind activation, v2 signed local Canvas slots, output-pixel affine textured layers and narrow CLUSTER instrument reference | Yes | Debug signed bench candidate only | Identity geometry preserves existing scenes; only local/remote textured quads may use the new validated affine, while CLUSTER Instrument/Overlay/Punch-through remain untransformed. App code can register a painter and request redraw for the current signed content ID without borrowing Surface. Initial scene, instrument upload and MAIN-triggered CLUSTER replay require sealed per-frame EGL receipts. These are JVM/fake-driver results, not a proven mapping from phone type-2 transforms, native Gauge/Indicator rendering, an external physical cluster, optical proof or phone ACK |
| Surface router, decoder leases, and MAIN/external UI covers | EGL/router batch binder, exact Host authority, MAIN in-Activity black View and process-owned external Presentation black cover | Yes | Debug signed bench candidate only; trusted external route unavailable on current device | Ordinary MAIN starts uncovered. Ultra handoff and accepted MAIN visual-boundary events revoke old permits and require exact Host cover ACK before stale pixels can be relied on; later reveal needs a fresh signed type-3 scene revision, EGL proof and matching TextureView timestamp. Terminal malformed/closed replay, transient AA05 BUSY and null-intent palette events fail closed. A missed producer timestamp permits at most two signed same-scene reproofs while covered. External CLUSTER requires its own signed CLEAR/Numeric proof. STOPPING and type-110 TEARDOWN cover before retirement; ordinary recovery uses an exact ticket and fresh TextureView. Offline tests pass, but Android UI cover is not optical blanking, hardware scan-out, external-panel validation or a live iPhone Ultra result |
| CAF config/message/subscription engine | Yes, plus profile-bound read-only Android Car bridge and Debug signed synthetic mutation v2 | Yes | No production profile bundled | Real phone sent a DATA2 `configRequest`, received ID-0 `configResponse`, and reported plugin `0` parsed with `accessoriesCount=0` while DDP was off. VehicleMotion is Ultra-only on this firmware, so that count and absent consumer-driven register/read traffic do not establish OEM Profile rejection; live Ultra-on semantics and real vehicle values remain unverified |
| Vehicle State Protocol | Infrastructure and Debug signed CAF bench | Yes | Debug explicit opt-in only; Release off | Real phone negotiated it with a user-signed Debug bench; synthetic values are not vehicle data. The DDP-off CAF trial cannot determine whether an Ultra-enabled phone would allocate or subscribe to the known VehicleMotion tree |
| Session management (`stopSession`) | Yes | Yes | Wireless with explicit Ultra opt-in | Exact iOS 27 sidecar/command subset; reasons 1 and 2 only |
| CarPlay AppLinks iAP2 metadata | Identification declaration and typed `0xAD00` Start / `0xAD01` Update / `0xAD02` Stop with bounded, session-owned lifecycle | Yes | Debug Ultra opt-in only; Release/default off | iOS 27 `accessoryd` feature `0x18` requires these three declared message directions for the AppLinks capability bit; optional `0xAD03`/`0xAD04` are not part of that gate. The streaming Host has explicit Start/Stop controls and never subscribes automatically. No post-change iPhone capability/consent or live AD01 capture |
| CarPlay AppLinks icon subset | Optional `0xAD03` request / `0xAD04` response, exact-session type-8 FileTransfer receiver, one in-memory verified image and bounded Host decode | Yes | Debug Ultra opt-in, explicit icon-size and row request only; default `None`, Release off | Requires accepted MFi/CSM, active subscription, state-2 AD01 row with icon hash, and a one-shot manual request. Receiver caps image at 2 MiB, validates SHA256_16 and PNG/JPEG/WebP header geometry, then publishes only to the same current owner. No live iPhone AD03/AD04/type-8 capture, confirmed phone image format/hash length, app launch or persistent icon cache |
| Multi-display topology, V2 resource graph, resolved render plan | Infrastructure plus batch-output session coordinator | Yes | Debug signed static candidate | Independent ingress/output cardinality and exact resource identity; an external Host route exists but no independently trusted physical panel or complete theme semantics are present |
| Physical `displayPanels` / DDP plug-in description | Opt-in serializer, exact-live-Surface Host probe and asynchronous two-phase Host freeze | Yes | Explicit setting, off by default; diagnostic bypass only in Debug | Host freezes stream pixels before sink construction, then waits only for a fixed trusted external candidate; timeout/withdrawal yields baseline MAIN-only geometry. MAIN needs its exact visible Surface; ALT also needs one independently trusted CLUSTER Surface and same-sink route. The phone parsed the MAIN panel as `ddpCap`, but a parked probe exposed a persistent Dashboard lockout rather than usable Ultra UI. Post-AA05 admission strips panels without ready MAIN UI Sync except in the explicit Debug probe; non-diagnostic `/info` serialization restricts DDP to an accepted UI Sync feature scope and freshly checked local readiness, with no atomic guarantee after that check |
| External cluster `Presentation` | Candidate discovery, physical-route lease, process-owned async Presentation lifecycle and typed EGL handoff | Yes | Product path exists but fixed trust providers deny by default | Connector/display epoch, calibrated millimetres, sink type-111 route, registry token and exact physical batch remain bound through handoff. A hardware attestor and device validation are still missing; DisplayManager alone never promotes a panel |
| Video playback control | Directional command codecs, default-deny parking authority, detached VideoOverlayUI action/transaction/geometry classifier and atomic scene reducer, and VideoSettings property schema | Yes | Off | No OEM source certificate or trusted hardware safety output exists; no ordinary VHAL value can emit `allowed=true`. The overlay subset now types origin/size/anchor/affine style fields and canonical image-asset references alongside bounded nonnested transactions; the in-memory reducer commits a valid bundle as one revision or leaves the old scene intact. No DeviceInfo unit/safe-area/scale mapping, z-order, drawing, RCS handler, ACK, touch vector or live phone validation. Settings project four known property keys |
| MatchPoint video-player data plane | Diagnostic runtime, planner, optional exact-session factory, session-bound Media3/HLS player, revocable PIC/park/output grants, PairVerify PSK lease and bounded TLS-PSK source | Yes | Off | The optional factory is wired into media-session lifecycle but product supplies no authority bundle; a missing grant denies readiness without claiming PSK or player. Five confirmed commands can drive an injected player port. Detached TLS-PSK passed local loopback, pins session peer/Network and route, and never falls back to ordinary HTTPS. No product PIC issuer, client PSK identity vector, real-phone handshake, first-frame proof, trusted hardware cover or negotiated VideoPlayback feature |
| Main-buffered audio / APAP | Detached codec, authenticated dual-port transaction, strict PCM16/48k ingress and product-preparer candidate, experimental AAC-LC 44.1/48k ingress/MediaCodec, epoch-gated `mctl` owner and controlled AudioTrack | Yes | Off | AirPlaySession issues generation-bound raw-secret leases only after full M4 response flush. Frozen request tickets and independent connection-ID/mctl-seed/APAP-`shk` replay ledgers permit a new same-M4 child only after old-owner quiescence; pending retirement returns 503. PREPARING now reserves one slot under lock, binds or rolls back APAP/mctl outside it, then publishes only if that same slot remains current; close cancels and waits boundedly. PCM native play/write use in-flight tokens, but independent hardware mute is unproved. Product injection remains off and source byte order, format, clock, AAC packetization, ALAC and phone vectors remain unproved |
| Video timing NTP clock | Exact-peer PT210/211 probe matcher and bounded video-clock lease | Yes | Ordinary video timing; not UI Sync calibration | Each reply must match the selected address/port and one outstanding t1. Only selected/applied measurements within age/error/era bounds may schedule video; contradictory picker-window samples widen or revoke the lease, and ambiguous half-era deltas fail closed. Source port on a real iPhone is unverified. This is not a type-2/type-111 shared-clock issuer |
| Cluster asset metadata | Identity-pinned endpoint seam and Host catalog probe | Yes | Explicit experimental setting, off by default | Wired/wireless 0x4301 can request version-zero catalog refresh only after exact live MFi pin match; no real-phone query or transfer capture |
| Profile quarantine, canonical store, compiled-runtime manifest, activation receipt | Safe seam plus Debug CAF and static UI Sync bench provider, immutable policy epoch | Yes | Release `Disabled`/`Unavailable`/external trust `DENY_ALL`; separate Debug opt-ins | A user-owned P-256 signature anchors the test Profile. Debug import/consent/Host-setting replacement first revokes the old epoch and requires exact MAIN/CLUSTER Android Host cover ACK; failure retains the published selection and retryable old owner. The phone opened CAF, but no Apple/OEM trust root, full CAF exchange or phone-accepted UI Sync proof exists |
| Theme/package/manifest schemas | Strict parser, native/phone Gauge/Indicator affinity, signed v3 same-layout component/palette variants, v2 local-Canvas assignments, type-3 scene controller and narrow ResolvedTheme style witness | Yes; offline palette regressions repaired and retested | Debug signed static candidate only; Release off | Issuer/executor independently re-read exact phone package/theme bytes at issuance. An immutable exact-profile/display/intent index now selects validated v3 variants without repeating SHA, parse or compile on each type-3; session liveness and signature binding are still checked. The signed initial styled primary uses an inert default palette; alternate palettes require exact later phone type-3. MAIN visual boundaries require Host cover ACK. No live iPhone or complete Theme rendering proof |
| Local instrument signal bundles | Exact Theme-slot and CAF service/characteristic pin validation, read-only speed triplet/RPM-state/indicator-state bundle, per-IID source-age rejection | Yes | Optional signed Debug CLUSTER reference only; Release off | The compiler binds declarations to one exact verified bench Profile, its phone package and CAF schema; the issuer independently rechecks assignments. The Debug child can open an owner-scoped source and render an opaque, BENCH-marked full-frame reference with an initial unavailable state, freshness expiry and physical-generation retirement. A signed per-frame content proof distinguishes complete safety CLEAR from fresh Numeric reference content and binds current scene/output/ingress generations and EGL timestamp. Joined JVM tests prove the initial CLEAR path and a transient BUSY→CURRENT retry, not a native numeric physical sample, optical scan-out, integrated safety Overlay, external CLUSTER hardware or real-vehicle validation |
| Essential vehicle Overlay | Independent semantic source/output supervisor and fail-closed hardware contracts; no compositor | Yes, with fake issuers | Default `DENY_ALL`; no OEM source/output integration | Per-key real-source revision/freshness, independent connector and above-CarPlay-plane evidence, ≤100 ms autonomous hardware command TTL, watchdog retirement and explicitly reviewed per-telltale Unknown fallback are enforced at the boundary. No real vehicle source, physical safety plane, verified optical blanking or safety certification is supplied; app preview/diagnostic overlays are not this Overlay |
| Binary-plist value model | Yes, bounded subset | Yes | Used by existing protocols | Negative Int64 uses Foundation-style two's complement; an 8-byte integer with its high bit set decodes as raw signed Long, so the detached video codecs reject ambiguous positive 2^63..2^64−1 outbound values |
| Theme asset transfer / UpdateData | OPACK codec, bounded receiver/RCS handler and durable untrusted quarantine | Yes | Experimental with Ultra opt-in and authenticated per-session quota | iOS 27 header/chunk/checksum/ACK framing and encrypted loopback confirmed; no real-phone transfer, signature verifier, installation or theme activation |
| LoggingData and product log-transfer activation | Directional OPACK codec, bounded archive sender, authenticated exact-type RCS handler and serial dispatcher | Yes | Off | iOS 27 receiver establishes request/header/chunk/statistics/ACK fields; no trusted archive/telemetry producer, confirmed sidecar, negotiated feature, or phone validation |

Only a feature that reaches a valid semantic exchange on a real phone should be described as
interoperable. Passing local codec tests or knowing a private UUID is not sufficient.
Fresh build and test counts are recorded with the later dated run below rather than treated as
interoperability evidence. Offline checks are not a live iPhone UI Sync/Ultra capture, an
external physical CLUSTER display test, or an optical safety/blanking certificate.
The current negotiation code requires a wireless session for CAF and UI Sync. Apple's
[WWDC24 architecture overview](https://developer.apple.com/videos/play/wwdc2024/10111/)
describes the next generation of CarPlay as wireless-only. This public statement is product
architecture evidence; the narrower receiver readiness checks still require their own live
validation on the targeted iPhone build.

## 2026-10-15 synthetic profile and live iPhone observation

The earlier debug-only device harness generated its own package, inspected and reverified it from
the canonical Store, then ran AA05 receipt consumption, manifest preparation/commit, synthetic UI
Sync reset/presentation over encrypted loopback RCS, CAF read/update, and revocation/close on the
Android device. That historical fixture reported `PASS` using test-supplied readiness facts, not
an observed physical output batch. The current harness deliberately withholds ClusterControl
without physical proof while retaining its CAF and lifecycle exercise. Neither version fed its
profile or runtime into the iPhone's AirPlay session.

In a real wireless session on the same device, Mac BAA/MFi authentication reached AA05, the iPhone
completed Pair Verify, both type-110 and type-111 video produced first frames, and the type-130
iAP tunnel completed handoff. The product authorization result was `Unavailable`, matching the
fixed `Disabled` deployment provider and unavailable vehicle identity authority. The phone's SETUP
requested `vehicleStateProtocol`, but the negotiated enabled set was only `uiContext`, `viewAreas`,
`altScreen`, `hevc`, `sessionManagement`, and `iAPChannel`. The accepted type-130 client UUID was
the iAP channel `E9459FD0-BCAD-4C45-820F-1E72447EF2F2`; no CAF or ClusterControl client was
opened. The live result is therefore ordinary CarPlay with the wireless SessionManagement subset,
not a CarPlay Ultra session.

A positive live Ultra result in this app requires a deployment profile and stable vehicle identity
that pass its fixed local trust policy, plus the claimed renderer and CAF backend for the same real
AA05 receipt. That is an application safety boundary, not evidence of a phone-side OEM-signature
check at feature-offer time. The synthetic harness cannot establish an Apple/OEM deployment or
prove a real iPhone accepted any Ultra channel.

## 2026-10-16 iOS 27.0.1 eligibility trace

Reverse analysis of the matching IPSW and the saved wireless handshake narrowed the phone-side
gate. CarKit derives theme-asset availability from its Ferrite feature mask `0x28`. In the
post-consent iPhone archive, `carkitd` passes that mask to the availability sender and
`accessoryd` logs `themeAssetsAvailable = 40` (`0x28`) for the same session. A later 16:21
live iAP2 frame capture finally resolved the wire ambiguity: the nested `0x4300` U8 was exactly
`1` while the iPhone's `accessoryd` again logged `40`. Thus the Ferrite mask is an upstream
feature argument/log value, **not the observed wire byte**; the original Boolean interpretation
is correct for this captured iOS 27 session, though not proven for every mask/version. In the
underlying feature set, bit 3
(`0x08`, GaugeCluster) maps to AirPlay `uiSync` and `fileTransfer`; bit 4
separately maps to `vehicleStateProtocol`. In the earlier session, `0x4300` carried `0` and SETUP
requested vehicle state but not UI Sync. Both observations are consistent with the same CarKit
feature calculation. The zero alone does not identify which input cleared GaugeCluster:
the paired vehicle record, theme-asset support/identifier, device policy, or disabled features.

The saved `0x4301` StartSession omitted SDK version and cluster-asset ID/version. The examined
`carkitd` asset-manager StartSession paths require the asset ID, SDK version and a
present asset-version number before updating the stored cluster asset identifier. An explicit
version zero requests a catalog update; an absent version is skipped by those business paths.
The ObjC dictionary decoder also rejects a missing asset version, but it has not been proven to
be the direct decoder for this project's iAP2 transport: the previously captured five-field
StartSession still launched ordinary CarPlay. That message lacked the asset ID and SDK version.
The transport builders now accept exact eligible preinstalled asset metadata with a nonblank SDK
version and emit it even when the phone's earlier availability value is zero. A separate,
default-null acquisition intent emits ID and SDK version with an explicit zero asset version to
request a catalog update; it does not claim installation or authenticity. Both paths bind to the
actual MFi authentication identity and cannot be selected together. The product has no authorized
preinstalled catalog selection; an independent, default-off bench setting can now select the
zero-version request only when an explicitly pinned certificate type and identity digest match
the live MFi provider. No asset ID, SDK or pin is prefilled. Its BAA test provider
uses a short-lived certificate. In four saved iPhone log archives spanning reconnects and the
consent change, the observed leaf serial stayed `01a13e9d3a9d`; the earlier claim that it changed
on every connection was unsupported. CarKit's certificate-serial lookup can therefore find the
same stored vehicle during those sessions, although stability across provider restarts and the
identity behind each similarly named CarPlay settings entry remain unverified. These
are distinct from the definite local `Disabled`/`Unavailable` product gate above. A phone-side
CarKit trace is needed to distinguish the remaining feature-policy conditions.

To obtain the pin for a provider you control, run `python3 scripts/mfi_identity_pin.py` while
that provider is running (or pass `--server` for an HTTPS endpoint). It makes one read-only
`/mfi/certificate` request, validates the provider checksum, and prints the certificate type
and the same domain-separated identity digest used by the app; it does not print or save the
certificate. Set `XCERTPLAY_MFI_TOKEN` in the environment if the provider requires a bearer
token. A changed or restarted short-lived BAA certificate requires a new pin. Matching a pin
only permits this explicit catalog probe; it does not authorize a Profile or imply an asset
is installed on the phone.

A device run on 2026-10-16 before the iPhone setting change reproduced the same boundary: AA05
produced `authorization unavailable`; both Bluetooth and tunnel `0x4300` carried `0`, and both
`0x4301` messages had only five top-level parameters. SETUP was before `/info`,
requested vehicle state but not UI Sync, and enabled only `uiContext`, `viewAreas`, `altScreen`,
`hevc`, `sessionManagement`, and `iAPChannel`. `/info` had no vehicle-state or UI Sync sidecar;
the only accepted type-130 client was the ordinary iAP channel. Both type-110 and type-111 H.265
streams rendered first frames, and the Android app remained running. This is another ordinary
CarPlay session, not a positive Ultra test.

The same iPhone's unified-log archive adds an important qualification. `carkitd` reported a
connected vehicle with an existing pairing, `vehicle supports theme assets: YES`, and `device
supports theme assets for this asset ID`, yet sent `CarPlayAvailability ... {assets: NO}`.
The CarPlay process reported `No enhanced integration consent for launch UI Contexts`,
`isVehicleDataSession=NO`, and no theme controller. Thus the earlier hypothesis that the phone
simply had no paired vehicle is not established. The feature calculation still did not admit
GaugeCluster, while absent consent is a separate observed gate. Private feature values in the
log are redacted, so the exact condition that cleared its bit remains undetermined. The phone's
generic CarExperienceAssets catalog also
reported a parse error, but this occurred after the initial `assets: NO` and cannot explain that
earlier availability value by temporal order alone.

The first archive alone left two alternatives: a stored vehicle not yet at `pairingStatus == 2`,
or a feature-policy mask clearing Ferrite. A later archive records pairing status `2` and a
trusted CarPlay device for this session, narrowing the observed block to the feature-policy path.
The missing enhanced-integration consent is separately observed and does not appear in this availability
calculation. The generic MobileAsset parse error concerns an app denylist, not direct proof that
the cluster asset or Ferrite feature was denied.

A later iPhone archive from the same test setup makes the last arithmetic gate decisive: at
01:49:25, `carkitd` logged `supportedCarPlayFeatures: features disabled by preference: 40`
immediately before sending `assets: NO`. The corresponding firmware function intersects the
disabled mask with candidate features and removes that intersection. Decimal 40 is `0x28`, exactly
the GaugeCluster and PassengerDisplay Ferrite bits. Despite the log wording, the helper that
produces this mask combines the vehicle's disabled-features preference with an asset-disabler
decision; either can produce `0x28`. The archive does not expose which source did so. Missing
enhanced-integration consent remains a separate confirmed gate.

The firmware also contains a concrete way this preference can arise: when a vehicle reports
theme-asset support but its StartSession delivers Classic with no theme-asset metadata and no
prior preference, `carkitd` logs `Ultra-capable HU delivered Classic with no prior preference`
and persists all Ferrite bits (`0x28`) as disabled for that vehicle. This is a plausible historical
origin for the current mask, not proof that this branch ran in the captured session; the archive
begins after the preference was already present. Clearing a pairing or preference before the
product has authorized asset metadata would not fix the missing implementation and could simply
recreate the opt-out.

CarKit's `CRVehicleAccessoryManager._updateVehicle:usingAccessory:` derives the observed
`supportsThemeAssets: YES` from bit `0x200000` in the current CoreAccessories accessory feature
mask, then updates the stored vehicle. A separate `carkitd` Bluetooth identification message has
an explicit `supportsThemeAssets` property. iOS 27 `accessoryd` maps the existing iAP2
`WirelessCarPlayTransport` group 24, child 5 void parameter to that theme-assets capability;
the Android Identification builder already emits it for wireless transport. Thus the paired
vehicle's `supportsThemeAssets: YES` does not require forgetting and re-pairing to add a new
field, nor does it prove an OEM Profile was accepted. The Mac BAA helper only supplies
certificate/signature operations and is not the origin of this feature bit.

After the user changed the iPhone setting, carkitd recorded four writes setting disabled CarPlay
features to zero, and its last checks of feature mask `0x28` returned NO. In a fresh 02:16 session,
the availability sender was passed `0x28`, and the phone's SETUP newly requested `fileTransfer`
and `uiSync`. The original `0x4300` frame bytes have not been preserved. This proves the previous
phone-side feature block was cleared for that session. The Android product
still returned `authorization unavailable`, so it did not enable UI Sync or vehicle state and
advertised no corresponding `/info` sidecars. The same iPhone session separately logged no
enhanced-integration consent for launch UI Contexts, no session file-transfer support for theme
assets, and no theme controller. Multiple similar vehicle entries exist on the phone; the logs
hide their IDs, so the toggled setting cannot yet be tied to every per-vehicle consent state.
The later decoder preserved the raw theme-availability byte, and a further 02:18 handshake
reproduced nonzero theme availability and the phone's `uiSync` request. The saved Android
diagnostic did not independently establish whether the byte was `1` or `0x28`. The unchanged
product authorization still fell back, while both ordinary H.265 displays rendered their first
frames.

A new 16:21 parking/bench run on the current Debug APK completed Remote MFi AA05, the P2P/iAP2
handoff, Pair Verify, and both ordinary H.265 video streams. Its raw `0x4300` formatter read
`wirelessAvailable=1` and `themeAssetsAvailable=1`; the same iPhone's unified log recorded
`themeAssetsAvailable 40` before serialization. SETUP requested `uiSync`, `fileTransfer`,
`vehicleStateProtocol`, `mainBuffered`, `videoPlayback`, and `logTransfer`, but the local
authorization was `Unavailable`: only ordinary display features, quarantine-only
`fileTransfer`, session management and iAPChannel were enabled. `/info` had no `uiSyncInfo`,
vehicle-state sidecar or `displayPanels`; the only accepted type-130 client was iAPChannel.
The phone reported no gauge-cluster screen, no theme controller, no vehicle-data session and
`No enhanced integration consent for launch UI Contexts`. Because multiple similar vehicle
entries remain on the phone, this consent log cannot yet be assigned to the entry the user
changed. An initial 16:11 P2P attempt ended before the phone opened AirPlay TCP, but the second
attempt succeeded on the same APK with a scoped IPv6 P2P address; neither the placeholder BSSID
nor theme availability `1` is a sufficient explanation for the first timeout.
Subsequent read-only inspection found that a dual-address `p2p0` could select its scoped `fe80::`
first, so the installed listener bound only `[fe80::]%p2p0:7000` and did not accept IPv4
`192.168.49.1:7000` connections. An initial source change to prefer only IPv4 had an unverified
regression risk: the same phone had actually succeeded over IPv6. The revised source retains
scoped IPv6 as the primary bind/Bonjour address and may separately listen on a verified IPv4
address of the exact P2P group interface. Both sockets share one generation/retirement ledger;
the controller re-queries the exact current P2P group/interface and live listener addresses at
both 0x5703 and 0x4301 send points, instead of announcing a cached bind snapshot. P2P startup
rejects a pre-existing group it cannot prove belongs to this attempt, paces group-info polls,
and retains a retryable cleanup owner when asynchronous create/remove or channel loss is
unresolved; it does not claim a timed-out removal succeeded. Android `removeGroup` cannot name a
particular group ID, so identical groups replaced between the last check and deletion remain a
platform TOCTOU limit, not a certified ownership guarantee. This
revision has not been installed or retested on the iPhone. It addresses a possible
*post-association* IPv4 AirPlay failure, not the separate current absence of any Wi-Fi station
on `p2p0`.

### What the synthetic profile does and does not prove

The debug harness's ZIP, signature placeholder, trust verifier, AA05 receipt and RCS peer are
local fixtures. None of its bytes were offered to the iPhone, and a local `PASS` proves the
Android session machinery, not phone acceptance of an OEM asset. Conversely, the live iPhone did
offer `uiSync` and `fileTransfer` after the feature preference changed, although no authorized
theme package was installed in this app. This disproves a blanket claim that an OEM-signed profile
is required merely for the phone to *offer* these two protocol features.
There is now a debug-only detached ECDSA P-256 verifier for a user-owned bench signing key. It
binds vehicle ID, exact MFi certificate kind/digest, asset ID, content versions and inspected ZIP
digest. The debug build can explicitly select a CAF bench provider and, with a separate opt-in,
an executable static UI Sync catalog bound to exact live Host outputs; both recheck this binding
at real AA05 activation. The release provider remains disabled. A local signature cannot assert
Apple/OEM authorization or, without a device capture, prove real iPhone acceptance. Reusing the
same signed stored asset is allowed; live session freshness remains the AA05
receipt/coordinator's responsibility.

The observed phone-side gates are separate: CarKit computes vehicle Ferrite features from the
paired record, device-supported features and disabled-feature mask; AirPlay then requests the
corresponding protocols; DashBoard checks enhanced-integration consent for launching UI contexts.
AirPlaySender records the accepted SETUP feature flags, then requires corresponding `/info` keys
(`fileTransferInfo`, `uiSyncInfo`, `vehicleStateProtocolInfo`) for enabled features and their
absence for disabled features. That validation checks key presence, not a theme ZIP signature;
the later AirPlaySender RCS factory checks the accepted feature flags for ClusterControl,
UpdateData and ProtocolData client types, again without a theme ZIP check in that branch.
More precise iOS 27 analysis places the first ClusterControl switch at the AirPlay feature
response: `AirPlaySender` sets `enableCarPlayClusterControlChannel` only when SETUP's **enabled**
features include `uiSync`. Without that bit, `createRemoteControlSession` returns `-17606` before
the `07D9F906-8D64-4B54-A808-A20BCA2C51C2` network client opens. A mismatch between the
accepted bit and `/info.uiSyncInfo` separately returns `-16720`. A DDP panel, a phone request
for UI Sync or a standalone `/info` key cannot substitute for an accepted, consistent pair.
An authenticated `/info` arriving before feature SETUP now freezes an ordinary-only scope:
provisional Ultra sidecars cannot be correct for every later phone proposal. The control session
rejects any repeated feature SETUP and retires its old channels; idempotent replay would need
explicit timing/event-port reuse, while renegotiation would also need atomic channel revocation.
The underlying feature exchange separately refuses a changed request or lost readiness instead
of returning an old wider feature answer. A frozen `/info` scope is rechecked against live UI Sync
physical preflight before it can be emitted again.
The saved iOS 27 handshake used SETUP-before-`/info`, so the info-first fallback is a protocol
consistency fix, not an explanation of that captured session's Ultra block.
Whether DashBoard actually requests and drives those channels, and whether it can launch a usable
theme, are later stages. The product's experimental Ultra opt-in can now conditionally enable
`fileTransfer` for a bounded, authenticated, quarantine-only session; its disabled/release authorized
Ultra composition still declines `uiSync` and vehicle state.
The last check still logged missing consent in the 02:16 session despite `assets: YES`, so the
setting/vehicle-identity correlation is not established. No captured session negotiated UI Sync
or transferred a theme. Whether a self-built profile can drive a genuine UI Sync channel on this
iPhone remains an experimental question, not a demonstrated yes or no.

The availability sender's *input* is a Ferrite bit set: `carkitd` passes
`supportedCarPlayFeatures & 0x28` and its 02:16 log records `40`; the later raw iAP2 capture
shows that this is not necessarily the outgoing U8. In addition,
`CRFeatureAvailability.supportedCarPlayFeaturesForSession:` removes GaugeCluster (`0x08`) if the
session configuration has no gauge-cluster screen. `CARSessionConfiguration.hasGaugeClusterScreen`
is computed from whether any parsed display reports `supportsDDPContent`; the physical-display
plug-in constructor sets that bit, while the legacy display constructor does not. A type-111
instrument video stream alone therefore does not satisfy this session gate. It is a DDP
display-configuration gate, not an observed OEM signature check. Normal availability also
requires the paired-vehicle state; the firmware has a separate simulator-policy branch, so
pairing status `2` is not a universal rule.

DashBoard's direct `DBUISyncChannel` creation gate checks its internal disable flag, at least one
Layer Metadata requester and a current session; it does not directly check an asset signature or
`layoutURL`. `DBDisplayManager` registers a per-screen requester only when the display identity
matches a screen whose `supportsLayerTracking` is true. CarKit derives that property from
`supportsDDPContent` on a physical display plug-in object: a nonempty top-level `displayPanels`
array with per-panel `displayPlugins` dictionaries and a string plug-in `uid` selects that
constructor. Its legacy screen fallback does not set the DDP flag. AirPlaySender reads both
`displays` and `displayPanels` independently, so a physical declaration can coexist with the
existing legacy display entries. Reverse analysis of
`CARDisplayInfo.initWithPhysicalScreenDictionary:displayPluginDictionary:` additionally found
that the physical panel's **root** `properties` array supplies its content roles: exact token
`CarPlay` marks MAIN as CarPlay content, while `Map` marks the presently map-only ALT route.
The previous physical serializer omitted that array; the current opt-in MAIN descriptor emits
`properties: [CarPlay]`, and a physically trusted ALT descriptor emits `properties: [Map]` without
claiming `Instrument Cluster` or `showsInstruments`. The app has an explicit, default-off bench
switch for its immutable `/info` physical-panel declaration. It copies the same session's legacy
pixel/mm values and stream UUIDs, leaves ordinary responses unchanged, and projects out inactive
cluster panels when `altScreen` is not negotiated. Current Host emits MAIN only after its exact, visible
`PHYSICAL_MAIN` Surface and pixels have been checked. ALT additionally needs exactly one
separately trusted `PHYSICAL_EXTERNAL` CLUSTER Surface; its non-default instance, connector/display
epoch and calibrated physical millimetres are retained in the frozen liveness proof. A second
trusted candidate invalidates that unique selection. Without an exact type-111 route the external
Surface stays PREVIEW_ONLY. The current in-window preview cannot supply one; a separate
`Presentation` route exists, but default external-trust issuers deny it and the test handset
has no active external panel. The
process-owned target version remains subscribed for the session: replacement, resize, downgrade,
withdrawal, or Activity teardown revokes the exact old AirPlay session. Standalone origin-zero
panels must match their legacy display's
geometry; composite panels retain independent geometry. The plug-in `uid` is an independent
`CARDisplayInfo.identifier`, not required to equal a video UUID. Its managed stream UUIDs must
bind to the existing main/cluster `ScreenID`s; an empty plug-in is insufficient. The candidate
descriptor has unit tests and the phone has parsed MAIN as `ddpCap`; that is not acceptance of a
complete UI Sync/Ultra session. A separate upstream theme branch
can defer connection callbacks while the requested asset is absent; a theme
asset without `layoutURL` lets the callback proceed but does not construct `DBThemeController`.
The static call order is narrower than a blanket theme prerequisite: `sessionDidConnect:` publishes
the current session/features, and a matching DDP screen can register a Layer Metadata requester
and create `DBUISyncChannel`. The later `didConnectSession:` notification that runs display
`handleConnect` can nevertheless be held by a theme lookup. Channel construction alone does not
prove a retail iPhone opened ClusterControl or sent semantic frames;
the actual network connection starts later, when a `DBUISyncSession` adds an observer for a
display. A further `DBDashboard.handleConnect` site was found: when the environment has
GaugeCluster feature bit 3, its `displayInfo.supportsDDPContent` is true and its UI Sync channel
exists, it constructs `DBUISyncSession` without a direct theme or `layoutURL` check. That
constructor calls `DBUISyncChannel.addObserver:forDisplayID:`, which starts the session channel
and calls `openChannelWithError:` for ClusterControl. The earlier claim that every observer
construction requires a theme layout was therefore wrong. Upstream timing still matters:
`DBSessionController.sessionDidConnect:` notifies the display delegate immediately when the
vehicle has no cluster asset ID. Its asset-wait branch requires three conditions together:
nonempty asset ID, GaugeCluster feature bit 3 and DDP support. The historical trace already
had a `Started` old session; a remembered asset ID alone is not an OEM rejection verdict.
When all three hold it waits for a non-null `CARThemeAsset`. A catalog miss leaves its completion
pending; a non-null asset with no
`layoutURL` completes the callback yet cannot create a normal `DBThemeController`. Thus an
asset-less *protocol-level* UI Sync path is statically reachable for a no-ID vehicle, and a
theme-less path is reachable with an asset object lacking a layout. Neither is a captured
retail-iPhone ClusterControl exchange or a ready, rendered Ultra UI. The saved current vehicle
did update a cluster asset ID, so the no-ID branch cannot simply be assumed for it.

An additional iOS 27 path explains why publishing MAIN DDP metadata without a foregrounded
layout is unsafe. `DBLockOutController._shouldLockoutUntilMainAssetForegrounded` checks
`supportsGaugeCluster` (supported-features bit 3), `supportsDisplayPlugin` (DDP), and
`!mainAssetForegrounded`. On the 02:15 live session, supported features were `1023`, and a
MAIN `DBLockOutWindow` covered Dashboard from 02:15:45.213 until disconnection.
`DBDefaultUISyncSessionDelegate.didReceivePacket` handles wire command **5** (`requestLayout`;
the dispatcher uses internal zero-based index 4), and only after a valid layout completes does
its event delegate receive `assetDidForegroundForDisplayID`; `DBDisplayManager` can then clear
the corresponding lockout. The path requires a legitimate rendered asset and functioning UI Sync
channel. This is neither a reason to synthesize the foreground event nor proof that any local
Profile has satisfied the phone's layout, theme or consent gates. The signed static Debug
renderer now has a narrow producer-to-UI-Sync path: after an opaque MAIN scene's EGL presentation
ticket, or after an exact Local Canvas post plus EGL draw/swap/finish receipt, it can offer a
scene-guarded command 5 `RequestLayout` through the session's outbox. Video-only MAIN, unsupported
semantic changes, and stale/replaced scenes cannot claim that foreground proof. A bootstrapped
Debug scene can initiate this local request without a received phone layout command; its
`signedPhoneCommand` remains null rather than being represented as an iPhone event. Later phone
layout, palette or appearance changes advance or suspend the corresponding semantic scene proof.
The outbox separately binds a READY generation; an earlier `SENT_UNCONFIRMED` request is not
coalesced across channel reattach or a changed semantic epoch, graph, batch, viewport or local
layers. A successful local
carrier write remains unconfirmed until the phone's actual UI Sync response; it does not prove
visible pixels or that Dashboard cleared its lockout. No live phone capture has exercised the new
path.

The Android Host's current cluster `TextureView` is a preview overlay within the main Activity,
not an external Android `Display`. Advertising a physical DDP panel as an opt-in protocol probe
does not create a matching second physical output or justify a complete renderer receipt.
The iOS `hasGaugeClusterScreen` predicate accepts *any* parsed DDP-capable display, not an
explicit instrument role. A truthful main-only DDP declaration is therefore a distinct static
possibility on this single-screen handset: the low-level `/info` serializer and signed
single-output runtime contract permit it. The current Host probe supports a verified main-only
declaration; without an independently trusted external output it never emits ALT, even if
ordinary type-111 video is configured. This is not evidence that iOS accepts a complete
main-only Ultra session.
The local AirPlay negotiator now permits a main-only `uiSync` offer only when an exact standalone
MAIN DDP panel, a complete provisioned renderer descriptor, a current signed single-output
`MAIN/default` physical preflight, and live Host panel proof all agree. Existing ALT behavior is
preserved. A revoked Surface actively retires its exact AirPlay session; SETUP and `/info` also
recheck liveness. The Debug provider now has a separately opted-in, signed static UI Sync
candidate; it has not been accepted by a real iPhone and cannot be described as live Ultra.
The independent `DBDashboard` observer path removes a universal theme-layout prerequisite,
but not the upstream asset wait for a vehicle that has an asset ID, nor the missing full theme,
gauge and vehicle integration.
No main-only UI Sync or complete Ultra session has been demonstrated on the real phone.

The new **offline** MAIN-only end-to-end fixture uses a user-signed P-256 Debug Profile and one
locally admitted AA05 child. Under a single frozen feature scope it enables `uiSync`, publishes
MAIN DDP plus `uiSyncInfo`, opens an encrypted type-130 ClusterControl loopback peer, exchanges
Reset/ResetComplete through READY, and waits for a signed Local Canvas first-frame EGL receipt
before an exact fake-Host TextureView timestamp lifts the Android black cover. The first signed
scene revision starts at `1`, so a completed initial frame cannot be confused with an absent
revision. Wrong plug-in UID, missing physical output or transport, and `/info`-first scope fail
closed in the same fixture. This is local AA05/renderer/Surface simulation, not an iPhone
foreground ACK or optical panel result.

A separate shared-module test uses real loopback TCP on **one** AirPlay control connection:
feature SETUP returns enabled `uiSync`; a subsequent `POST /info` returns matching
`uiSyncInfo` and MAIN DDP; type-130 ClusterControl SETUP then succeeds and TEARDOWN retires it.
The test-supplied renderer/physical readiness and RCS `dataPort` do not open a real RCS data
socket or exchange UI Sync payloads in this test. It establishes control-path coherence, not
phone acceptance, a real physical display or a complete Ultra session.

To avoid inducing that lockout in ordinary sessions, the post-AA05
`ControllerUltraAuthorizationRuntime.guardedPhysicalPanelsConfig` now checks both authorized
and fallback publications. Unless the exact MAIN DDP/UI Sync preflight is locally ready, it
removes `displayPanels` **and** their liveness proof before publication. Release never enables
the diagnostic exception; Debug keeps it only when the separate physical-panel probe is
explicitly enabled. For retained non-diagnostic panels, `AirPlayConfig` sets
`requireMainUiSyncForDisplayPanels`, and `AirPlayInfoPlist.build` repeats the exact MAIN/UI Sync
readiness check when serializing `/info`; if renderer or panel liveness went stale after AA05, it
omits the panel metadata. This is not a phone-side Ultra authorization and does not make the
check and later phone parsing atomic: a change immediately after serialization remains possible.
Phone-side consent, theme availability and a working layout handoff remain unproven.

The iOS 27 theme downloader uses `com.apple.MobileAsset.CarDDPAssets` (or the preflight asset type
under a separate preference), keyed by the vehicle's cluster asset identifier and SDK/
compatibility versions. Its ordinary StartSession receiver requires a nonempty asset ID, nonempty
SDK version and present numeric asset version before it updates the vehicle's stored asset
identifier. A stored version of zero asks MobileAsset to refresh the catalog; an absent version
in the examined asset-manager paths does not. Exact iAP2 acceptance of the new zero-version
intent remains untested. The unrelated `CarExperienceAssets` catalog error in the log cannot be
treated as a rejection of that theme query. [Apple's architecture presentation](https://developer.apple.com/videos/play/wwdc2024/10111/)
describes the normal asset direction as iPhone-to-vehicle and authenticity verification as a
vehicle responsibility. In the examined `carkitd` sender, certificate/signature/additions are
optional transfer metadata; archive URL and accessory content version are the early requirements.
That weakens a claim of a phone-side signature hard gate at send time, but says nothing about
MobileAsset catalog admission or the car's acceptance policy. A self-built ZIP in
Android storage does not become a phone MobileAsset or establish that the vehicle should trust it.
The ordinary MobileAsset query uses the stored asset ID, SDK and compatibility versions, not the
MFi serial in that request builder. Claiming an existing, compatible catalog asset ID might
therefore retrieve that *existing* asset, but no such catalog hit or usable cross-vehicle result is captured;
it is not a route to upload a newly authored ZIP. CarKit's `signatureData` accessor reads a file
named `CarPlayAccessory.assets.sha256`, while `certificateData` reads `Certificate.chain.pem`.
The separate `layoutURL` and `archiveURL` resolve to `CarPlayPhone.assets` and
`CarPlayAccessory.assets.zip` respectively; the phone-side layout is not the car-side archive.
Those accessors and the phone sender are metadata plumbing, not proof of a phone-side asymmetric
OEM-signature check or of safe acceptance by the vehicle.

### 2026-10-16 profile-boundary audit

There is no single observed "OEM profile" check governing every stage. The iOS 27.0.1 binaries
and saved sessions support this more precise decision chain:

| Stage | Observed gate | What a local synthetic profile can establish |
| --- | --- | --- |
| Vehicle association and consent | CarKit resolves the paired vehicle by live MFi certificate serial. Enhanced-integration consent is a separate stored status (`CRVehicle.isEnhancedIntegrationEnabled` tests `2`), and its prompt requires `supportsEnhancedIntegration` on the stored or live vehicle. CoreAccessories' `supportsCarPlayAppLinks` capability bit `0x40000` populates that support; theme-assets bit `0x200000` is distinct. iOS 27 `accessoryd`'s AppLinks feature `0x18` requires outbound `0xAD00`/`0xAD02` and inbound `0xAD01` Identification message IDs; outbound `0xAD03` and inbound `0xAD04` are optional for that bit. | A local ZIP/fixture changes neither the phone's pairing record nor consent. The earlier live accessory capability mask was `0x301400`, with theme support but no AppLinks bit. The Debug Ultra opt-in now conditionally declares the required three IDs and optional icon pair, but there is no post-change phone mask or consent capture. The user's four setting writes cleared the Ferrite disabled mask, not the separately observed consent status; multiple similar vehicle entries still prevent attributing a UI choice to the exact live serial. |
| Feature eligibility | Device/vehicle Ferrite capabilities minus disabled features determine the sender's `0x4300` Ferrite mask and the phone's AirPlay request. The session removes GaugeCluster unless a parsed display has DDP content support; legacy type-111 video is insufficient. On this IPSW's `t8160` platform, `_deviceFeaturesForThemeAssetID:` does not universally reject IDs outside its special prefix; asset-disabler and other policy still apply. | The phone demonstrably offered `uiSync` and `fileTransfer` while this app had no authorized profile. This disproves a *local Profile* or universal OEM-ID-prefix gate at feature offer, not MobileAsset catalog admission or later phone-side asset policy. |
| AirPlay acceptance and RCS | AirPlaySender enables ClusterControl only if SETUP's **enabled** features include `uiSync` (`createRemoteControlSession` otherwise `-17606`); `/info.uiSyncInfo` must agree with that acceptance (`-16720` on mismatch) before the type-130 client can open. These gates check feature/sidecar consistency, not a theme ZIP signature. | Signed Debug and real TCP loopback fixtures now exercise matching local SETUP, `/info` and type-130 lifecycle. The 02:15 real phone saw DDP but `ultraUiSync=false` and `/info uiSync:false`, so it had only iAPChannel; Release has no authorized Profile, and no real-phone UI Sync channel capture exists. |
| Theme lookup and transfer | The stored cluster asset ID can come from a CoreAccessories endpoint property or StartSession. A present ID, SDK and version feed a `CarDDPAssets` MobileAsset query; explicit version `0` requests catalog refresh. `MAAsset` conversion supplies the phone-local `layoutURL`. Transfer additionally needs a live file-transfer session, a serial-associated vehicle, a newer staged asset and an accessory archive URL. The theme-asset-library XPC admission checks an entitlement. | The fixture ZIP is not a phone MobileAsset. Even if a reported ID matches a catalog asset, catalog admission, staging and transfer still need observation. Sender certificate/signature fields are optional *metadata in that sender function*, not proof that the vehicle accepts unsigned content; Apple assigns authenticity verification to the vehicle. |
| UI Sync versus rendered theme | DashBoard can register a DDP Layer Metadata requester and create `DBUISyncChannel` before theme lookup. `DBDashboard.handleConnect` independently constructs an observer and starts ClusterControl when GaugeCluster, DDP and channel conditions hold. An existing asset ID plus a catalog miss can still defer the display connection callback; no-ID or non-null asset without `layoutURL` can proceed. | A self-built renderer could reach a protocol-level channel without a phone layout in a static code path. The live phone recognized MAIN DDP metadata and sent type-110 video, but no UI Sync semantic packet, theme-independent visible MAIN output or complete main-only Ultra negotiation was captured. |
| CAF VehicleMotion allocation and registration | `CarAccessoryFramework` marks VehicleMotion Ultra-only; `CAFCarManager.shouldAllocAccessoryType:` checks `ultraEnabled` before allocating it. `caraccessoryd` broadcasts parsed configuration, while registrations and missing-IID wire requests depend on a client/consumer rather than following every `configResponse` automatically. | The DDP-off trial's `ultraEnabled=NO`, `accessoriesCount=0`, and absent register/read do not isolate an OEM acceptance gate. A same-identity, DDP-on/UI Sync-on, consumer-observed trial is needed to test allocation and subscription. |
| Vehicle trust and safety | Apple's [architecture explanation](https://developer.apple.com/videos/play/wwdc2024/10111/) assigns authenticity verification of transferred local-UI assets to the vehicle. | A self-owned test key can prove local integrity under this app's own trust policy, but cannot certify an Apple/OEM deployment or validate safety-critical gauges. |

The *current implementation* still has a lower ceiling than full Ultra:
`BenchUltraProfileDeploymentProvider` admits CAF bench profiles: v1 can read mapped Android Car
or synthetic values, while v2 can mutate only a signed, session-local synthetic repository.
Neither path authorizes writes to a real vehicle. A separately opted-in signed UI Sync catalog
can now compile executable static MAIN/CLUSTER layouts, require matching current DDP plug-in IDs
and exact physical Host targets, and issue a combined CAF/UI Sync manifest from one real AA05
generation. This is a Debug test candidate, not a phone-accepted or complete Ultra session. Its signed
`AssetIdentifier` is bound to the local test policy but is not passed into iAP2 `0x4301`;
the separately enabled, identity-pinned catalog probe only requests version `0`. Release keeps
the deployment provider disabled. A Debug bench `Verified`/`PASS` still cannot by itself prove
an accepted ClusterControl channel or an iPhone-installed theme.

The consent and feature settings are not interchangeable. In the saved post-setting archive,
four `setCarPlayFeaturesDisabled` writes led to `themeAssetsAvailable=40` and a phone SETUP
request for `uiSync`/`fileTransfer`, while the same session still logged `No enhanced integration
consent for launch UI Contexts`. `accessoryd` reported `IAPAppAccessoryCapabilitiesKey=3150848`
(`0x301400`) in the saved sessions: bit `0x200000` is present, bit `0x40000` is absent.
`CRCarKitServiceAgent.isEnhancedIntegrationEnabledWithCertificateSerial:reply:` matches the
request serial against stored vehicle certificate serials; the acceptance path writes status `2`
and posts a consent-change notification. The observed preference writes are not that path.
Further iOS 27 `accessoryd` analysis located the AppLinks feature `0x18` gate: its capability
bit 18 requires the accessory's Identification to list `0xAD00` Start and `0xAD02` Stop as
accessory-to-phone messages and `0xAD01` Update as phone-to-accessory. The `0xAD03` icon request
(accessory-to-phone) and `0xAD04` response (phone-to-accessory) are optional for this particular
feature-bit test. This refines the earlier
unknown-producer conclusion for **AppLinks**; it does not identify the separate theme-assets
bit's wire origin. `Iap2IdentificationClient` now adds these five IDs under the Debug Ultra
opt-in; `Iap2AppLinksMessages` and the wired/wireless control clients provide typed bounded
Start/Update/Stop. The streaming Host now has a Debug-only, explicit category picker and
Start/Stop overlay with a bounded, in-memory latest-update summary. The default preferred icon
size is `None`, preserving AD00 without its optional size field; a user can select 64 px before
Start. Static iOS 27 analysis indicates AD01's optional per-app child-4 icon hash is emitted
only for a positive preferred icon size. Once the exact authenticated MFi/CSM owner has an active
subscription and a state-2 AD01 row with that hash, the user can select that row and explicitly
request **one** CarPlay app icon. The Host neither requests icons nor subscribes automatically
when a control session starts or changes, and it cannot launch apps. A delayed
`0xAD01` from a Stop/Start overlap may be shown as stale metadata; this UI is not an authoritative
app inventory or an authorization signal. A failed `0xAD02` write is shown as Stop-uncertain:
same-session AppLinks commands and metadata delivery stop until the control session reconnects,
without blindly resending a possibly partial Stop.

For that one explicit row, the current CSM sends AD03 root `0` with the CarPlay bundle ID;
root `1` is the separate ExternalAccessory subfeature, parsed but not used by the Host. Static
iOS 27 reverse gives AD04 root `0` bundle ID, root `1` status (`0` absent, `1` available),
root `2` type-8 FileTransfer buffer ID and root `3` hash for status `1`. The owner permits a
single AD03 attempt per CSM because AD04 has no request nonce; uncertain sends and late replies
cannot be retried on that CSM or rebound to a replacement owner after Stop/reconnect. A type-8
SETUP that precedes AD04 is held for at most one second, with neither START nor image allocation
until the matching status-1 response grants the exact buffer ID/hash. A 31-second Host request
timeout, a 30-second receiver
deadline and Stop/session teardown retire the grant. The session-12 receiver admits at most
2 MiB, checks complete length, `SHA256_16` (first 16 bytes of SHA-256) and bounded PNG/JPEG/WebP
header geometry before publishing one memory-only image to the same live owner. Android bitmap
decode runs on a bounded background worker, not the UI thread; detach or owner replacement
discards the image. This deliberately strict parser follows the static hash operation and local
format policy, **not** a captured iPhone icon wire vector: actual AD01/AD04 hash length and
transferred image encoding still need live confirmation. No iPhone AD03, AD04 or type-8 transfer
has been captured after this change.

Release and the default Debug setting leave this subset off, and there is no app-launch
implementation. No subsequent iPhone capture has shown the resulting CoreAccessories mask,
prompt, status `2`, or active-serial consent. The previously absent bit remains a plausible
explanation for prompt eligibility, not proof that this declaration alone would produce a working
Ultra session.
Code anchors: `shared/src/main/java/com/shilapi/xcertplay/transport/Iap2IdentificationClient.kt`,
`shared/src/main/java/com/shilapi/xcertplay/iap2/message/Iap2AppLinks.kt`,
`shared/src/main/java/com/shilapi/xcertplay/iap2/session/Iap2FileTransferReceiver.kt`,
`shared/src/main/java/com/shilapi/xcertplay/orchestration/ControllerAppLinksControl.kt`,
`shared/src/main/java/com/shilapi/xcertplay/transport/Iap2WirelessControlClient.kt`, and
`common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt`.

The asset ID is likewise not an all-purpose authorization token. For this IPSW's `t8160`
hardware, `carkitd`'s `_deviceFeaturesForThemeAssetID:` accepts a non-special, nonempty ID at
the device-feature calculation branch; `_disabledCarPlayFeaturesForVehicle:` can still combine
the stored disabled preference with an asset-disabler result when ID and both versions exist.
This static result narrows the claimed gate only. A local Profile's arbitrary ID is not thereby
published in `CarDDPAssets`, and the phone's stored asset ID can also arrive from a CoreAccessories
endpoint even when `0x4301` lacks asset metadata. The 02:16 saved session logged
`DBSessionController Session did connect` after the asset ID update, but had already fallen back
to Classic capabilities; it cannot test the Gauge/DDP theme-wait branch.

The iPhone also contains a local asset-override path, selected by a global
`AccessoryDeveloperEnabled` preference or internal-install policy, that reads its own
`Library/CarPlay/<assetID>` directory. Its overrider reads version/compatibility metadata from
`Info.plist` and can construct `CARThemeAsset` from phone-local `CarPlayPhone.assets` and accessory
archive files without a MobileAsset catalog hit. Whether an unmodified retail iPhone can enable
that preference and place a custom package there by a supported method is unverified. It is not a discovered wire
protocol for an Android accessory to upload a ZIP. The generic phone-side theme receiver also
does not prove retail accessory-to-phone sideloading: the only located `CRFileReceiver` instance
is the LoggingData receiver, configured for logs/statistics, not `setThemeAssetReceiver:`.
The local Debug ZIP was not originally even metadata-compatible with that override reader:
`AccessoryContentVersion` was a string, while iOS 27 expects an `NSNumber` there and otherwise
falls back to a string `CFBundleVersion`, which was absent. The generator now has the separate
`--phone-override-metadata-probe` option for a MAIN test profile: it emits the observed numeric
version and string fallback with typed optional compatibility fields. It also emits a candidate
phone `ResolvedTheme` with the four required root maps, one represented-color palette, an empty
color-override layout style and matching `layoutStyleID`/asset ID; the Android parser now models
the actual nested ColorSet→ColorID→Dynamic gradient map instead of EditableTheme `LayoutStyle`
objects. A separately signed numeric-version Debug fixture passes the Android importer.
The signed Debug static-scene compiler and independent runtime issuer now re-read those exact
verified package/theme bytes and accept the selected `layoutStyleID` only as inert metadata when
the **selected** palette and its selected ColorSet contain no color override or extension drawing
semantics and its one opaque represented color matches the existing layout background. For a
phone-selected scene, the palette ID is checked against its exact signed type-3 intent and Local
Canvas scene; issuer and executor each reconstruct the witness from verified bytes. Another
palette in the same signed style may carry complex data, but is not interpreted, painted or
inferred as a recolor. A palette-b selection cannot borrow palette-a's witness for that path,
even for the same Layout. The initial styled primary is now required to use the signed default
palette and pass its own inert check; an alternate palette-b waits for an exact later phone type-3
selection. The issuer and executor have both been retested, including the earlier initial-B
regression. Density, millimetres, raster geometry and WebDisplay remain unchanged.
Default/v1/v2/v3 ZIP bytes remain unchanged. These offline parser/compiler checks do not prove that
`CARThemeAsset` or Dashboard accepts/renders the candidate on an iPhone, nor provide retail
installation or authorization. The inner `CarPlayPhone.assets/Info.plist` is still a test stub.

The Overlay is a distinct vehicle-side responsibility rather than another replaceable slot in
the phone theme. The examined CAF path exposes an `OverlayView` service with read/on/region state,
and DashBoard has a separate automaker Overlay state monitor and lockout branch. These symbols
identify a control/state surface, not a decoded visual contract or a built-in safety compositor
for this Android app. Apple's [architecture session](https://developer.apple.com/videos/play/wwdc2024/10111/)
places essential telltales in a vehicle-rendered frontmost Overlay layer.

The new `VehicleEssentialOverlaySupervisor` is an **independent semantic boundary**, not that
physical layer. It has no CarPlay, Profile, CAF, UI Sync, renderer or Activity dependency. Each
telltale/area key requires a real-vehicle source epoch, monotonic sample revision, exact status,
value and source timestamp; a missing, simulated, stale, future-dated or revised sample is
`Unknown`, never silently `KnownOff`. Any `Unknown` presentation requires an independently
reviewed policy for that exact telltale; there is no generic off or warning-light fallback.
An output lease must independently attest the exact connector/display epoch, a hardware safety
plane above CarPlay, and autonomous command expiry of at most 100 ms. The supervisor rechecks
the sample inside the hardware-dispatch guard, subscribes to source/route revocation, schedules
a watchdog at sample expiry, and keeps failed per-key retirement owned for retry. A fault in one
telltale does not revoke another key's lease. Its default source, output and fallback issuers
are `DENY_ALL`; tests use fakes. No real OEM source, physical safety-plane compositor, hardware
expiry issuer, optical blanking proof or safety certification is integrated. An accepted semantic
command remains unconfirmed, and the Android UI cover described below is not this Overlay.
Code anchors: `common/src/main/java/com/shilapi/xcertplay/vehicle/essential/VehicleEssentialOverlayContracts.kt`
and `VehicleEssentialOverlaySupervisor.kt` in that directory.

The remaining decisive bench capture is staged, not a single "Ultra" flag: verify the *same*
paired vehicle's consent; observe zero-version `0x4301` asset metadata and any `CarDDPAssets` query;
observe the phone's requested versus accepted SETUP flags and `/info` sidecars; then check for
ClusterControl/UpdateData RCS opens, semantic UI Sync packets, asset transfer, local authenticity
decision, and a non-null theme controller/rendered output. No saved capture yet spans that chain.
The key falsification is whether, after an explicitly accepted `uiSync` SETUP on the same paired
MFi identity, ClusterControl opens and carries semantic packets *without* a matching phone-side
`CarDDPAssets` theme with `layoutURL`. A positive capture would show that a self-owned Profile
and renderer may reach a protocol-level Ultra control plane even though the phone does not supply
an OEM theme; it would not establish OEM certification or a safe gauge UI. A negative capture must
identify whether consent, `/info` acceptance, DDP layer tracking, asset wait, or the RCS open is
the first failing stage. Neither result requires deleting the vehicle's pairing or preferences.

Static cross-check anchors in build `24A446` (unslid addresses):

| Gate | Binary, method and address |
| --- | --- |
| Device/theme features and disabled mask | `carkitd` `CRFeaturesAvailabilityAgent._deviceFeaturesForThemeAssetID:` `0x10007a54c`, `_disabledCarPlayFeaturesForVehicle:` `0x10007aa48`, `_supportedCarPlayFeaturesForVehicle:` `0x10007ab38` |
| Enhanced-integration support and consent | `ExternalAccessory` `EAAccessory.supportsCarPlayAppLinks` `0x23625f698`; `CarKit` `CRVehicleAccessoryManager._updateVehicle:usingAccessory:` `0x1c17172dc`; `carkitd` `CRCarKitServiceAgent._shouldPromptEnhancedIntegrationForStoredVehicle:messagingVehicle:` `0x1000711d4` |
| AppLinks feature and optional icon Identification | `accessoryd` `_addiAP2IdentificationInfo:` `0x1001498ac–0x1001498c4` sets bit `0x40000`; Identification table `0x1001afc70`; required AD00/AD01/AD02 versus optional AD03/AD04 check `0x10004f00c–0x10004f0c4` |
| AppLinks icon metadata and transfer | `accessoryd` AD01 optional child-4 hash when iconSize > 0 `0x10014e3d8–0x10014e418`; `iap2_appLinks_requestAppIcons` AD03 bundle-ID roots `0x10014d628–0x10014d9f8`; AD04 response construction `0x10014f130–0x10014f178`, `0x10014f438–0x10014f4b4`; image-byte `SHA256_16` `0x1000de840–0x1000de870`; type-8 FileTransfer `0x10014f270–0x10014f380`, `0x100097210–0x10009741c` |
| AppLinks consent state | `accessoryd` status-2 query paths `0x1000de1f4`, `0x1000def3c`, `0x1000df088`; this reverse finding does not substitute for a live, same-MFi-serial consent capture |
| DDP screen and session feature | `CarKit` `CARSessionConfiguration.hasGaugeClusterScreen` computation `0x1c16de00c`, `CRFeatureAvailability.supportedCarPlayFeaturesForSession:` `0x1c1726ffc` |
| Physical panel content roles | `CarKit` `CARDisplayInfo.initWithPhysicalScreenDictionary:displayPluginDictionary:` `0x1c16fcf2c`; root `_CRPropertiesKey` `0x1e1783130` maps `CarPlay`, `Map`, `Instrument Cluster` and `showsInstruments` independently from the DDP plug-in |
| Theme wait and independent UI Sync observer | `DashBoard` `DBSessionController.sessionDidConnect:` `0x25c5cbc44`, `DBDashboard.handleConnect` `0x25c558acc`, `DBUISyncChannel.addObserver:forDisplayID:` `0x25c5ed3f8` |
| MobileAsset request and phone layout | `carkitd` `CRThemeAssetLibrarian._assetRequestForVehicle:` `0x100059610`, `+[CARThemeAsset(MAAsset) themeAssetFromAsset:]` `0x100052fa0`; `CarKit` `CARThemeAsset.layoutURL` `0x1c170aa68` |
| Paired-vehicle transfer and file sender | `carkitd` `CRThemeAssetLibrarian._mainQueue_vehicleForSession:` `0x100056a2c` (MFi serial lookup `0x100056a5c–a8c`), `_mainQueue_evaluateAssetTransferIfConnected` `0x100056b54`, `CRFileSender.sendThemeAsset:` `0x10003b640` |
| Theme-library XPC admission and Dashboard theme use | `CarKit` `CARThemeAssetLibraryAgent listener:shouldAcceptNewConnection:` `0x1c170ccb0` entitlement check; `DashBoard` `_handleSessionConnectWithThemeAsset:` `0x25c5cdc1c`, `_handleSessionConnectRequiringThemeAssetWithCompletion:` `0x25c5cd8f0` |
| Phone-local developer asset override | `carkitd` `CRThemeAssetLibrarian._enablesOverrideAssets` `0x100055bfc`, `CRThemeAssetOverrider._carplayLibraryURL` `0x10005b604`, `_carplayLibraryDirectoryForAssetIdentifier:` `0x10005b68c`, `_readMetadataFromAsset:` `0x10005b854` |
| Ultra-only VehicleMotion type and allocation | `CarAccessoryFramework` `_CAFAccessoryTypeVehicleMotion` `0x2694a7be0` → CFString `0x276a133d8` (`0x0000000009000001`); `CAFAccessoryTypes.isUltraOnlyType:` `0x2549a5eb0`, mapping builder `0x2549a60d4–e4`; `CAFCarManager.shouldAllocAccessoryType:` `0x254a703c8`, Ultra-only check `0x254a70488–98`, `ultraEnabled` check `0x254a704b8`, denial `0x254a704bc–d4` |
| CAF config distribution and demand-driven registration | `caraccessoryd` `_workQueue_didReceiveConfigFromPluginID:config:` `0x100009a28`; `_addRegistration:instanceIDs:priority:withResponse:` `0x10000b140`; `CarDataClient.registerInstanceIDs` `0x100012908`; addRegistrationFromCache block `0x10000f590–a8` sends a wire `registerRequest` only for missing cached IIDs |
| Vehicle Overlay state (not a compositor) | `CarAccessoryFramework` `CAFOverlayView.serviceIdentifier` `0x2549cfbb4`, read/on/region methods `0x2549cf2d4–cfb74`; `DashBoard` `DBAutomakerOverlayStateMonitor` `0x25c75b8b0`, `didUpdateOn` `0x25c75c7b4`, Overlay lockout `0x25c7f46b4` |

## 2026-10-17 signed Debug CAF live probe

In a parked/bench run, a user-owned P-256 signature over the synthetic Profile was staged in the
Debug app and consumed at a real Remote MFi AA05. The Android session log recorded
`0xaa05 AuthenticationSucceeded` at 00:38:38.213 and `CarPlay Ultra authorization activated` at
00:38:38.261: the latter is **the app's local Debug policy result**, not an Apple/OEM
authorization. At 00:38:39.876 the iPhone requested `vehicleStateProtocol`, which the app
supported and enabled; `/info` then carried its vehicle-state sidecar. Across the 00:27 and
00:38 reconnects, the phone attempted both type-130 CAF clients, `CarPlayProtocolData`
(`3E2F3C61-AAD0-42CB-A8AA-BF22186DA62E`) and `CarPlayProtocolData2`
(`FF4A6720-F2BE-4F56-A3E1-DB3B4E37D634`). Both SETUPs were accepted in the earlier run; in
the 00:38 run DATA2 was accepted, but the later DATA attempt was rejected after the DATA2
callback failure had begun tearing down the session. Thus the previous "no real-phone CAF
channel" result was a property of the disabled-profile sessions, not an absolute phone-side
block. Evidence: the on-device `Android/data/com.shilapi.xcertplay/files/logs/xcertplay.log`
and sanitized RCS diagnostics, cross-checked against
`shared/src/main/java/com/shilapi/xcertplay/airplay/rcs/catalog/RcsClientType.kt`.

The first captured `CarPlayProtocolData2` `COMM` body was 70 bytes of **direct OPACK**, not a
`bplist` wrapper. Decoding its outer `pluginID`/`pluginData` and inner command yielded a
`configRequest` for plugin ID `0` (Android log at 00:38:41.782). The staged synthetic profile
instead registered plugin ID `7`; the subsequent callback failed with `CafProtocolException`.
The exact-ID lookup in the local registry makes that mismatch the leading explanation, not a
phone-provided error reason. Read-only inspection of iOS 27.0.1
`caraccessoryd` found that its initial configuration pass enumerates IDs from `0` through
`pluginCount - 1`, consistent with the captured first request. The carrier distinction is
implemented in `CafRcsCodec.decodeCarrier`; the exact-ID lookup is in
`CafPluginRegistry.requireRegistration`. This narrows the first failure to a likely
test-data/protocol mismatch rather than an observed OEM-signature rejection. It does not
establish that an ID-0 profile will complete registration, supply safe vehicle semantics, or
render a gauge. The iOS `caraccessoryd` loop uses each index as the plugin ID, so the
single-plugin Debug profile was regenerated with ID `0`, re-staged, and signed for the new package
digest and real AA05 identity. At 00:53:13.082 local Debug authorization activated again. At
00:53:15.610 the phone sent `configRequest` for plugin `0`; at 00:53:15.612 the app sent a
263-byte direct-OPACK `configResponse` with the same transaction ID. DATA2 remained authenticated
and ordinary CarPlay remained active. The other CAF channel timed out before authentication, and
no subsequent `register` or `read` was observed for over a minute. This proves request decoding,
exact plugin routing, and a reply on the real encrypted channel. A subsequent USB syslog capture
at 01:04 confirmed both iPhone-side `CarDataChannel`s reached `isOpened=YES`, and
`caraccessoryd` reported `Already parsed pluginID 0` and `pluginCount=1/1`; its resulting `CAFCar`
was nevertheless `ultraEnabled=NO`, `accessoriesCount=0`, `isConfigured=YES`. The DATA socket on
the Android side had received no first authenticated frame and timed out after 30 seconds, so
its iPhone-side open state is not proof of bidirectional DATA traffic. The synthetic tree used
type IDs 1/2/3/4, which are not the framework's known accessory/service/characteristic types.
This proves the outer config was parsed, but not that a usable CAF accessory, live vehicle value,
or Ultra gauge was produced. The earlier reading of `accessoriesCount=0` as evidence against the
Profile was too strong. Static inspection of this exact `24A446` `CarAccessoryFramework` shows
that `_CAFAccessoryTypeVehicleMotion` (type `0x0000000009000001`) is classified as Ultra-only, and
`CAFCarManager.shouldAllocAccessoryType:` denies such types when `ultraEnabled` is false. The
source initializer for `CAFCarConfiguration` copies
`CARSessionConfiguration.hasGaugeClusterScreen` into `ultraEnabled`; that CarKit bit derives from
a `supportsDDPContent` display, and the physical-panel probe was **off** in this CAF run. This is
a plausible, code-backed explanation for the zero count without invoking OEM Profile rejection,
not a live demonstration of accessory allocation. A separate `copyWithoutPlugins` initializer
appears not to preserve the Ultra byte, so the printed `CAFCar` alone also cannot prove the
live source bit. DDP eligibility does not establish theme, consent, or Profile acceptance.

An explicitly approved parked-only follow-up used the framework-known VehicleMotion →
DisplayedSpeed → SpeedKMH/SpeedMPH types but **synthetic**, fixed numeric values. The signed
ID-0 Debug Profile activated and answered a 70-byte `configRequest` with a 288-byte
`configResponse` at 01:17:25.870–25.873. No `register` or `read` followed during the brief trial;
the priority-0 DATA socket received no first authenticated frame and timed out after 30 seconds.
The new static trace narrows that negative observation: `caraccessoryd` broadcasts a parsed
configuration, but `_addRegistration:instanceIDs:priority:withResponse:` queues or returns based
on channel/client state, `CarDataClient.registerInstanceIDs` returns an empty response for empty
IID input, and the addRegistrationFromCache path emits a wire `registerRequest` only for IIDs
missing from its cache. Thus no registration/read is expected merely because a `configResponse`
arrived; a consuming UI/data client and its actual IID demand must be observed. Whether this
specific Profile schema would be accepted once that demand exists remains unknown.
The experiment was stopped, CAF bench was disabled, and the active Profile was replaced with an
unsigned, non-speed test ZIP. No fixed speed remains active or locally authorized. This does not
show that the iPhone accepted those known types as a usable vehicle.

Subsequent `24A446` CarAccessoryFramework inspection identified another limitation of that
older synthetic speed probe: its SpeedKMH/SpeedMPH characteristics omitted CAF `units` metadata.
`CAFMeasurementCharacteristic` resolves the integer unit code through `_NSUnitFromCAFUnitType`;
zero falls back to a generic unit with an empty symbol. Bidirectional mapping establishes
`0x407` = m/s, `0x408` = km/h, and `0x409` = mph. The explicit parked-bench generator now emits
`0x408` and `0x409` for its two synthetic speed characteristics, with Python tests; no revised
speed Profile has been signed, installed, subscribed to, or displayed on an iPhone. This does not
authorize or represent real vehicle speed.

A separate parked probe at 01:28 turned on **only** the MAIN physical DDP panel (CAF bench was
off). The app reported `MAIN=ready`, `physicalPanels=true`, and published the MAIN panel; iPhone
`carkitd` appearance logs changed MAIN from `noDdpCap` to `ddpCap`, while the legacy ALT screen
remained `noDdpCap`. This proves the iPhone recognized the panel advertisement without an OEM
Profile. Android's MAIN output then appeared black with only the small ALT map preview visible;
the exact first-frame/decoder cause was not captured before restoration. The probe was switched
off, CAF bench remained off, `/info` returned to no physical panels, and the normal MAIN H.265
first frame rendered again at 01:33:55.812. DDP metadata recognition is not a usable Ultra
display or evidence that UI Sync/gauge presentation works.

At 02:15, a second parked MAIN-DDP A/B used the corrected root content-role array
`properties: [CarPlay]` (the code also emits `[Map]` for a separately trusted ALT physical panel;
this handset had no such ALT output). The phone again logged MAIN `ddpCap` and ALT `noDdpCap`,
accepted an AirPlay SETUP, and sent type-110 MAIN and type-111 ALT first video frames. Android's
HEVC decoder reported a first MAIN output at 1200×2464 pixels and a first ALT output at
1920×720 pixels. Nevertheless, the captured Android screen showed a black MAIN region with the
small ALT map preview. The earlier possibility that the phone never sent a MAIN frame is therefore
ruled out for this run; the evidence does **not** distinguish black source pixels from a
Surface/composition or subsequent rendering problem. In the same phone session, DashBoard logged
`Theme Controller not found` and `No enhanced integration consent for launch UI Contexts`.
Those are observed gates, not proof that either one alone caused the black image; the user's
enabled setting may correspond to one of several similar saved vehicle entries rather than this
exact live pairing. At the time of this first capture, ordinary CarPlay reconnection had not yet
been confirmed.

Retrospective phone-log and binary analysis found that a MAIN `DBLockOutWindow` covered Dashboard
at 02:15:45.213 and remained until this DDP session disconnected. Its observed lockout state
matches the GaugeCluster + DDP + no-foregrounded-main-asset predicate described above. This
identifies a concrete phone-side obstruction; it does not, by itself, prove the entire decoder
or pixel path was otherwise correct.

A later Debug-only pixel probe of the same parked MAIN-DDP condition observed the MAIN
`TextureView`'s first-frame callback at 02:33:28. The view was `shown=true`, `alpha=1`, and
1200×2464 pixels; a 64×64 sample of that texture was `nonBlack=0.0%`. There was no further MAIN
texture update for about one minute, while the ALT map remained visible and the aggregate video
rate was about 20 frames/s. The aggregate rate does not establish a continuing MAIN stream.
Sampling the MAIN texture itself rules out a simple final Compose overlay hiding otherwise
visible MAIN pixels. It does **not** yet distinguish black source pixels from a decoder/Surface
path that fails to produce nonblack pixels. At 02:35:56 the DDP probe was switched off again;
CAF bench remained off. An ordinary type-110 first frame of 14,854 bytes arrived at
02:36:00.770, and the main map was visually restored.

The live MFi leaf certificate serial for this pairing is `01a13e9d3a9d`. The phone's
enhanced-integration query matches that serial to a saved vehicle whose consent status must be
`2`. Accessory capability bit 18 (`0x40000`, `supportsCarPlayAppLinks`) separately influences
enhanced-integration support/prompt eligibility; it is distinct from theme-assets bit
`0x200000`, and the observed `0x301400` capability mask lacks bit 18. The path that could set
consent for this exact active vehicle has not been demonstrated in the live run. The later static
reverse identifies the three Identification message IDs needed for the AppLinks feature, and
the Debug-only declaration is now implemented; this session predates that edit. Neither the
missing bit nor the black pixels establish that the new declaration fixes consent or MAIN video.

The independently opted-in UI Sync/physical DDP path was off in this run. In the 02:15 DDP probe,
the phone's SETUP requested `uiSync`, but this was the **diagnostic DDP-only** path:
`physicalPanels=true`, `ultraUiSync=false`, no accepted `uiSync`, and `/info uiSync:false`.
The phone opened only iAPChannel; it did not issue a `07D9` ClusterControl network SETUP.
The absent enabled bit is sufficient to take the firmware's `-17606` branch before any OEM
Profile decision can be inferred from this trace. An inconsistent `/info.uiSyncInfo` would be a
separate `-16720` gate, not evidence of a signature check.
The black MAIN/lockout therefore cannot be used as evidence that a locally offered static UI Sync
layout was rejected. Neither a ClusterControl channel nor a physical gauge output or
phone-installed theme was demonstrated; this is a live CAF/control and DDP metadata probe,
**not complete CarPlay Ultra**. The newer signed static foreground-request, AppLinks, and
instrument-crosswalk code has not been run against this iPhone in a captured session.

At the latest read-only inventory, Android's active Debug import contained the CAF bench ZIP
but no detached `signature.der`; the separate signed UI Sync opt-in remained false, and this
Mac did not see the iPhone over USB. No device setting, APK or connection was changed for the
new offline tests. Their green results cannot be backdated to the 02:15 phone trace or counted
as a new iPhone Ultra trial.

The next discriminating parked/bench test is conditional on a newly authorized reconnect, not
an inference from the old logs. Keep one exact paired MFi identity and the signed ID-0 known-type
synthetic Profile fixed; enable the truthful, physically preflighted MAIN DDP panel **together
with** the Debug signed single-output UI Sync/Local Canvas candidate; then capture the phone's
requested/accepted SETUP, `/info uiSync:true`, ClusterControl opening and reset/READY, exact
scene-bound raw command 5 `RequestLayout`, and the phone's response/foreground or lockout state.
The current code has signed-scene eligibility, exact per-display Host target/generation checks,
and multi-output batch preflight; this handset has only a verified MAIN physical output, so the
test must not claim a real CLUSTER panel. None of those local receipts is a saved iPhone raw-5
exchange or an accepted foreground transition.
In that **same** DDP-on session, capture CAF config allocation, the source `ultraEnabled` state,
any consuming client/IID registration demand, the resulting `registerRequest`/read, and errors.
Compare with a controlled DDP-off run only after restoring ordinary display behavior. No saved
capture has these joint conditions. A change from zero to nonzero accessories would support the
Ultra-only allocation explanation, not prove OEM certification, accepted vehicle semantics, or
safe gauge presentation; a continued zero requires tracing the first failing stage before
attributing it to a Profile gate. Fixed synthetic speed must remain strictly bench-only and must
never be represented as real vehicle telemetry.

## 2026-10-17 signed MAIN-only bench attempt

The later parked Debug trial supersedes the earlier read-only import inventory above. A signed
MAIN-only Local Canvas Profile ZIP, its P-256 public-key pin, and its detached signature were
staged through the app's SAF import flow. The exact 177-byte MFi-bound signing request matched
the offline expected SHA-256 (`1180276cf86b8bc0c3297ad2b346cc0d9f07a5b92b8257cece1857670ebeeb6f`)
before the result was interpreted. On the Android side, the live
AA05 exchange activated the signed Debug authorization, and the MAIN physical DDP target became
ready. The Host's pre-AA05 startup line `ultraUiSync=false` is a baseline before that activation,
not a post-authorization UI Sync rejection.

This attempt stopped before AirPlay negotiation, so there is no phone SETUP acceptance,
`/info.uiSyncInfo` exchange, ClusterControl session, or UI Sync foreground result to evaluate.
A bounded historical iPhone log showed `0x4301` accepted, saved network credentials, and CarPlay
auto-join starting. The phone's `wifid` was then jetsammed with
`JETSAM_REASON_MEMORY_HIGHWATER` at 14:57:12; after it restarted, auto-join did not retrigger
and the phone selected an unrelated network. The user manually joined the accessory P2P network
later, and Android confirmed an IP-reachable peer, but `airplayd` marked that Wi-Fi association
`IsCarPlay: N` with no CarPlay UUID after `Session Start Timeout`. No RTSP connection followed.
This is a concrete transport interruption and expired session request, not proof that the signed
Profile or Ultra UI Sync was accepted or rejected.

Build-24A446 `carkitd` inspection found that its `wifid` restart callback only reattaches the
Wi-Fi device and refreshes power state; it does not retry the pending CarPlay join. Repeating
`0x4301` creates a new CarPlay session request, so it is not a same-session Wi-Fi recovery action.
Repeating `0x5703` can revisit saved credentials and in-car state but has no proven jetsam-retry
contract. The late manual Wi-Fi join confirmed reachability but did not resurrect the timed-out
CarPlay request; a fresh user-authorized session is required for the next phone-side check.

That additional parked, user-authorized session began at 15:31 without installing a new APK.
The phone joined promptly, requested `uiSync` and `vehicleStateProtocol`, and the same TCP
session's SETUP enabled both. `/info` then reported `uiSync:true` and `vsp:true`; iPhone
`clusterControlChannel=1`, and its 07D9 ClusterControl type-130 RCS opened without the earlier
`-17606`/`-16720` errors. Android authenticated that channel, sent MAIN UI Sync RESET, and
received one encrypted communication carrier; there is not yet a RESET_COMPLETE/READY, raw-5
write/ACK, accepted foreground, or locally revealed MAIN canvas. The installed build silently
classifies some rejected carriers, so a new source-only Debug marker distinguishes malformed
carrier from unknown display without logging payload or identifier; it was **not** installed for
this run. The phone's MAIN appearance had `ddpCap`, and its CAF car showed
`ultraEnabled=YES` but `accessoriesCount=0` for this assetless, generic synthetic CAF tree.
None of these observations proves complete Ultra or a usable gauge accessory.

The iPhone also logged no enhanced-integration consent for launch UI Contexts, and its Theme
Controller remained null with no vehicle resources. All four user-visible `xcertplay` entries
were reported enabled, but no current certificate-serial-to-saved-vehicle match was yet proven.
Reverse inspection separates two other nearby logs from this question: the `no matching vehicle`
message is a capabilities-plist default lookup, and `no ppid` belongs to an analytics path;
neither by itself establishes a pairing or OEM authorization failure. The actual consent query
compares `CARSession.MFiCertificateSerialNumber` with saved vehicles and tests for status 2.
CarKit derives that serial from the authenticated FigEndpoint's AuthenticationData when its
authentication type is MFiSAP or MFiMutualAuth. The available syslog does not distinguish an
unmatched serial from a matched saved vehicle with status other than 2, nor identify which
saved row, if any, matched. The four visible enabled switches do not resolve that ambiguity.

The null Theme Controller has an independent asset gate. In build 24A446, `carkitd` takes
assetIdentifier, assetVersion and sdkVersion from 0x4301 StartSession and updates the vehicle's
cluster-asset identity only when all required values are present. CarKit's theme-asset library
returns no asset when either stored vehicle identifier or clusterAssetIdentifier is absent;
DashBoard constructs the Theme Controller only after finding and decoding an asset layout URL.
This assetless signed MAIN-only bench Profile did not advertise a selected cluster asset in
0x4301 group 7, so its successful UI Sync feature negotiation alone could not create the
iPhone Theme Controller. A user-pinned ZIP binding is not evidence that a matching, authorized
theme asset is installed on the phone.
The phone normally matches assets from its `com.apple.MobileAsset.CarDDPAssets` catalog;
firmware also contains a generic theme-archive receiver branch, but no static retail call that
installs a theme receiver was found in the inspected `carkitd` binary. The project does not
implement an accessory-to-phone theme upload. Static inspection has not established whether a
self-signed archive would pass the phone's eventual verification, so neither acceptance nor
impossibility is claimed.
The iPhone19,7 build-24A446 IPSW's complete SystemOS file inventory contains no selectable
`CarDDPAssets` bundle/catalog record or asset ID; the `CarExperienceAssets` type descriptor in
that image is a different asset family. This does not establish that the test iPhone18,1 lacks
OTA-downloaded assets or that unexpanded Cryptex content is empty.

After the parked trial, the user authorized restoration. The app's own Debug bench controls
disabled both CAF and UI Sync opt-ins, then Host Settings disabled experimental Ultra and its
dependent MAIN DDP probe. Persisted preferences were read back as false. A fresh ordinary
CarPlay session at 15:52 enabled neither `uiSync` nor VSP, received type-110 and type-111 first
frames, and displayed the ordinary CarPlay launcher. The corrected Bonjour receiver-ID and
additional Debug rejection markers remain source-only; they were not installed in this session.

A separate upstream receiver-ID decimal-format patch was applied to source and passed the full
Gradle test, assembly, and lint checks. That patched build was **not** installed for this trial,
so its behavior cannot be attributed to the live result. Full CarPlay Ultra remains unproven.

### 21:32 fixed-APK rerun and restoration

An independently installed Debug APK included the phone-first UI Sync handshake rewrite, removal
of the duplicate tunnel `0x4301`, and an `AirPlaySession.close()` race fix. The handoff reported
1,886 shared Debug, 1,883 shared Release, and 974 common Debug unit tests without failures. A
fresh check of the later current source (with the nonterminal CLUSTER RESET and its pre-ACK
recovery fixes and the load-race fixes below, 2026-10-18) ran 1,928 shared Debug tests (two
skipped), 1,925 shared Release tests (two skipped), and 1,016 common Debug tests with no
failures under a forced `--rerun-tasks` run; Debug APK and common Release AAR assembled, lint
passed, and all 38 Python script tests passed. An earlier shared Release run hit a timing
failure in `AirPlayMainBufferedM4LeaseTest` that did not reproduce in five isolated reruns or
later forced full runs; this source was not installed or run on a device.
Two previously intermittent full-suite failures were traced to production races and fixed:
(1) `ScreenStream.finish()` took the retirement lock that an owner `close()` holds while it
joins the worker, so a close racing the worker's own finish waited out the whole join deadline
and then failed; finish no longer takes that lock (endpoint close is idempotent under
`stateLock`), with a deterministic regression test. (2) A concurrent read-only liveness probe
(for example the local-canvas producer's) made `UltraProfileActivationCoordinator` report
`Busy`, and both its other liveness probes and scene activation read that as revoked, falsely
retiring a healthy AA05 session under load. Probes now wait up to 500 ms for another thread's
probe (any other operation, timeout or interruption still fails closed), and activation/shared
provisioning treat `Busy` as a bounded re-check rather than loss. Trade-offs: under a probe
storm, mutators that return `Busy` instead of waiting see it more often, and a caller holding
the layout-controller lock or an iAP2 callback thread can wait up to 500 ms before failing
closed. The instrument owner's post-RESET epoch guard is now lock-free over `@Volatile` fields
to remove a lock-order deadlock with its raster close. These fixes are offline JVM evidence. Before the shutdown/retirement fixes, two full runs failed at the
same UI Sync test teardown, leaving a process-local physical target claim that cascaded into
41 later failures; isolated classes and an intervening full run passed. Source analysis found
that the layout controller's `shutdownNow()` could interrupt a worker inside a synchronous EGL
status call. Its close now waits boundedly without interrupting that worker, and per-ingress
retirement waits for the exact earlier uncertain EGL control within its existing deadline.
The test fixture keeps close retryable and the EGL diagnostic retains the original failure.
Blocked-worker and uncertain-control regressions pass; these changes have not been tested on
the device. The teardown failure is not counted as a 21:32 device observation. This Gradle project
currently exposes no `:shared:testReleaseUnitTest` task, so an older Release test-results
directory is not counted as a fresh Release test. The real run confirmed MFi AA05 and signed
Debug authorization, an initial `0x4301` carrying theme-asset acquisition metadata, and
no duplicate tunnel `0x4301`. At 21:32:46.762 the AirPlay SETUP response **enabled** `uiSync`;
`/info` reported `uiSync:true`. Both CAF RCS channels authenticated and one CAF `configRequest`
received a `configResponse`.

The next gate did not fire in this run: there was no phone type-130 SETUP for 07D9 ClusterControl,
no Android ClusterControl authentication, no UI Sync RESET/READY, and no raw-5 write or ACK. The
only video SETUP was type 111 (ALT); no type-110 MAIN SETUP, codec configuration, or first frame
was observed. ALT's 15-second stream accept/authentication deadline expired at 21:33:01.892,
then the control session closed. This timeout is the first definite local termination, not proof
of why the phone earlier omitted ClusterControl or MAIN. The nominal iPhone capture file ended
at 20:43 and contains **no 21:32 phone events**; do not infer a phone-side consent, asset, or
OEM rejection from this run. The raw iPhone file may contain Wi-Fi credentials and must not be
published. The two verified capture processes were stopped after the run.

At the user's parked/bench authorization, the app's own settings disabled CAF and UI Sync bench
opt-ins, experimental Ultra, MAIN DDP, and the theme-asset catalog probe. Persisted preferences
were read back as false. The 21:47 ordinary session enabled neither `uiSync` nor VSP and received
first frames on type 110 and type 111. Its actual `/info` dimensions were type 110 =
1200×2464 and type 111 = 1920×720. With Main Web Display off, type 110 follows the Android
portrait viewport. The user then explicitly approved Main Web Display with local preview despite
its same-network browser output. The 22:01 ordinary reconnect advertised type 110 = 1280×720 and
type 111 = 1920×720, with first frames on both. The Android portrait screen displays the MAIN
preview letterboxed; no system `wm size` or density override was made. Ultra, DDP, asset probe,
and both signed bench opt-ins remained off.
The current source also has Debug-only, payload-free type-110/111 screen-candidate diagnostics
to distinguish zero TCP candidates, wrong-peer rejection, exact-peer pre-auth stalls, and a
first authenticated frame; it does not change the 15-second timeout. A signed Debug CAF v3
standard-VHAL speed path now validates source generation, exact iOS speed types/units, single
sample fanout, explicit Android Automotive `CAR_SPEED` permission, and encrypted DATA/DATA2
integration in local tests. Neither source addition was installed or exercised in the 21:32
phone session, and neither is OEM/Profile authorization proof.
The signed v3 real-speed Debug path now also latches a revoked `CAR_SPEED` grant at source reads
and CAF publication, with a lifecycle-owned 100 ms observer to retire both otherwise-idle CAF
streams. A focused encrypted DATA/DATA2 integration test revoked the grant after both channels
opened and confirmed both closed without a new read or probe; the instrument source became
unavailable. Android Car manager identity is published atomically with its generation and a
binding-death callback clears the old connection. These are source/test results only: no
Automotive speed hardware or post-change CarPlay device run has been performed. The 100 ms
observer cadence is not a hard real-time deadline if the OS permission query stalls.
The older signed v1 Android Car CAF path is now rejected if it binds either standard VHAL speed
property or advertises a known iOS speed characteristic: raw meters-per-second values had no
v3 unit projection or two-second freshness gate and could otherwise be mislabeled as speed in
kilometers per hour. Explicitly synthetic v1/v2 bench fixtures remain available; Android OS
permission still guards physical reads. This fail-closed change is also source/test-only.
After this device run, the Debug version-zero `0x4301` acquisition intent was additionally bound
to the current signed Profile's exact `AssetIdentifier`, live UI Sync authorization, AA05 MFi
identity, and each wired/wireless control-send endpoint. A failed check leaves the ordinary
StartSession intact and omits the acquisition metadata; it does not prove a matching asset is
installed on the phone. The old Bluetooth bootstrap channel now suppresses its entire `0x4301`
after tunnel handoff, even when successor and predecessor use the same MFi certificate. These
checks precede a potentially blocking CSM send and are not an atomic no-post-revocation wire
lease. The new private A/B capture tool and its limits are documented in
`docs/debug-ultra-ab-capture.md`; it has not yet been run against the paired devices.
The source-only signed UI Sync path now selects an issued same-layout v3 palette variant on a
standalone phone type-16 PaletteChange without inventing type-3 provenance. It still requires
the exact signed component map, a new scene, Host cover ACK and first-frame proof before MAIN
reveal. Same-channel MAIN RESET invalidates old scene/foreground authority and needs a fresh
signed type-3 to recover; authenticated ClusterControl socket loss revokes the exact physical
batch and enters STOPPING. A same-channel phone RESET of one physical CLUSTER is now
nonterminal in source: Authority phase 1 synchronously revokes only that CLUSTER's old reveal
permits and replay attempts, then a frozen ticket obtains the exact borrowed Host UI-cover ACK
off the RCS callback (at most three attempts). MAIN permits, MAIN output and the issued physical
batch keep running. Recovery needs a fresh signed type-3 above the reset floor, a new scene
revision and EGL producer timestamp above the epoch floors, and first-frame proof (a PRESENTED
scene ticket, plus the same-revision Local first-frame receipt or a sealed instrument replay).
The real instrument owner first overwrites its ingress with a complete opaque safety clear;
dynamic gauge values may draw again only after that new clear is armed. A fresh scene or
safety-clear replay that presents before the Host ACK is held, not dropped: a static scene is
parked and re-evaluated by that ACK, and an instrument replay retries for at most 5 s. Failure
of phase 1, the controller/owner invalidation, the Host cover, the bounded 5 s post-RESET clear
commit, or that pre-ACK replay window still takes the terminal STOPPING protection. These are
offline JVM/source behavior and limitations, not a live phone ClusterControl exchange; no device
has run this path.
The active BAA leaf certificate's `notAfter`, read without exporting the certificate, is
2026-10-19 18:49:26 JST. A changed certificate identity invalidates the current MFi-bound
Debug signature and requires a fresh signing request/signature; iPhone consent may also need
to be checked for the new certificate serial rather than inferred from same-named old entries.

### New-vehicle opt-out and DashBoard theme-asset gate (20:40 log, build 24A446)

The rotated BAA leaf made `carkitd` treat the accessory as a new vehicle
(`isPairedWithCertificateSerial` false) and present CarPlay Setup. During that first-pairing
prompt flow, a StartSession without complete asset information (non-empty
`clusterAssetIdentifer`, numeric `clusterAssetVersion`, non-empty `SDKVersion`, and device
features containing 0x28) from a theme-capable vehicle with no stored
`disabledFeaturesPreference` makes `carkitd` log "Ultra-capable HU delivered Classic with no prior
preference" and disable 0x28 for that vehicle; the next SETUP then omitted `uiSync` and
`fileTransfer`. The prompt flow is presented only for an unpaired vehicle, so this opt-out cannot
be recorded by a later StartSession. Forgetting the car removes the stored preference (it also
removed the Bluetooth bond on both devices in this run).

With the Debug catalog probe sending `bench.asset`/`debug-ios-1`, the phone logged "start
session message has asset information" and SETUP again requested `uiSync`. That same asset
identity, however, enters a second gate. DashBoard's `sessionDidConnect:` defers
`setCurrentSession:` whenever the stored vehicle has a `clusterAssetIdentifier`, feature bit 0x8
(GaugeCluster) is set and the session has a gauge-cluster screen; it then waits for
`CARThemeAssetLibrary` to deliver a matching staged/applied asset, logged "Theme asset not ready
yet." There is no timeout or assetless fallback, and `DBUISyncChannel` (the ClusterControl
client type) is created only from `_updateLayerMetadataService`, which requires `currentSession`.
The 20:40 log matches this: `clusterControlChannel = 1` but no 07D9 RCS. An earlier assetless
vehicle record took the immediate-connect path and opened ClusterControl.
`carkitd` overwrites the stored identifier on every USB/Bluetooth messaging connect from the
identification ThemeAssetIdentifier (nil included; skipped for the AirPlay-tunnel transport) and
writes it again from a StartSession only when all asset fields are present. The current Host
identification sends no ThemeAssetIdentifier. Static analysis therefore predicts that a paired
vehicle reconnecting over Bluetooth with the catalog probe **off** has its identifier cleared and
takes the immediate-connect path, while `uiSync` stays requested as long as the vehicle's
`supportsThemeAssets` (from identification capability bit 21) survives; 0x4300
`themeAssetsAvailable` should then report 0x08. This was independently re-derived from the
binaries but is not yet observed on a device. accessoryd was not available to show statically
how bit 21 is set, but the 16:23 session (identification without ThemeAssetIdentifier, no asset in
StartSession) requested `uiSync`, which requires `supportsThemeAssets` for that vehicle. No locally uploadable theme path
exists on a retail phone; the developer-only local override requires `AccessoryDeveloperEnabled`
or an internal build.

### 2026-10-18 live: RCS outbound type, ClusterControl and UI Sync READY

Parked bench, user-authorized reconnects, iOS 27.0.1, dual-side logs (raw iPhone logs are kept
private; they may contain Wi-Fi credentials).

- **Clean vehicle record.** The "bench.asset" identifier recorded by the earlier catalog probe
  could not be cleared over the protocol (CarKit's `setClusterAssetIdentifier:` keeps an existing
  value on nil/empty). The car was forgotten on the iPhone, re-paired in ordinary mode (the Host
  now claims `themeAssetsCapable` only when Ultra is enabled, so carkitd logged "vehicle supports
  theme assets: NO" and recorded no opt-out and no asset ID), then reconnected with Ultra enabled
  and the catalog probe off. carkitd reported "supports features requiring asset: 40", SETUP
  requested `uiSync`, DashBoard took the immediate session-connect path (no "Theme asset not ready
  yet"), and the phone created the 07D9 ClusterControl RCS. MAIN type-110 and ALT type-111 video
  streams authenticated and delivered first frames.
- **RCS outbound package type.** Every phone→Host APTransport package carries `'asyn'` at offset 4
  and `'comm'` at offset 16. The phone silently ignored all Host packages (UI Sync and CAF alike)
  while the Host sent `'comm'`. Static analysis of iOS 24A446 AirPlaySender (adversarially
  verified) shows its RCS receive handler delivers only `'cmnd'` (plus the internal `'died'`) to
  the RCS client. `RcsChannel` now writes Host `comm` payloads as `'asyn'`/`'cmnd'` and normalizes
  an inbound `'cmnd'` back to `comm` for loopback peers. A wire-level test asserts the bytes.
- **Result after the fix (03:14, reproduced at 03:18 and 03:19).** Phone Reset → Host Reset →
  phone ResetComplete → Host ResetComplete → `UI_READY`; the raw-5 RequestLayout was written and
  **acknowledged by the phone** (`RAW5_ACK`). The phone then sent type 6 echoing the request
  (`layoutID` "main-local-canvas", empty `activeComponents`, fade flags) and type 19
  (appearancePreferenceChange). It sent no type-3 layoutChange, so the Host kept MAIN covered,
  as then designed for a missing type-3.
- **Assetless reveal authority (IDA-verified, iOS 27.0.1 DashBoard).** In a session without a
  phone theme asset, `DBDefaultUISyncSessionDelegate` handles an inbound requestLayout by echoing
  the whole request `data` back as type 6 (`sendLayoutChangeEnd`, unconditionally, with no
  accept/reject field), then calls `assetDidForegroundForDisplayID:` (marks the asset display
  foregrounded and clears the phone-side "uiSync" lockout), then sends type 19. Type 3 is sent
  only by `DBTransitionMetadataHandler`, which exists only in themed sessions, so an assetless
  session never sends type 3; a themed session sends nothing in reply to requestLayout. The Host
  now treats that echo as the matching type-3 authority under a fail-closed rule:
  `CarPlayUiSyncChannel` attests a type 6 only when it equals, in every key and value, the data of
  the oldest outstanding ACK-requesting RequestLayout of the current sequence space with that
  data, whose psn an *earlier* phone packet acknowledged, with no Reset since (the matching type 6
  spends that request; Reset or close spends all); the presentation hub retains that attestation; and the signed-theme controller
  accepts it only as the request-echo form (never the timed form) of the exact raw-5 it issued for
  that MAIN display's signed intent (still the submitted MAIN intent), once, in the same activation
  and semantics epoch and above the Reset floor. The resulting type 3 carries that signed intent's `layoutID`, `activeComponents`
  and `paletteID`, never values from the echo, and then follows the unchanged type-3 path: new
  scene revision, exact Host cover ACK where one is pending, same-revision EGL first-frame/PRESENTED
  proof before reveal, CLUSTER Reset rules and terminal protection. A later Reset invalidates it as
  it does type 3.
- **Standalone type 19 (IDA-verified).** The assetless delegate then sends type 19 `{preference}`
  via `sendAppearancePreference`: an appearance preference statement, never followed by a layout
  choice. It still covers MAIN at once. Like the standalone type-16 palette recovery, the Host
  then re-proves the *same* signed scene as a fresh revision, and only that revision's
  PRESENTED/first-frame proof after the exact Host cover ACK reveals MAIN again. Admitted only
  when the display has a current-epoch signed layout authority (type 3 or the echo) older than
  the type 19, the type 19 is the latest appearance event with no `override` and a `preference`
  in -1…1 (the evidenced appearance-mode domain; the preference enum itself is not wire-proven,
  so the Debug `UI_COMMAND` marker now logs the value), no type 21/22 arrived since that layout
  choice, no other suspension is active, and the issuer returns the exact current variant as
  appearance-invariant. Every issued executable selection is static by construction (static
  layout background, no appearance overlay, no `requiresDarkAppearance`, single-color palette)
  and the v3 catalog has no per-appearance rows, so nothing else is ever selected. Each later
  type 19 repeats the cycle without a new raw-5; other values, an open 21, any 21/22 since the
  layout, or a Reset stay covered.
- **Phone Reset.** A Reset still revokes MAIN and voids the old epoch's choices. Because an
  assetless phone never volunteers a type 3, a MAIN whose submitted scene that Reset covered
  (Authority semantic cover on the same physical batch; a never-submitted MAIN stays pending as
  before) and that is covered only by that Reset now renders its
  primary signed scene as a fresh, still covered revision, and its presented foreground scene
  re-issues raw-5, which the outbox writes when the new handshake reaches READY. The new epoch's
  acknowledged echo (above the Reset floor) then recovers MAIN exactly like the first one. Old
  epoch transition/palette/appearance/opaque/unsupported boundaries (all at or below the floor)
  are dropped at the Reset, including an open type 21: the phone reopened its session and never
  sends that old epoch's type 22.
- **TextureView latch timing (root cause of the first black MAIN).** HWUI latches a TextureView
  frame on its RenderThread *after* the UI-thread `onSurfaceTextureUpdated` callback, so
  `SurfaceTexture.timestamp` read inside the callback names the previous image (the first
  callback read 0). A static signed scene sends no later frame, so the permitted producer
  timestamp (`eglPresentationTimeANDROID`) was never observed and MAIN stayed covered. The Host
  now reads the *latched* image after the draw has synced, at permit arming, and on every cover
  watchdog tick; only an exact match with the permit reveals, a newer latched frame takes the
  missed-frame path, and an older one stays covered (with at most five TextureView redraw
  requests per permit).
- **Live result (03:14–04:55, parked bench, iOS 27.0.1, Debug build).** READY → raw-5 written and
  ACKed → type 6 marked `ackedRequestEcho` (`layoutID` `main-local-canvas`) → echo authority →
  fresh revision → `ARM_LATCHED latched==permitTs` → `REVEAL_MAIN_GATE state=REVEALED`; the
  following type 19 (`preference=0`) covered MAIN, the appearance recovery re-proved the same
  signed scene (rev 3), and MAIN revealed again and stayed stable. This is the first device run
  that revealed the signed MAIN Local Canvas through UI Sync. The device was returned to ordinary
  mode afterwards.
- **Follow-up fixes after an adversarial review (source/test only, not yet run on a device):**
  the CLUSTER Presentation cover now uses the same latched-image observation (its callback-time
  timestamp still drives only the ordinary decoder reveal); a Reset closes an open type 21;
  overlapping RequestLayouts are kept in a bounded FIFO of outstanding requests (an echo spends
  the oldest request with exactly its data and is attested only if that request was ACKed
  first), the controller matches the oldest unconsumed issued raw-5, and an attested echo of the
  already-applied signed intent is a no-op instead of an opaque cover; the echo attestation is
  an unforgeable module-internal token; a scene revocation that arrives only after a complete
  raw-5 write no longer aborts the transport or marks the write uncertain; `themeAssetsCapable`
  is claimed only when this build can reach Ultra (Release never; Debug only with Ultra on, the
  bench and UI Sync opt-ins and an active signed import, because identification is built before
  any session can prove readiness); Release builds no longer log RCS header words, reveal-marker
  arguments or renderer-failure exception messages.
- Debug-only markers: `UI_COMMAND type=… name=… data=<key names and value type tokens only>`
  (only allow-listed UI Sync schema keys are named and a string is printed only when it equals
  a layout ID this Host requested), RX/TX APTransport
  header words, the `REVEAL_*` reveal-pipeline markers (`ULTRA_STATUS event=REVEAL_…` on tag
  `xcertplay-usb`), and the Ultra renderer-unavailable exception chain.

## CarPlayUpdateData transfer boundary

iOS 27.0.1 uses a dedicated `CarPlayUpdateData` type-130 RCS client
`09A8AA2D-8932-4048-9492-6B658D42C2A3`. Each COMM body is one OPACK dictionary, not an
iAP2 session-12 artwork datagram. A header has `messageType=1`, a UUID `sessionID`, theme
`payloadType=1`, integer `version`, `chunkCount`, and optional binary `certificate`, `signature`,
and `additions`. Ordered chunks use `messageType=2`, `chunkIndex`, and binary `data`; the last
chunk includes a lowercase-hex SHA-256 checksum over the whole archive. `messageType=5` carries
integer `accepted`, with zero meaning file receipt. That ACK is not proof of theme authenticity.

The local codec and receiver bound OPACK values, chunks, byte totals, order, session identity,
and checksum. A separate file sink syncs an untrusted archive and exact metadata to private
quarantine before allowing ACK zero. A bounded per-stream RCS handler and conservative recovery
inventory exist. With the experimental Ultra setting enabled, the product now reserves a bounded
quarantine slot only after an encrypted Pair Verify succeeds, conditionally advertises
`fileTransferInfo`, and registers the exact UpdateData RCS handler for that control session.
Exhausted quota or an unavailable root leaves the ordinary session unchanged. Encrypted loopback
tests cover SETUP, OPACK transfer, ACK, clean EOF and quota fallback; no real iPhone has yet sent
a theme through this path. No signature or vehicle binding is asserted by the sink, and received
files stay inactive. An exact UpdateData clean-EOF exception now requires a successfully sent
terminal ACK; input queued before the final ACK downgrades success to rejection, and later input
closes the one-transfer stream rather than being silently dropped. Other RCS streams and abnormal
closes retain session-failure behavior. Before
registration, quota admission and sink ownership are tied to the authenticated session lifecycle.
On iPhone, sending additionally requires a
matching staged MobileAsset and an active session supporting file transfer; feature advertisement
alone does not produce an asset.

The examined `carkitd` sends a phone-local MobileAsset's accessory archive in this direction:
`_assetRequestForVehicle:` (24A446 `0x100059610`) builds a theme download request,
`_mainQueue_evaluateAssetTransferIfConnected` (`0x100056b54`) checks file-transfer support,
and `CRFileSender.sendThemeAsset:` (`0x10003b640`) sends the accessory archive. CarKit's
`layoutURL` (`0x1c170aa68`) instead locates `CarPlayPhone.assets` on the phone for Dashboard's
Theme Controller; `assetsArchiveURL` (`0x1c170aab8`) points at the accessory ZIP. The generic
phone-side theme receiver branch (`carkitd 0x10003a2e4`) exists, but the examined retail
construction (`0x10003e6f0`) wires its receiver for logs/statistics, not theme uploads. This
is negative static evidence for accessory-to-phone sideloading, not proof that no other process
or internal mode can ever use that receiver. Neither a version-zero `0x4301` catalog request
nor UpdateData ACK zero installs a self-built phone theme.

## UI Sync and custom presentation

The UI Sync implementation distinguishes three identifier layers:

- ClusterControl RCS client type: `07D9F906-8D64-4B54-A808-A20BCA2C51C2`;
- UI Sync session channel: `7DED4F02-38A6-4CBA-8731-4B9BA40CB044`;
- the opaque display ID carried under the historical outer `displayUUID` key.

The ClusterControl RCS body is one **OPACK** dictionary `{displayUUID, data}`, not a binary
plist. iOS 27 `-[DBUISyncChannel sendPayload:/sendPacket:forDisplayID:description:]` encode it with
`OPACKEncoderCreateData`, and `channel:didReceiveMessage:` decodes with `OPACKDecodeData`; CarKit
creates the ClusterControl RCS with `SendMessageAsIs`/`SendMessageWithoutReply`, so there is no
`{params:{data}}` wrapper. An earlier build sent a binary-plist outer dictionary, which the phone
drops and which this codec now rejects as a malformed carrier. One physical ClusterControl stream
is routed to independent per-display state machines.

### UI Sync handshake (iOS 27.0.1 24A446 wire model)

Source: `DashBoard.framework` in the 24A446 dyld cache, traced to the instruction level, plus one
live iPhone syslog of the phone's own Reset. Addresses are in that image.

**Representation.** The sender picks it from *its own negotiated version* (send format select
`0x25c7b9a0c`): binary-plist `Data` under `data` while it has none or has v1/v2
(`sendPayload:`), a nested OPACK dictionary only after it negotiated v3 (`sendPacket:`). On
receive, `Data` goes to the payload path (`sub_25c7bec3c`, never version-gated) and a dictionary
to the packet path (`sub_25c7bf12c`), which drops it if the phone has no negotiated version or one
below v3 (`0x25c7bf27c`/`0x25c7bf398`). A dictionary sent before the phone negotiated is
therefore silently dropped; this is consistent with the live log, in which no inbound UI Sync
message was processed after the round-1 build answered with dictionaries.

**Exchange** (S = our ssn = 1; P = our Reset psn = 1):

| # | Direction | Message | Carrier |
|---|---|---|---|
| 1 | phone → us | Reset `{type 0, ssn 1, psn 1, asn 0, id <phone id>, version 3}` at session open (`sub_25c7bb254`); phone enters state 1 | plist `Data` (live log: 137-byte plist) |
| 2 | us → phone | Reset `{type 0, ssn S, psn P, asn 1, id <phone id>, version 3}` | plist `Data` (required) |
| 3 | phone → us | ResetComplete `{type 1, ssn S, psn 1, asn P, id <phone id>, version 3}` (`sub_25c7bb45c`, send `0x25c7bbdec`) | dictionary (phone now has v3) |
| 4 | us → phone | ResetComplete `{type 1, ssn S, psn 2, asn 1, id <phone id>, version 3}`; phone becomes READY (`sub_25c7bdb4c`, `0x25c7be47c`) | plist `Data` (accepted by the unversioned payload path; a dictionary would also be accepted) |

The phone discards a ResetComplete in state 1 (gate `0x25c7bdef8`), so answering the phone's own
Reset with a ResetComplete never completes; only our Reset (accepted in states 1–3, id checked
only if present at `0x25c7bb7c0`) moves it to state 2. It ignores everything in state 0
(`0x25c7bc1ac`) and has no timers, retransmission or self re-Reset after channel open (complete
xref of send and self-reset), so the accessory is **passive**: it writes nothing until the phone's
Reset arrives, and `CarPlayUiSyncMode` now has only `PASSIVE`. Omitting `id` from our Reset would
make the phone attach `{data: <DisplayAssetConfig>}` to step 3 (`0x25c7bbb08`); we always send
the id, so step 3 carries no payload. A Reset without `version` would negotiate v1 (`csel` at
`0x25c7bc1fc`). The ResetComplete id is required for v2/v3 (`0x25c7bdf50`–`0x25c7bdf94`); its
`data` is ignored. A later phone Reset (a reopened phone session) restarts the exchange; a Reset
from us while the phone is READY fires its `didReset`.

**Steady state.** Every message, including a CommandAck, uses psn = the sender's next psn and
asn = its last received psn. The receiver requires ssn = S, psn = last+1 exactly
(`0x25c7bca34`), and lastAck ≤ asn < its next psn (`0x25c7bcab0`/`0x25c7bcae8`); a failure is a
silent drop with no recovery. Phone commands carry no `id` and no `version` (`sendCommand`
`sub_25c7b9208`, `0x25c7b930c`); payload is `{type: <command>, data: {...}}`. Every phone sender
except TransitionData sets `noAck = false`. A command without `noAck` is acknowledged with
`{type 3, ssn, psn, asn}` before delivery (`0x25c7bcb54`–`0x25c7bcb84`); an ACK is never acked.
After step 4 our next psn is 3 and the phone's is 2, so the first phone command is `psn 2, asn 2`
and our first command is `psn 3` (or later, counting our ACKs).

**Phone identifier.** `id` is a phone-generated CarPlay identifier, not the signed Profile's
vehicle binding. The channel learns it from the first phone Reset of each ClusterControl channel
lifetime and requires every later Reset/ResetComplete, and any other message that carries an
`id`, to repeat it; a mismatch is rejected without state change, and closing the channel clears
it. The signed Profile `vehicleId` still authorizes the Profile, runtime manifest and renderer
exactly as before; it is just no longer compared with this wire field, which it can never equal.
This is not a weaker peer check: the peer is authenticated by the AA05 pairing and the encrypted
RCS stream that carries the channel, and the phone only compares `id` with its own session value
(`_stringCompareWithSmolCheck` against `+0xb28`).

**Implementation and tests.** `CarPlayUiSyncChannel` implements the accessory side; inbound
Reset must be plist `Data`, every other inbound message must use the representation of the
negotiated version, and the stale-duplicate path rechecks the representation too.
`CarPlayUiSyncPhoneRoleSimulator` is an executable model of the phone rules above (drops instead
of errors, `lastDropReason`); the router readiness self-test and the unit tests run the accessory
against it, and `CarPlayUiSyncSessionTest` also checks byte-for-byte phone-exact Reset and
ResetComplete carriers. **Unproven:** no live iPhone has yet answered our plist Reset, so steps
3–4 and READY are binary-derived only; `phoneMax = v3` and the Data/dictionary routing in
`DBUISyncChannel` come from earlier notes plus the live `version 3`, not this trace; the command
number table is from earlier notes; what the phone sends first after READY, what clears the
`uiSync` lockout, and whether a production head unit includes `id` in its Reset are unknown.

Debug builds log, at most once per rejection kind per ClusterControl stream,
`ULTRA_STATUS event=UI_CARRIER_SHAPE kind=… fmt=… size=… shape=… err=…`. The line lists the
container format, byte size, dictionary keys with value-type tokens (three dictionary levels) and
the exception class with a reduced message. Only allow-listed key names appear verbatim: the
carrier/message/payload schema keys (`displayUUID`, `data`, `type`, `ssn`, `psn`, `asn`, `id`,
`version`, `noAck`, `config`) and the command-data keys the codec's typed builders and semantic
decoder use; any other key becomes `<key#N>`. In the message, only words from a fixed vocabulary
of this codebase's own error texts survive; local display/vehicle identifiers become `<id>`, UUIDs
`<uuid>`, hex runs `<hex>`, digit runs longer than three digits `<n>`, and every other word `<?>`.
String, number and data values never appear. Release builds do not compute or emit the line.
The shared binary-plist codec also preserves Foundation's signed representation for negative
Int64 values (eight-byte two's complement) independently from the full unsigned UInt64 range.

The default app does not advertise `uiSync`; normal iPhone channel creation is tied to matching
vehicle/theme provisioning, which an integrator must supply before constructing a production
endpoint. Ordinary type-111 video, preview, PIP, and custom WebDisplay remain available without
UI Sync.

The atomic UI Sync provisioning path owns immutable endpoint/static-plan snapshots and opens a
private presentation hub, endpoint registry, and renderer child for each AirPlay control session.
Factory capability and live session readiness are now different proofs. Before a session, the
factory challenge fixes the verified Profile/program/implementation and static display plan; it
cannot claim that displays are already subscribed or physical outputs already bound. Offering
UI Sync additionally requires an explicit, currently valid Host physical-target preflight.
After a session opens, ACTIVE requires observed live subscriptions for every UI Sync display and
one challenge-bound physical batch covering the exact verified Profile output assignments,
static-plan output objects, current Host target generations, and live EGL/router generation.
Stale, missing, swapped or replayed leases fail closed; a changed receipt is not equivalent to
the same issuance. Legacy/base renderer capabilities remain diagnostic-only. Callback dispatch
is bounded and non-inline, and failed retirement retains the exact owner for retry. The signed
Debug static candidate has an exact physical authority; the default/Release composition has no
authorized deployment profile or complete gauge renderer. These contracts are not yet evidence
of a real-phone UI Sync session.

`UiSyncForegroundRequestOutbox` is provisioned per exact AirPlay control session. It accepts only
the session's declared display IDs and a revocable scene/revision/batch guard, waits for that
display's router-observed READY state on the attached ClusterControl generation, rechecks the
physical/session lease before writing, bounds its queue, and cancels pending work on reset,
revocation or close. A typed `CURRENT`/`BUSY`/`REVOKED` generation probe treats a transient AA05
coordinator operation as deferred admission, not as permanent loss or permission to write; hard
revocation remains terminal. An ambiguous/blocked transport write is marked uncertain and stops
further sends on that generation; a successful write is `SENT_UNCONFIRMED`, not an iPhone ACK or
foreground proof. The signed static Debug renderer now calls this port only after its local MAIN
scene evidence, using the selected signed layout and no guessed component mapping. Its
phone-response, Dashboard foreground state and optical output have not been tested live.

The Debug signed owner can attach one exact CLUSTER `UiSyncPresentationSource` to an already-owned
cluster WebDisplay only after renderer/physical-batch activation. `UiSyncSnapshotWebDisplayAdapter`
replaces versioned, bounded JSON snapshots by revision and does not replay historical events into
the legacy event accumulator. The binding is read-only; it neither enables `uiSync` nor substitutes
browser pixels for a trusted physical cluster output. If that optional WebDisplay is absent, the
renderer does not acquire one to manufacture readiness. No iPhone semantic snapshot has reached
this adapter in a saved live session.

The phone's type-111 video remains a remote renderer input. Received commands are direction-gated:
iPhone-to-accessory transition start/data/end, layout-change, end-layout-change, metadata, focus,
palette, appearance preference/boundaries, and drive-mode theme overrides have bounded typed
models. Command 3 carries exact `layoutID`, `activeComponents` (String→String) and `paletteID`
keys. iOS 27 `DashBoard` also constructs that exact triple for a logged layout-transition start;
its final dynamic call edge to command 1 has not been proven by a wire capture. The decoder now
accepts this precise command-1 shape as a pending transition only, alongside the known empty and
DCA/Deck shapes; it cannot select or authorize a layout. Command 6 has two confirmed sender
paths: normal transition-end includes `regions` and `current_ntp_time`; the RequestLayout path
constructs a request-derived opaque form, not a proved byte-for-byte echo. Command 9 metadata
comes from `DBTransitionMetadataHandler`'s `isTransitioning` state change, not a compositor
present fence. Command 7 `GiveFocus` delivers a focus `tokenID`; it is a focus-control event,
not a content draw. The reducer records desired layout and end events only: neither command 6,
command 9 nor a protocol ACK proves that local rendering presented pixels. Accessory-to-iPhone
commands use separate typed builders with exact keys; they are not projected as received state.
Region dictionaries remain deeply frozen opaque source values;
a separate read-only diagnostic view validates the known ordered `identifier`, `opacity` with
`cumulative_opacity` fallback, and 16-value input `transform` / six-value output `transform2D`
fields without making them paint authority. The source's non-finite-value rejection is unchanged.

The signed MAIN controller now performs a centralized visual-boundary preflight on each new Hub
snapshot. For an already-submitted physical MAIN, accepted type-1/2/4 transitions, changed or
unsupported type-3, type-6 end-layout, type-9 metadata, type-16 palette, type-19/21/22
appearance, type-24 drive-mode override, and unknown/wrong-direction events revoke old scene
authority before semantic reduction. This includes AA05 `BUSY`, terminal malformed-only replay
and closed histories, and a type-16 event when the initial scene has no signed primary intent.
Phase 1 synchronously invalidates the old permit; phase 2 obtains an exact Host black-cover ACK
outside the controller lock, retaining retryable ownership if ACK fails. Type-7 `GiveFocus` is
the narrow focus-only allowlist and does not cover MAIN. A CLUSTER event or ordinary non-Ultra
mode does not cover MAIN. A type-4 end or a protocol ACK alone cannot restore the pixels: only a
later valid signed phone type-3 selection can force a new scene revision and fresh EGL/TextureView
proof. Same-batch ordering and CLUSTER/MAIN isolation have direct tests. This is a fail-closed
Android UI-layer lifetime rule, not implementation of opaque transition geometry or proof that
the phone foregrounded the asset. iOS 27 `DashBoard` sends
type-1/3/4/6 from `0x25c6cf424`/`0x25c6cf77c`/`0x25c6cf670`/`0x25c6cf7c0` respectively;
its type-2 Layer Metadata uses presentation timestamps. The accepted command now carries one
receiver-local monotonic sample through Hub snapshot/replay without converting the phone's raw
UInt64 fields. IPSW disassembly shows boot-relative
`UpTicksToNTP(presentation_host_time + screenLatency)` for `presentation_ntp_time`; CoreUtils
packs boot-relative ticks as 32.32 seconds rather than a UTC/NTP wall-clock epoch. The default
latency policy is 25 ms wired / 75 ms wireless, subject to settings overrides. Type-111 video
instead passes PTS through an APSNetworkClock conversion into packed seconds/fraction. The two
values are **not** proven directly comparable, so the product still does not align type-2 timing
with type-111 video PTS for
per-frame Remote/Local/Punch-through composition or schedule frames from the new sample.

A detached one-shot `UiSyncType2FrameCoordinationGate` now makes the missing proof explicit. It
requires the same session/display and validated type-111 stream, an exact applied type-2 reducer
result plus matching latest Hub snapshot, a fresh authenticated frame identity, and monotonic
frame order. A separately issued, current calibration must project *both* raw NTP64 values into
one local monotonic domain with bounded error/drift; the existing media `NtpClock` is not such a
calibration. A separate renderer proof must establish a complete opaque region and its exact
output-space transform for this frame. The currently admitted diagnostic shape is only one
full-opacity region with an explicit finite invertible six-value affine and no extra fields;
unknown clipping, geometry or clock relationship fail closed. Pairing consumes its candidate and
returns only a diagnostic target timestamp, never an EGL-completion, foreground or pixel-authority
permit. Eight direct unit tests cover positive and stale/wrong-scope/uncalibrated/unproved cases.
There is no default calibration or geometry issuer, runtime subscription, scheduler integration,
or live iPhone timing vector.

The separate ordinary-video `NtpClock` now pins both address **and port** for PT210 requests and
PT211 replies. Each reply must match one outstanding local `t1`; even a malformed matching
reply consumes that probe, preventing replay. A short-lived video lease exists only for a
selected and applied measurement within peer, sample-age, error and clock-era bounds. Every
accepted sample in a picker window constrains the selected low-RTT sample's error, and a
contradictory newer sample widens or revokes the old lease. Signed modular NTP64 arithmetic
handles the 2036-era rollover while rejecting an exactly half-era, direction-ambiguous delta;
an absent/stale lease falls back to frame-arrival scheduling rather than an unbounded wall-clock
guess. These are local timing tests, not a verified iPhone timing-source port, optical present
time, or calibration between UI Sync type-2 and authenticated type-111 timestamps.

The authenticated type-111 frame path now identifies header `+0x28` as
`APSNetworkTime.timelineID`, not a second timestamp; `+8` contains its packed `secs`/`frac`.
The timeline ID is retained through the frame receipt and Debug capture but is never converted
to time or equated to type-2. The Debug timing export uses schema `xcertplay-timing-v2` and the
`timeline_id` field instead of the misleading `auxiliary_network_time` field.
An opt-in Debug timing capture binds one signed session/AA05 physical batch, records only bounded
type-2 scalar times, decoder-release and exact EGL-matched frame receipts, and exposes a manual
sanitized NDJSON export with per-session display aliases. Source retention, observer-mailbox and
ring-overflow losses are reported separately. The diagnostic observer uses its own bounded
worker so decoding/serialization cannot hold the renderer callback mailbox; `BUSY` pauses
recording and revoke/close clears it. A 50 ms poll cannot prove that no brief BUSY excursion
occurred between samples; this capture is a clock-calibration aid, not a synchronized scene or
optical display certificate. No live iPhone type-2 capture has been recorded yet.

`UiSyncSnapshotWebDisplayAdapter` projects one complete hub snapshot by revision and never
re-appends retained event history as new commands. Its channel lease prevents a late callback or
an old adapter's close from overwriting a replacement. Custom cluster pages receive versioned
JSON messages:

```json
{
  "type": "clusterPresentation",
  "version": 1,
  "presentation": {
    "revision": 1,
    "commands": []
  }
}
```

The bundled page stores the latest snapshot in `window.carPlayClusterPresentation` and dispatches
`carplay-cluster-presentation`. Existing pages that ignore this message continue to receive the
same video/audio/control framing as before. The adapter and WebDisplay factory seam are implemented,
but the product host does not yet attach them to an authorized UI Sync session.

A resource-level Android coordinator now owns exact remote candidates, local/overlay producer
surfaces, typed outputs, V2 graph publication, resolved EGL submissions, observer barriers, and one
backend-wide retirement proof per control session. It deliberately cannot implement the complete
renderer subtype. A production path still needs an authorized compiled theme/semantic program,
real output destinations and subscriptions that can issue the complete receipt, plus Host wiring
from the AA05-bound profile activation lease.

The authorized construction seam no longer accepts a renderer-selected program identity. Its
profile compiler supplies the expected semantic/theme program and renderer implementation before
factory acquisition; the challenge-bound factory receipt must match both. An issuer-bound,
canonical compiled-runtime manifest additionally freezes the schema/binding fingerprints, full
typed output and ingress records, display set, profile/theme/package digests, and CAF
decoder/factory/handler identities. The same manifest object—not merely an equal digest—must
survive preparation, backend lease, and child creation.

The receiver preserves each screen frame's raw little-endian NTP32.32 presentation value from
header offset 8 alongside its local arrival time. For negotiated UI Sync sessions, it adds the
screen clock's omitted 1900-to-1970 epoch offset modulo UInt64, maps the result through the timing
port's synchronized clock, and uses Android's timed `MediaCodec` Surface release. Outliers,
overflow, missing synchronization, and non-UI-Sync sessions fall back to immediate rendering. The
existing WebDisplay frame format remains arrival-time based.

## Session management

When the experimental Ultra setting is enabled, the wireless host can advertise the observed
`sessionManagementInfo` subset and consume the exact
`stopSession` command with `disconnectReason` 1 or 2. The feature is derived from trusted attachment
provenance, not from the configurable audio-mode flag, so a wired NCM session cannot opt itself in.
Successful stop moves the session to a terminal stopping state before its 200 response is flushed;
new commands and outbound event traffic are then rejected, and the sockets close gracefully after
the response. Unknown fields, invalid reasons, unnegotiated commands, and wired sessions fail
closed. This session-control subset is unrelated to OEM activation or entitlement and grants no
Ultra capability.

## Vehicle data / CAF

The CAF layer implements config, register, unregister, read, write, control, update notifications,
opaque error values, transaction timeouts, priority routing, schema validation, and bounded update
queues. Numeric plugin IDs, IIDs, types, and formats retain their UInt64 wire representation.

No generic vehicle profile is advertised. Android speed, gear, battery, and temperature properties
do not by themselves establish the OEM's CAF plugin ID, IID, units, permissions, priority routing,
or error codes. A production integrator must supply a validated profile and a repository whose
read/write/control operations match the real vehicle. The default/release app therefore keeps
`vehicleStateProtocol` disabled rather than inventing a successful but unsafe implementation.
The user-signed Debug bench is a separate, explicitly selected synthetic test path. The original
ID-7 profile failed to route the phone's ID-0 request. The corrected ID-0 profile replied to a real
`configRequest`, but no subscription or value read has yet been observed. On `24A446`, a parsed
config is distributed before consumers request instance IDs; an empty or cached request need not
produce wire registration. The known VehicleMotion accessory is also Ultra-only and was probed
with physical DDP off. These observations cannot be promoted into proof that the Profile passed
or failed phone-side acceptance.

CAF provisioning now transfers ownership once to the Ultra runtime and opens a fresh child for
each AirPlay control session. Parent revocation immediately closes admission; exact handlers,
construction-cleanup owners, session children, and the factory are retained until retry-safe close
succeeds. Plugin state cannot leak across reconnects, and a frozen legacy factory cannot continue
serving after its authorized parent is revoked. This lifecycle proof does not supply any OEM CAF
IDs or make the default disabled profile usable.

The authorized CAF path is distinct from that diagnostic path. It requires the compiled decoder
identity, an exact plugin-ID-to-handler-factory identity map, and every created handler to report
the same identity while still exposing the frozen schema. Ordinary `CafRuntimeProvisioning.from`
therefore cannot produce authorization evidence.

An externally verified, read-only Android Car profile can now build one explicit IID-to-property/
area repository behind independent DATA and DATA2 handlers with matching decoder/handler IDs and
signed error values. That bridge distinguishes protocol response readiness from actual Car Service
connectivity: before connection or after disconnect, reads return `operationFailed` rather than
invented values. It rejects writable characteristics and controls. The debug bench provider may
select it with an explicitly signed read-only CAF mapping; the release root does not. The
connected Android phone has no Android Car Service; no physical
vehicle value has been tested. After a subscribed value has actually been published, a subsequent
source loss or bridge shutdown retires that channel so the peer sees EOF instead of silently
retaining a stale last update. This does not invent an unverified CAF invalid-value wire format.

## Cluster assets

Asset identifiers and versions are opaque. The preinstalled-asset metadata path is eligible for
iAP2 0x4301 only when its exact catalog entry is confirmed installed and locally authorized. A
separate, default-off acquisition intent binds an ID and SDK version to the actual MFi identity
and emits version zero to request a phone catalog refresh. It neither claims that the asset is
installed nor depends on interpreting the earlier 0x4300 availability byte as a Boolean. The
product has no authorized preinstalled selection. Its experimental Host setting can request the
zero-version path only after an explicit
asset ID/SDK and exact live MFi certificate-type/identity-digest pin match; it is off by default
and has not been exercised against a real iPhone.

The repository contains no Apple theme bundles, certificates, or signatures. Reverse evidence
confirms the UpdateData and LoggingData client UUIDs, feature names, `/info` presence keys and
dedicated-socket `comm` carrier. UpdateData's header/chunk/checksum/terminal ACK subset has a
bounded, quarantine-only receiver and encrypted loopback tests; its ACK is file receipt, not
asset authenticity or installation. LoggingData's application schema remains unknown.

### Preinstalled profile-package quarantine

`UltraProfilePackageInspector` provides a fail-closed boundary for deployment-supplied,
preinstalled profile candidates. It accepts the evidenced root artifacts
`CarPlayPhone.assets/`, `CarPlayAccessory.assets.zip`, `Certificate.chain.pem`,
`CarPlayAccessory.assets.sha256`, `AccessoryAdditions`, and `Info.plist`.
`CarPlayPhone.assets` is a directory, not a flat file: its non-empty root `package.json`,
`theme.json`, and `Info.plist` are required, while its bounded nested resource tree is preserved.
Other top-level directories, unsafe paths, central/local ZIP disagreements, links, duplicates,
undeclared files, excessive sizes/counts/ratios, and malformed outer binary plists are rejected.
The accessory assets ZIP is retained as opaque bytes and is never recursively extracted.

The inspector does not infer Apple certificate, signature, or checksum semantics from those file
names. `Info.plist` is parsed only as a bounded binary-plist dictionary. Activation identity is an
explicit verifier binding over the vehicle ID, MFi/certificate identity, asset identifier, opaque
iOS/accessory content versions, and a canonical digest of every allowed file. With no externally
provisioned verifier, a rejection, a verifier failure, or any field mismatch, no
`VerifiedUltraProfile` can be produced. All retained package, manifest, digest, and proof bytes are
defensively copied. A deterministic canonical ZIP is streamed from the inspected root files and
the complete `CarPlayPhone.assets/` tree; attacker-controlled ZIP metadata is never exposed to a
second parser downstream. `UltraProfileStore` writes that stream into an immutable content-digest
directory, fsyncs files and directories, atomically switches a strict active pointer, and reopens,
reinspects, and reverifies it for each load. A failure after the pointer rename is reported as an
explicit durability-uncertain commit rather than as a false rollback.
Verification alone does not advertise or install the profile and does not
enable CAF, UI Sync, cluster-asset metadata, or UpdateData; those still require separate local
installation and authorization policy. Experimental, quarantine-only UpdateData reception is a
separate opt-in path and does not activate a verified profile.

The release/default Android composition root is deliberately `Disabled`; the debug build has a
separate, off-by-default, user-signed CAF-only bench selection. MFi certificate capture creates
only a redacted identity for binding policy. A session receipt is privately issued per control
authentication attempt only after the same exchange has sent AA03 and then receives AA05. It is
single-use and bound to the exact captured MFi session, iAP2 control-session object, and monotonically
advancing control generation. An ordinary new/invalidation attempt revokes the preceding generation.
For the wireless Bluetooth-bootstrap to type-130-tunnel transition, an explicit predecessor-bound
handoff can keep the old receipt live while AA05 produces a candidate. Promotion first registers
and consumes the successor through a required callback, then revokes the predecessor; callback
failure rolls the candidate back without silently killing the bootstrap generation. The profile
activation coordinator can atomically renew the same manifest/runtime tree to that successor.
The successor carries an exact predecessor-lineage proof, so a receipt from another authority with
the same certificate digest is insufficient. Production issuance also binds the exact authenticator
object and a one-shot live exchange claim; every consumed receipt requires an explicit revocation
callback. Failed callbacks and MFi session/construction cleanup owners are retained for retry.
There is now a controller-facing product-session boundary that performs the strict
`beginSession -> prepare -> commit -> openRuntime` transaction and returns the runtime and
configuration from the same exact lease. Its release vehicle-identity authority is `Unavailable`,
so the default path exits before constructing a coordinator or
consuming the AA05 receipt. It also retains the exact coordinator on close failure and exposes an
identity-bound liveness probe at each session admission. The product Host now owns this lifecycle
through its controller and atomically binds the AirPlay configuration and runtime after AA05.
Wireless tunnel renewal is two-phase: the handoff callback prepares the exact successor, and the
controller publishes it only after the outer MFi commit succeeds. Admission closes before sessions,
media, and the authorized root retire in that order. None of this makes an unavailable deployment
provider or vehicle identity usable.
The coordinator additionally binds the receipt certificate kind and SHA-256 identity to the
vehicle ID and verifier-produced profile identity, uses generation reservations for
prepare/commit, and retires leases on reconnect or close.

The coordinator now validates a process-issued compiled manifest against that exact profile and
receipt before publication, then constructs the final `AirPlayUltraRuntime` itself. Callers receive
a revocable runtime lease rather than raw UI Sync/CAF provisioning. Closing a child retires the
runtime first, then backend provisioning, then the backend lease; a failed child stage retains the
same object for retry and prevents its parent from being reported retired.

These mechanisms are not an OEM authorization proof. The repository still has no pinned Apple/OEM
profile trust roots or authorized profile compiler. `UltraProfilePackageInspector.verify` is a
low-level verification harness whose caller-supplied verifier must never be installed as a product
trust authority. A production provider must replace it with a statically selected cryptographic
verifier, pinned deployment roots, and an authorized compiler for verified profile material. Until
those exist and the complete activation path is device-validated, the release provider remains
`Disabled`; the debug bench provider can supply explicitly signed CAF v1 reads, v2 in-memory
synthetic mutations, and an independently opted-in static UI Sync test catalog bound to real Host
outputs. It supplies neither Apple/OEM trust nor a complete gauge renderer or phone theme asset.

### Theme and renderer foundations

The parser covers the confirmed `package.json` hierarchy (display, layout, zone, component, slot,
instrument and input-stream presentation), `theme.json` layout styles, the direct-map
`asset_manifest.json`, and resolved palette/layout defaults. JSON input is strict UTF-8 and is
bounded by byte, depth, node, collection, string and numeric budgets; duplicate decoded keys,
non-finite/unbounded numbers, BOMs, comments and trailing content are rejected. Unknown confirmed-
container fields remain immutable opaque values. Accessory Lua is retained only as inert data and
is never executed.

Read-only CarPlayAssetUI symbol inspection confirms the `Instrument.Kind` associated values
`inputStream(String, VisibilityConfiguration?, InputStreamPresentation?)`,
`punchThrough(String, InstrumentDataIdentifier)`, and
`punchThroughType(PunchThroughType, InstrumentDataIdentifier?, InputStreamPresentation?)`.
Local Swift `JSONEncoder` reproduction confirms their synthesized tuple keys `_0`, `_1`, and `_2`.
The `PunchThroughType` and `InstrumentDataIdentifier` symbols expose `String` raw values, and their
Codable witnesses use the RawRepresentable helper, supporting a string wire form without assigning
meaning to unknown values. The schema therefore exposes strict typed views for the confirmed
strings and presentations while retaining visibility configuration as bounded opaque data; the
complete original payload remains available as an immutable opaque snapshot. Unknown tuple keys
are rejected, while nested `InputStreamPresentation` keeps the parser's explicit forward-compatible
extension map. The diagnostic scene reducer carries these typed identifiers, references, and
presentations without rendering them or enabling any capability.

Symbol types confirm `Display.size` and `Component.size` as `CGSize`, and `Zone.frame` and
`Slot.frame` as `CGRect`. Their Swift JSON wire forms were independently reproduced as `[w,h]`
and `[[x,y],[w,h]]`; CarPlayAssetUI's own SwiftUI `EdgeInsets` conformance encodes
`[top,leading,bottom,trailing]`. The strict geometry decoder exposes those as finite logical values,
including negative origins, without inventing positivity constraints or applying Android density.

iOS 27 CarPlayAssetUI decodes required `Layout.backgroundColor` as SwiftUI Color from either four
CGFloat channels or a hex string with optional `#` and exactly 3, 6 or 8 digits; eight digits use
trailing alpha (`RRGGBBAA`). The local compiler now emits a deterministic solid background fill
for those forms, imposing finite unit-range channels as a local safety rule rather than attributing
that restriction to Apple. The confirmed Display dynamic-color wrappers now have strict typed
`single.value` and `dynamic.value.lightMode/darkMode` views; style 2 selects dark. Palette
`GradientColor.stops` also has a bounded typed String→Color view, including the legacy single
wrapper. Spatial interpolation, stop ordering behavior on iPhone, and visual layering remain
unproven; parsing them does not make the theme renderer ready or activate a product Profile.

`Instrument.Kind.gauge` now has a strict `gauge.configuration` view: `style`, `rendersLocally`,
and `visibilityConfiguration` are required. The confirmed 13 `GaugeStyle` cases retain full
bounded payloads; `circular` exposes its nine required fields and `[lower,upper]` range,
`textElement` its required `dataType`, `shapeLayer` its required `pathElements`, `imageGroup` its
required `states`, and `color` its dynamic ColorID envelope. Progress-bar's nested `type` remains
opaque. None of those structures establishes CAF IID mapping, interpolation, labels, drawing or
safety behavior, and an active gauge still prevents complete renderer readiness.

`Instrument.Kind.indicator` now has a strict but non-rendering configuration projection:
`dataIdentifier`, `style`, `presentationEdge` and `containerInsets` are required, with optional
`vehicleLanguageIdentifier`. The four observed styles (`critical`, `animatedSymbol`, `selector`,
`message`) retain bounded opaque associated payloads. This does not establish a CAF IID/unit
crosswalk, warning priority, animation timing or local pixels; active indicators remain outside
the executable static renderer subset.

Further iOS 27 inspection limits what can be inferred from the phone-side model. `SlotViewBuilder`
accepts a `Slot`, `Instrument`, and selected layout ID and returns an external SwiftUI view;
`DataSourceManager` looks up an `InstrumentDataIdentifier` plus optional zone region. The
examined `InstrumentView` branches do not contain a complete numeric circular/text/progress
gauge rasterizer. OEM gauge geometry, animation, thresholds, and CAF-to-identifier mapping are
not recoverable from these branches alone. An Android renderer for a user-owned Profile should
therefore expose an explicit, verified-slot external-view extension and read-only typed data
sources, without claiming pixel equivalence to an OEM gauge.

Apple's [CarPlay Ultra architecture session](https://developer.apple.com/videos/play/wwdc2024/10111/)
separates four pixel roles: iPhone-streamed Remote UI, vehicle-rendered Local UI for gauges and
driving-related indicators, vehicle-rendered Punch-through UI for custom camera/assistance
content, and a separately composited vehicle-system Overlay for essential telltales. UI Sync
coordinates Remote/Local/Punch-through frames; the Overlay is not a replaceable custom Canvas
slot. The app's signed Local Canvas work addresses only a narrow Local UI extension and does not
implement the complete renderer, Punch-through integration, or physical safety Overlay. The session's
speedometer example consumes three concurrent, vehicle-prepared speed presentations; RPM uses a
value and state. `CAFCombine` exposes corresponding speed/speedKMH/speedMPH and RPM-state
observables, while `CarPlayAssetUI.DataSourceManager` resolves a typed
`InstrumentDataIdentifier` and optional zone region. These are separate interfaces, not proof
that Theme `dataIdentifier` equals a CAF IID. The detached `InstrumentSignalBundle` now validates
an explicit exact Theme slot and multi-signal CAF service/characteristic type, unit and state
binding, then reads/subscribes to one read-only repository with whole-bundle stale/unavailable
handling. Its runtime class alone has no Profile digest/issuer or active session binding and is
not a renderer-ready Gauge. The optional signed Debug
`CarPlayPhone.assets/xcertplay-instrument-signals-v1.json` declaration is compiled against the
exact verified Profile instance, phone package, Theme display/slot and CAF tree. The manifest
issuer independently re-parses its assignments and binds their digest and CAF schema to the same
runtime; a forged equal-looking assignment is rejected. That declaration alone does not start a
subscription or authorize a renderer. The separately gated Debug child now rechecks its exact
signed CLUSTER assignment and current physical batch before opening one owner-scoped read-only
source. It first uploads a full-frame `DATA UNAVAILABLE` reference, then may rasterize only the
admitted circular speed/RPM or critical indicator subset on its own dedicated EGL texture and
CLUSTER output. Every frame visibly carries `BENCH / NOT VEHICLE SAFE`; stale, revoked, missing
or future-dated source readings clear it or request whole-output retirement rather than reusing
old numeric pixels. A completed EGL upload/swap/finish is not proof that unsafe pixels were
optically blanked on hardware. `VehicleStateResult` carries source-acquisition age per
IID, and a bundle rejects missing, future-dated, or stale acquisitions rather than mistaking a
recent cached read for fresh telemetry. The Android Car bridge derives that age from source event
timestamps on the monotonic clock; synthetic bench values remain synthetic. Arbitrary custom
Canvas must not replace or obscure the independent
safety-critical Overlay.

The raster owns one reusable mutable ARGB canvas, captures **one** complete opaque copy per
admitted submission, then transfers that owned array through the coordinator to the EGL command
without another full-frame copy. EGL keeps one direct RGBA staging buffer per instrument ingress
and reuses it only after `glTexSubImage2D` and `glFinish`; uncertain upload retires the staging
object. A typed `BUSY` preflight avoids even allocating the owned copy when a transient
generation operation cannot admit the frame. Source expiry is checked again **after** the copy
and before handing numeric pixels to EGL. The estimated app-side full-frame transfer load is
roughly 128 → 32 MB/s for the representative high-resolution update rate used in review; this
is an allocation/copy-path estimate, **not** a device-memory-bandwidth or latency measurement.
It does not include GPU-driver copies or guarantee that old numeric pixels have been obscured
optically before the next scan-out.

The initial unavailable upload does not by itself open the data source or reveal an external
screen. The child waits for an exact instrument-only CLUSTER scene swap/finish replay receipt
for that safety-clear frame, then opens the source asynchronously. Every later instrument upload
or MAIN-triggered scene replay has its own sealed sequence, scene/graph revision, output batch
and output generation, instrument ingress/frame generation and EGL producer timestamp. The
bounded, serialized asynchronous replay outbox keeps these callbacks off the EGL thread; an
out-of-order older completion is `Superseded`, not a hard revocation of the newer frame. Overflow,
output/ingress generation change and true revocation still invalidate the observer. The owner
issues a private `SignedInstrumentContentProof` only for current signed content: a complete
safety CLEAR or a Numeric reference whose source is still fresh. It rechecks the committed
content and source age before a permit can be armed. The physical Authority separately checks
the signed assignment, exact AA05/physical batch and Host target. Its typed
`ARMED`/`BUSY_RETRY`/`REVOKED` decision and bounded arm ledger preserve one sealed receipt and
producer timestamp across a transient Host-arm or coordinator BUSY state, without treating an
ambiguous or stale result as an authorized new frame. `TextureView` must report that exact
timestamp before the black UI cover can reveal the frame.

The current joined JVM test constructs the signed Debug declaration and issuer, consumes AA05,
opens the real renderer child and Authority, drives the real EGL render thread through a fake
driver, and exercises the Host cover with fake Surface/Texture objects. Its three tests verify
the BENCH-marked initial `DATA UNAVAILABLE` full-frame CLEAR, reveal only on the exact timestamp,
and re-cover after AA05 or physical-trust revocation. A separate Core-backed integration fixture
has two passing tests for the initial CLEAR and for one sealed CLEAR surviving physical
BUSY→CURRENT without uploading or sealing a second frame. The Authority arm ledger also has
dedicated retry/timeout tests. These are software and hardware-driver stand-ins: they do not
show a real phone UI Sync ACK, a native numeric physical sample, an external optical scan-out,
OEM asset trust or Release readiness. Neither EGL completion nor an Android TextureView callback
is a hardware display-present or optical safety proof.

Code anchors: `common/src/debug/java/com/shilapi/xcertplay/bench/BenchInstrumentSignalProfileCompiler.kt`,
`common/src/main/java/com/shilapi/xcertplay/AuthorizedUltraCompiledRuntimeManifest.kt`,
`shared/src/main/java/com/shilapi/xcertplay/vehicle/ultra/instrument/InstrumentSignalBundle.kt`,
`shared/src/main/java/com/shilapi/xcertplay/vehicle/ultra/AndroidCarPropertyVehicleStateRepository.kt`,
`common/src/main/java/com/shilapi/xcertplay/ultra/instrument/InstrumentSceneCompiler.kt`, and
`common/src/main/java/com/shilapi/xcertplay/ultra/UltraInstrumentReferenceSlotOwner.kt`.
Frame-path anchors: `common/src/main/java/com/shilapi/xcertplay/ultra/instrument/OwnedOpaqueArgbFrame.kt`,
`InstrumentRasterOwner.kt` in the same directory, and
`common/src/main/java/com/shilapi/xcertplay/ultra/egl/InstrumentRgbaUploadStaging.kt`,
`UltraEglClusterReplayOutbox.kt` in that directory, and
`common/src/testDebug/java/com/shilapi/xcertplay/bench/BenchSignedInstrumentAuthorityEndToEndTest.kt`.

For a separate MAIN-only Local Canvas experiment, `scripts/create_debug_bench_profile.py`
accepts `--main-local-canvas --main-width N --main-height N`. The paired dimensions are bounded
to 64–4096 logical units per edge and at most 8,388,608 units of area. They change only the
signed Debug v2 package's MAIN display, full-frame zone/component/Empty-slot geometry; the
catalog remains `main-local-canvas` with no remote slot ingress, and density remains 1. This
does not alter the actual Host Surface, physical millimetres, iPhone font scaling, or the
ordinary CarPlay DPI setting. The generated 1920×720 case compiles in the Debug test suite;
the flags are not a production display calibration or a live phone result.

The EGL path can now carry one selected, fully opaque `FillLayoutBackground` through an exact
per-output render scene and clear the corresponding physical Surface before video layers. It
requires an explicit layout address, matching viewport and a caller assertion of full-viewport
mapping; default scenes still clear transparent. Output color-space fidelity, non-opaque blending,
zone/slot paint, and the product scene subscriber remain unproven or absent.

A detached bound-scene executor loads selected JSON only from one exact verified Profile, compiles
the confirmed fixed-background/rectangular-video-slot subset, binds exact session streams and
output objects, and submits a complete multi-output scene. It preserves component-local layer
order and fails closed on unsupported instrument painting, masks, transforms, gradients, or
duplicate display roles. The narrow CLUSTER reference path requires its separate exact signed
crosswalk, dedicated raster owner and current physical batch. Manifest v9 now signs a bounded
per-display catalog: the initial layout plus each
additional selected layout/state/proof and its exact InputStream ingress map are independently
re-derived from the Profile. The issuer also derives the semantic/theme program fingerprint from
that verified Profile and complete catalog; renderer receipts must match it rather than choose
their own identity. The v1/v2 static selector accepts only exact signed `layoutID`/`paletteID`
tags with empty `activeComponents`. Signed v3 adds an exact verified-package crosswalk for a
bounded nonempty component map; unknown maps remain rejected rather than inferred from wire text.

A detached, session-owned controller can replay and reduce type-3 layout changes per display,
select those signed variants, retain unaffected outputs, and submit a monotonic complete scene.
Transient AA05 coordinator BUSY results keep the latest complete replay for bounded retry;
revocation and terminal semantic failures stop submissions and retire the exact subscriptions.
The signed Debug candidate installs this controller; the default/Release renderer root does not.
Authorized submissions still reject
stream rebinding and unissued extra video layers; the diagnostic custom-layer path remains
separate. No trusted deployment compiler or complete product renderer issues these assignments,
and an EGL enqueue ticket is not a display-present fence or protocol ACK. These pieces cannot mint
complete renderer readiness or serve as a safety-critical gauge renderer.

Local and overlay EGL producer Surfaces now require an explicit physical pixel buffer size before
handoff; remote video retains decoder-owned sizing. This addresses accidental tiny custom buffers
being scaled up, but does not resolve phone display DPI or prove actual color/geometry on hardware.
Each queued scene and frame replay now checks all output targets and current EGLSurface dimensions
before drawing; a resized output fails closed and requires a new output/backend binding rather than
rendering with an old viewport. Native attach rechecks target validity before publishing a lease.
EGL retirement requires successful thread unbinding (or checked thread release) before reporting
resources safe to release. These are lifecycle protections, not an atomic multi-screen present or
a completed color-managed theme renderer.

Theme geometry is logical CarPlay geometry. It must not be multiplied by Android `density` or
treated as Compose `dp`. The explicit logical-to-pixel mapper supports caller-selected affine
mapping, fit/fill anchors, and four documented quantizers without consulting Android density.
Negative extents remain an explicit caller policy rather than being silently normalized.

The diagnostic scene plan resolves Zone frames in display space, Component origins at zone-local
`(0,0)`, and Slot frames in component/zone-local space before accumulating display coordinates.
Primary content precedes overlay components and stable slot z-order. Transition state selects the
confirmed `none`, `zoneShape`, or typed custom-image mask branch. Custom-mask start/end CGRects are
retained with an explicitly unknown coordinate domain. Instrument slot-shape clipping, internal
input-stream bounds clipping, and component transition clipping remain separate. Because the
external UIKit provider's mask-versus-`clipToBounds` order is not evidenced, that combination is
marked unsupported/fail-closed rather than guessed. The plan remains diagnostic-only.

The V2 display graph keeps topology IDs, output IDs, optional UI Sync IDs, and session-bound remote
ingress IDs as separate typed namespaces. Ingress and output cardinalities are independent: one
remote ingress may fan out to several output scenes, one output may compose several ingresses, and
an output may be local-only. A complete scene resolves against one immutable graph revision and
carries the exact resource objects forward, rather than re-querying mutable registries by text.
An older sanitized iPhone SETUP log contains dynamic `streamConnectionID` values for type 110/111
and a `uuid` matching each corresponding advertised `/info` display. That is evidence for a
session-time UUID-to-handle binding, not proof of current-firmware DDP `ScreenID` mapping: the
log contains only legacy `displays`, and the current saved trace omits the raw SETUP dictionary.
A signed static Profile cannot predeclare those dynamic connection IDs. An opt-in UI Sync selector
now binds the advertised display UUID and stream type, validates uniqueness at SETUP, then retains
the exact session-bound handle; legacy exact-ID selectors remain available. Missing/mismatched UUID
does not match the dynamic selector. Type-only matching would be ambiguous and is not an
authorization proof. This code has not yet been checked against a fresh iPhone SETUP capture.

An API 28-compatible EGL14/GLES2 backend now consumes those resolved plans on one render thread.
It uses external-OES `SurfaceTexture` ingresses, independent output window surfaces, bounded control
and render queues, per-output physical-pixel scenes, context/surface-loss terminal states, and
explicit decoder/GPU retirement proof. It is a real backend primitive, but not a complete product
renderer: output `eglSwapBuffers` calls are serialized and are not atomic across physical displays,
so a later output failure can follow an earlier successful swap in the same revision.
The local-Canvas ingress issues a receipt after its first successful Surface post. The EGL
foundation then requires the SurfaceTexture callback, `updateTexImage`, required local-layer
draws, all scene-output swaps and GPU finish for the exact scene and physical generations. On a
later layout, a reused Canvas can be carried only under a frozen handle lease and the prior
post/proof; EGL still redraws and swaps every required output for the new revision. A genuinely
new Canvas must post a fresh frame. If a custom painter posts again or a reused source cannot
prove carry, the controller keeps the prior proven scene and reports `PENDING_LOCAL_CANVAS`
rather than claiming the new scene or poisoning the session. A callback is not an exact
producer-buffer ID, and neither receipt proves panel scan-out, pixel identity, simultaneous
cross-display presentation, or phone acceptance. No optical capture or iPhone UI Sync
acknowledgement closes that gap.

The resource-level Android per-session coordinator can allocate exact graph ingresses, expose
local/overlay producer Surfaces for custom UI, attach typed Android outputs, publish only after
SETUP flush, submit resolved scenes, and retire the backend once under a bounded global proof.
It now has a separate V2 batch-output mode that publishes its scene graph only after the
EGL/Router batch is healthy and committed; a clean failure retains the old graph, while an
uncertain commit retains exact targets and closes readiness. Its legacy `attachOutputs` remains
explicitly sequential and may report a successful prefix.
A separate production EGL/SurfaceRouter batch binder now stages a complete replacement, then
publishes one logical generation; a failed second output leaves the old generation current, and
stale leases cannot detach its successor. An unchanged physical target/destination/size can
transfer its EGLSurface into a successor without an old lease closing it; changed wrappers,
destinations, or sizes require a new target. The binder holds an exclusive backend token so
single-output or public batch APIs cannot bypass its target-close ownership table. Failed
EGLSurface or owned-Surface cleanup remains owned for retry.
This is atomic *routing publication*, not simultaneous `eglSwapBuffers` or a SurfaceFlinger
display-present fence. A detached authority now validates an issuer-bound manifest, exact AA05
generation, real renderer child/coordinator identity and Host target versions before retiring
legacy decoder routes and committing the physical EGL batch. It is not installed by the product
composition root. A clean failure before any EGL external use closes its exact Surface
reservations and does **not** restore the old decoder: Host withdraw could otherwise release the
native Surface during restoration. This can leave a preview blank until a fresh bind. An atomic
borrow-to-Legacy ownership transfer is still required to restore visible output safely.
The sink starts a type-wide preview exclusion before capturing the legacy binding; old and new
WebDisplay sessions cannot attach during the handoff. After retirement, the old exact Surface is
tombstoned for preview use, while a distinct successor Surface may be bound by a new session.
An unchanged live TextureView therefore does not automatically recover its legacy preview after
an Ultra handoff. The Host now captures a process-coordinator teardown ticket for the old
controller/sink and a registry-issued receipt for the exact old MAIN borrow. Only successful
controller close and `awaitClosed`, sink close, old-borrow retirement, and a clear process barrier
allow withdrawal of the old View. It then installs a new `TextureView`, publishes a new
`PHYSICAL_MAIN` Surface/SurfaceTexture generation, and attaches the new ordinary decoder or
WebDisplay preview route. The sticky black cover is removed only after that new, exact route's
first texture update. A browser-only WebDisplay with preview disabled may reveal with its video
View hidden, since it has no local frame to await. Old callbacks and a coincidentally clear
barrier cannot substitute for the matching ticket or a fresh Surface generation. This recovery
is local lifecycle code with JVM tests; no post-change device reconnection has been validated.
WebDisplay close now detaches only its exact preview route and leaves a failed detach retryable.

A real coordinator-backed renderer factory/child now verifies every selected executable static
variant, owns a live EGL probe, and opens fresh replay subscriptions. The fixed product authority
receives the exact issued physical batch only after the session owns it, then submits its selected
scene under the same AA05 provisioning generation. A signed Debug Profile may therefore reach
the local UI Sync readiness path when its Host DDP outputs are live. These construction receipts
do not prove that an iPhone accepted ClusterControl or that a frame was visibly presented. The
static theme subset still excludes unsupported Gauge and dynamic drawing semantics.
It now admits a selected component's non-transition `zoneShape` clip only when its Zone is a
single, zero-radius rectangle: the shared static proof binds each InputStream slot to that exact
logical Zone rect, and the common executor re-derives an exact integral physical clip from the
verified program for both Remote and signed Local Canvas layers. EGL intersects it with each
slot frame and the viewport. Fractional/out-of-viewport mapping, circular or rounded Zones,
custom masks, transition clipping and native-instrument clipping remain rejected. This supports
one more static custom layout composition case; it is not a complete Apple theme renderer.

The EGL scene model also has an optional, renderer-private affine for Remote and Local textured
quads in explicit output-pixel coordinates. Identity remains the default; a pure geometry
projection supplies the same transformed vertices and final-pixel rectangular clip to draw and
preflight. The dedicated CLUSTER instrument, Overlay and Punch-through paths reject nonidentity
transforms. This adds compositor capability without assigning iOS type-2 `transform2D` any pixel,
z-order, clipping or clock meaning; no phone metadata is mapped to it yet.

The opt-in signed Debug v3 catalog adds several variants of the same Layout, keyed by the
complete `(layoutID, activeComponents, paletteID)` intent. Its verified-package crosswalk
requires unique Layout/Zone/Component IDs and exact mapsMedia/dca
`Zone.id → Component.id` primary selections; `single` receives no guessed map entry and `deck`
remains unsupported.
Only empty-payload, zero-radius rectangular carousel Zones enter the static renderer proof.
Issuer code independently re-reads v1/v2/v3 signed catalog rows, digests, initial variant and
route/ingress/Canvas assignments once at issuance; the executor binds the complete selected intent.
An immutable issuer-owned index keyed by exact Profile instance, display ID and full signed
`(layoutID, activeComponents, paletteID)` intent now serves type-3 lookups without repeating
SHA, package parsing or scene compilation on that hot path. The lookup still checks issuer
manifest/program fingerprints, while each live caller separately checks its provisioning
liveness; a matching string alone cannot mint a variant. The Debug
`--main-carousel-canvas` generator emits three same-layout variants with distinct custom Canvas
content IDs while preserving the v1/v2 hashes. For a styled signed v3 Layout, issuer and
executor re-create the witness for each selected palette from the same verified phone bytes;
only the selected palette/ColorSet must satisfy the narrow inert-style check. A fake-EGL
integration test presents palette-a and then same-layout palette-b as distinct authorized scene
revisions. The reviewed initial-primary regression is now closed offline: the signed initial
styled primary must use its verified inert default palette. An alternate B needs an exact later
phone type-3 or, for the same signed layout and component map, a standalone type-16 palette
choice with distinct provenance. Both paths synchronously revoke the old permit and wait for
exact Host cover ACK outside the controller lock. A repeated type-3 or valid type-16 cannot reuse
the old scene; recovery needs a new signed revision, EGL presentation and matching TextureView
timestamp. Offline tests pass. This still does not prove a
visual recolor, phone acceptance or optical scan-out. Variant validation remains bounded work at
issuance, but repeated type-3 traffic no longer repeats full Profile hashing/parsing/compilation.
A different or unsupported latest phone type-3 revokes the
old foreground/raw5 authority; raw5 carries the signed component map. iOS 27 static code
confirms the carousel crosswalk for mapsMedia/dca, but not whether Deck joins the final wire map.
No real phone UI Sync capture or
phone theme-asset acceptance validates v3 yet, and Release still denies activation.

Type-111 diagnostics now carry the authenticated AEAD per-frame sequence and raw wire NTP bits
through the exact video handle and MediaCodec input/output PTS to an immutable successful-release
receipt. The EGL owner reads the actual `SurfaceTexture.getTimestamp()` after `updateTexImage()`;
a bounded one-shot matcher joins only the same handle, Surface lease, EGL ingress generation and
expected Surface timestamp. It neither equates phone type-2 time with type-111 network time nor
claims scene swap or optical scan-out. The Host sends release receipts through a bounded
non-blocking per-sink relay, so an EGL/authority stall cannot block the decoder. A full EGL
command queue retains one dirty frame per ingress and retries when space returns; ordinary
Remote rendering continues even when a diagnostic match is absent. No consumer yet uses these
receipts to schedule synchronized Ultra frames.

The Host now keeps a process-owned, generation-tagged inventory of its actual TextureView
Surfaces. The main view can provide a borrowed physical output target with exact pixel size;
the floating cluster view is explicitly `PREVIEW_ONLY` and cannot satisfy a physical cluster
assignment. Resize/replacement/destroy revokes old borrows, while Surface and SurfaceTexture
release waits for both the legacy decoder and any EGL borrowing owner to retire. This inventory
does not by itself switch output routing or prove a second Android physical display exists.
On the currently connected Android handset, a read-only DisplayManager dump shows one active
built-in display; its other listed display is an inactive private 1×1 virtual display, not an
external instrument panel. The secondary `Presentation`/DisplayManager candidate and Host route
are wired, but default physical-trust providers deny admission. An actual external instrument
panel, independent connector attestor and hardware validation are still missing.

The external Host `Presentation` starts with an opaque black `DATA UNAVAILABLE` Android View
over its `TextureView`. The legacy decoder can reveal pixels only while its exact route is
current. Ultra handoff first claims the exact borrowed target and reapplies the cover; an older
legacy update cannot reveal it. An Authority permit is minted only from a signed, current,
presented EGL scene—or from an owner-checked dynamic instrument replay—and binds the physical
target version, Surface, output generations and producer timestamp. The same `TextureView` must
report that exact timestamp for the cover gate to reveal, and ordered cover sequences prevent a
late reveal from overtaking a newer cover. AA05/physical revocation, replacement, teardown and a
process-owned liveness watchdog request cover again; borrowed target cleanup is withheld until
the exact cover application is acknowledged. Failure to dispatch that UI operation retains a
retryable owner instead of claiming physical retirement. This is a best-effort Android UI-layer
guard: a `TextureView` callback is not an exact producer-buffer identifier, and neither that
callback nor an applied View proves optical blanking, independently ordered hardware telltales,
or scan-out. The joined signed-Debug JVM fixture establishes the Authority → Host software reveal
path for the initial safety CLEAR and exact timestamp, plus re-cover on AA05/physical-trust
revocation, using fake EGL driver and Surface/Texture objects. It has not measured this path on
the user's external panel or established an optical safety result.
Code anchors: `common/src/main/java/com/shilapi/xcertplay/ExternalClusterPresentationHost.kt`,
`HostPhysicalDisplaySurfaceRegistry.kt` in that directory, and
`common/src/main/java/com/shilapi/xcertplay/ultra/AuthorizedAndroidUiSyncPhysicalOutputAuthority.kt`.

MAIN now has a separate black Android View above its video `TextureView` and below its gesture
layer. It starts `GONE`, so ordinary CarPlay and the existing custom WebDisplay do not acquire an
extra visible layer. An exact physical MAIN handoff claims that Activity/Surface/SurfaceTexture/
Host-version generation and acknowledges an opaque cover before any renderer bind. The signed
static scene controller can issue a MAIN reveal permit only for its current, presented scene and
physical batch, after required Local Canvas post/carry and EGL swap/finish evidence; the permit
binds the producer timestamp. The Host reveals only when that same `TextureView` reports the
exact timestamp and all session, batch, scene, and target authorizations are still current.
Transient `BUSY` preserves the exact permit while covered for retry; `REVOKED`, a new scene, or
target replacement covers and invalidates the old reveal. A later scene cannot inherit an
earlier scene's already visible frame. A skipped timestamp (permit T, first observed update U>T)
remains covered: the gate reports the exact missed permit only after its opaque-cover ACK, and
an older delayed T cannot reveal. The authority may request at most two fresh submissions of the
unchanged signed scene chain, after rechecking the exact child, batch, target, Activity generation
and scene revision. Its off-main retry is bounded to two seconds and twelve `BUSY` retries, then
fails closed. Each submission needs a new revision's Local Canvas carry/post and EGL swap/finish
evidence plus the *new* exact TextureView timestamp; it sends no extra raw-5 `RequestLayout`.
Transparent remote-only content remains covered. The gate/controller/authority targeted Zulu 17
tests and the post-reproof full build passed. Cover ACKs are Android View state,
not an optical scan-out fence, hardware blanking proof, or independent safety overlay. The MAIN
path has JVM/fake-driver tests, not a live iPhone Ultra or post-change device result.
It does not change `/info` millimetres, Android display density, or the scaling setting.

## Evidence-only foundations

The confirmed `mainBufferedInfo` shape contains only `DCXEnabled`. `audioFormats` remains a
top-level `/info` array, while `audioOutputFormatsExtended` is read from matching entries inside
that array; neither belongs in the sidecar. The strict type-103 SETUP/control model also preserves
the confirmed `srat`, `strt`, `anch`, `fshb`, `apvf`, `loud`, `mata`, `magc`, `upck`, and `uppr`
tags, including the confirmed `packetFormatAPAP` flag. Read-only reverse evidence also contains a
CarPlay-specific negative result for APAT support. Future main-buffered work is therefore scoped to
the evidenced APAP path rather than adding a speculative APAT path.

The detached APAP foundation now implements canonical UIntV values; the exact 15-byte
CMTime/timescale/UInt24-sequence header; bounded opaque extension TLVs and the ID-zero terminator;
the four-byte big-endian BufferedAPAP TCP length (including its own prefix); and the 32-byte
APTransport `sync`/`rply` control header. Its AEAD path requires the exact 32-byte shared key,
leaves the 15-byte header clear, authenticates its first 12 bytes, encrypts the bytes after offset
15, and appends a 16-byte tag plus the explicit UInt64-LE nonce. Directional codecs enforce nonce
continuity, zeroize keys, and become terminal on malformed/authentication/provider failures.
Unknown extension IDs remain opaque; only evidenced framing is represented.

An additional detached session runtime can consume an already-established, already-authorized
`BlockingDuplexByteStream` and an exact 32-byte key. It incrementally accepts fragmented or
coalesced inclusive-length frames, bounds allocation before trusting the peer length, decrypts and
decodes through the same strict codecs, and serializes delivery through a bounded backpressure
queue. Clean EOF is not published until admitted callbacks drain; truncated EOF, replayed or
non-monotonic nonces, authentication failure, callback failure, and transport failure are terminal.
The exact transport, workers, and private key copy use success-only, retryable retirement. A
separate one-shot TCP listener now binds an explicit local address, restricts the peer/candidate
count, and waits for a first authenticated APAP packet under an absolute deadline before exposing
its session. A detached v27 transaction now validates the sender's raw 32-byte `shk`, freezes the
supported port mode, prepares that listener, and commits it only after a complete SETUP response
flush. Its modern response uses `streamConnections` → `streamConnectionTypeAPAP` →
`streamConnectionKeyPort`; the older mode uses `dataPort`. Its media preparer is not installed and
it is not registered with AirPlay negotiation.

The AirPlay control session now has a latent type-103 branch: only a negotiated `mainBuffered`
feature and a non-null media preparer could reach that transaction. Both remain disabled in the
product. The branch owns a prepared transaction through response flush, exact TEARDOWN and close;
it never routes APAP payloads into the legacy RTP sink. A separate detached `mctl` decoder/framer
now accepts bounded APTransport packages supplied by an already-authenticated caller, validates
`sync` command tags and parameters before dispatch, and preserves request correlation in a
validated `rply`. An additional peer-pinned TCP preparer now derives its own PairVerify-bound
directional keys, admits only an authenticated APTransport command after the shared post-SETUP
gate, and fail-closes the paired APAP owner if its control stream dies. It is not constructed by
the product media engine or advertised as readiness.

Additional iOS 27 AirPlaySender disassembly separates buffered audio's MediaDataControl plane
from the APAP TCP data socket: it creates an APTransport stream of type `mctl`, derives a distinct
encryption context from a seed, attaches message callbacks, and sends `fshb` there. When the
sender's MediaDataControl capability is set, its type-103 SETUP request adds a separate
`streamConnections` entry for MediaDataControl and reads that entry's separate response port.
The seed is formatted as an unsigned decimal UInt64 in `DataStream-Salt%llu`; verified Pair
Verify's raw X25519 shared secret is the HKDF-SHA512 input for distinct 32-byte
`DataStream-Input-Encryption-Key` and `DataStream-Output-Encryption-Key` outputs. On the
accessory listener, receive uses Output and send uses Input. APTransport TCP uses authenticated
records `[u16-LE length][ciphertext][tag16]`, length as AAD, up to `0x4000` plaintext bytes
per record, and independent zero-based UInt64-LE nonce counters. Decrypted records carry the
existing APTransport package stream. These keys are not interchangeable with the raw 32-byte
APAP `shk`. Receiver description feature bit 59 controls
`SupportsAudioStreamConnectionSetup`; the separate MediaDataControl capability requires both
bits 59 and 60, an enabled debug policy, and source version at least `450.1` (`0x0044AA84`).
The observed 32-bit `audioBufferSize` has an internal default of 5,000,000. AirPlaySupport
computes its underlying property from maximum audio bytes/second multiplied by buffer-duration
seconds, then stores a UInt32: its source-side unit is bytes, not frames or milliseconds. The
actual iPhone type-103 SETUP has not yet confirmed the response value or usable output capacity.
The product must not dispatch
`sync`/`rply` control over the raw APAP socket or declare that default as allocated sink
capacity. The detached type-103 transaction accepts a
MediaDataControl request only when an independent, peer-pinned `mctl` preparer is injected. It
returns both distinct ports, commits both after the complete SETUP response flush, and opens a
shared admission gate only after both commits succeed; otherwise it retires both and never emits
an APAP-only false success. A dual-port admission receipt now confirms the exact `mctl` worker
passed that gate before the transaction claims commit; later control failure retires APAP as
well. The injected preparer path has loopback tests, including an independently computed
HKDF-SHA512 vector, but no iPhone `mctl` capture/vector or installed product construction site.

An additional detached PCM16/48k product-preparer candidate composes that APAP/mctl transaction
with the strict PCM ingress, epoch-gated playback owner and an explicitly allocated native
output. It accepts only an exact wireless type-103 stream and a fresh, generation-bound lease.
`AirPlaySession` now publishes that lease generation only after the M4 RTSP response has fully
flushed and its control cipher is installed. A type-103 request is frozen into a bounded,
request-aware ticket before any socket, output or key borrow; the child lease binds the exact
peer, M4 generation and newly allocated receiver stream ID. Within that M4, separate replay
ledgers reject reuse of the request's connection ID, MediaDataControl seed **or** APAP `shk`
fingerprint even when the other fields change. Only one child may be active. After exact type-103
TEARDOWN, a fresh child is possible only once the prior transaction, media owner, lease and raw
secret borrow are proved quiescent. Pending retirement retains its selector and returns 503 for
retry; an ambiguous failure remains tombstoned. The scoped borrow lends only the verified raw
X25519 secret to `mctl`, and its owned bytes are zeroized on return. Sensitive RTSP-body,
decoded-plist and ControlCipher plaintext copies are wiped on the controlled close/failure paths
without wiping unrelated stream payload aliases. Replacement M1 and STOPPING revoke the exact
generation; no peer address or `isVerified` flag alone can substitute for the lease.

`MainBufferedV27SetupSession` now owns a two-phase PREPARING slot: it reserves one slot while
holding its monitor, performs APAP and optional `mctl` socket bind or rollback outside that
monitor, then publishes only after the same uncancelled slot is revalidated under lock. `close`
can cancel without queuing behind a blocked bind and waits a bounded time for its completion.
If the binder has not finished, the M4 preparation fence and exact cleanup owner remain
retryable rather than reporting retirement or exposing a partially bound port. The two
listeners still must reach their closed-gate READY state before shared ADMIT after complete
SETUP response flush. Focused type-103 tests (227) and the final full unit suite pass; this
remains an offline construction/lifecycle result, not a phone handshake. The production lease
intentionally supplies
`sourceByteOrder = null`: M4 authentication does not establish actual APAP PCM byte order, so the
PCM preparer declines before opening output. The app injects no preparer or `mainBuffered`
feature, and no real iPhone type-103 exchange has been captured. Actual PCM packet format,
phone-compatible output capacity and clock mapping also remain unverified; rate-one playback
without independent clock calibration is rejected. Native AudioTrack `play` and `write` now
reserve in-flight owners, perform potentially blocking calls outside the admission lock, and
compensate late completions after revocation. This is software retirement, not a verified
independent hardware mute before audible output.

The extracted iOS 27 `AirPlaySupport` library maps PCM16/48k/stereo format bit `0x8000` to
`lpcm` at four bytes/frame, while AAC-LC 44.1k bit `0x400000` maps to `aac ` and 1,024
frames/packet. The modern buffered sender's default preference starts with AAC-LC 44.1/48;
therefore a broad type-103 mask must not be offered by a PCM-only receiver. CarPlay sender code
sets the BigEndian flag in its transport PCM description and clears it for local HAL. The generic
`0x8000` ASBD has flags `0x0c` (little-endian PCM); that describes a generic supported format,
not the actual CarPlay APAP transport samples. The sender's `spf` is sourced from the selected
ASBD's `framesPerPacket`: PCM's value 1 does **not** prove that one APAP payload contains one PCM
frame or justify `payloadSize == spf*4` for a real phone. AAC's 1,024 frames/packet likewise
does not prove one AAC access unit per APAP packet. A real SETUP plus authenticated APAP payload
capture is still needed before choosing PCM endianness, packetization or AAC decoder admission.
`magc` carries codec-opaque cookie data and `upck` carries a separate PKD key; neither can be
guessed or ACKed as a codec configuration without a validated parser/owner. The extracted library
is retained for further read-only analysis under `/private/tmp/xcertplay-type103-dsc.LE6XdC`.

A detached AAC-LC ingress and Android MediaCodec decoder now cover the strictly selected
44.1/48-kHz stereo format bits and a two-byte AAC AudioSpecificConfig. It accepts only a
caller-supplied experimental one-raw-access-unit-per-APAP fixture, bounds each unit, and keeps
AudioTrack silent until the exact authenticated control owner has a queued PCM head, calibrated
clock anchor and native play receipt. Cookie changes require a new stream; flush/pause retire
the old epoch and its decoded/native output. JVM fake-driver and race tests cover these guards,
but the phone's APAP packetization, cookie shape and actual Android codec behavior have not been
captured or exercised on hardware. Static iOS 27 code passes each compressed CoreMedia sample
through one APAP encoder call and copies that sample's bytes without altering them. It confirms
a 24-bit sequence and media-time scale equal to the selected sample rate, but not that one sample
is one raw AAC AU, that its cookie is exactly two ASC bytes, that packet media time always advances
by 1,024, or that `magc` completes before the first media packet. The real cookie comes from
`CMAudioFormatDescriptionGetMagicCookie`. This is a candidate decoder path, not a reason to
broaden product format negotiation.

`mainBuffered` remains unadvertised because the detached preparer is not installed with an
authorized source byte order and readiness issuer. The repository also lacks verified live
AAC/ALAC packetization and cookie handling, network-time-to-AudioTrack anchor handling,
flush/reconfigure/key-generation orchestration,
PKD-protected rotation (the detached port rejects `upck` without a dedicated handler), and a
single readiness receipt proving listener, control dispatcher, timeline, decoder and sink for the
exact session. The codec tests alone are not runtime readiness.

The examined iOS 27 `carkitd` LoggingData receiver sends OPACK requests
`{messageType:3, request:1}` for a log archive and `{messageType:3, request:2, interval:UInt}`
for statistics. The accessory sends an archive header (`messageType:1`, `payloadType:2`), indexed
data chunks (`messageType:2`), or an opaque statistics dictionary (`messageType:4`,
`payloadType:3`); the phone returns `messageType:5` with `accepted:0` on complete save or `5` on
its observed save-failure path. A detached LoggingData codec and size-bounded one-archive sender
now implement that direction from an explicitly injected source. The `interval` unit, archive
generation rule, full statistics dictionary and any other ACK status remain unverified. There is
also an exact-type RCS handler whose bounded serial worker receives authenticated requests,
streams at most one archive, and handles its terminal ACK; its factory is disabled by default.
There is no trusted archive or telemetry producer, confirmed `logTransferInfo` sidecar, or
feature-readiness registration, so `logTransfer` remains off;
UpdateData's incoming-theme quarantine is not reused as a log sender.

The confirmed `videoPlaybackInfo` shape contains the required Boolean
`videoPlaybackAllowed`. The accessory-originated `setVideoPlaybackAllowed` and
`videoPlayerBackButtonEvent` commands can include the local player's optional bounded
`playbackStatus`; the phone-originated `changeVideoPlaybackToAudioOnly` command is consumed by a
readiness-gated local backend. These directions are intentionally not interchangeable. The
foundation remains independent from feature negotiation, and `VideoOverlayUI` and
`VideoSettings` remain metadata-only RCS types. Detached codecs now describe a narrow evidenced
part of their application framing, but neither type has a registered semantic handler.

The confirmed MatchPoint RCS client UUID is `A6B27562-B43A-4F2D-B75F-82391E250194`. Its `comm`
carrier wraps a binary plist in `params.data`. A strict, exact-stream diagnostic receiver runtime
now covers bounded queue/control/property/playback messages and the evidenced direction in which
the iPhone requests a streaming key and the receiver returns a typed top-level `key` response.
The request alone uses `params`; a response may contain Data or a string, and only valid Base64
strings expose decoded bytes. `authorizeItem` responses preserve the observed optional top-level
`item`, `PIC-Data`, and `playerGUID` values without inventing inner schemas. Terminal state is
published before bounded backend notification, transport close has explicit pending/complete/
failed truth, and diagnostics omit identifiers and URLs. It has no descriptor or factory, so it
cannot advertise VideoPlayback. A separate session-bound, bounded playback planner now sequences
the confirmed insert/remove/stop/seek/setRate commands without networking or registering a
handler. The runtime also has an optional exact-session PIC issuer port: the default still rejects
`authorizeItem`, while an injected issuer must complete authorization before a correlated success
reply is sent; borrowed request bytes are wiped and an expired reply cannot cross the send fence.
No product issuer is installed. Read-only iOS 27 analysis found that its MatchPoint local server
accepts a 16-byte key derived by HKDF-SHA512 from the PairingSession's internal 32-byte secret,
using `Pair-TLS-PSK` as salt and empty info. This project derives a candidate from the verified
Pair Verify X25519 shared secret with those same parameters; equality of the two sessions' input
secrets is not yet confirmed by a phone vector or successful handshake. The server requires TLS
1.2 or newer and enables only PSK-AES128-GCM-SHA256 (`0x00A8`) and
PSK-AES128-CBC-SHA256 (`0x00AE`). Its PSK identity/hint arguments are null; the client's actual
identity bytes are not proven. The local URL uses a dynamic bound host and `/1/…` route, supports
GET and Range responses, and has no observed redirect path. None of those facts makes ordinary
Web-PKI HTTPS an authenticated substitute for TLS-PSK. An exact AirPlay session
can now lend that 16-byte material only after verified M4 response flush and control-cipher
setup. The transient copy is wiped after use; a future TLS transport must register as a
session-owned `Closeable` before the claim completes. New M1, STOPPING, failure, and close revoke
claims, while a blocking or reentrant transport close retains one retryable owner rather than
silently treating it as retired. A detached Bouncy Castle TLS-PSK source now uses only the two
observed TLS 1.2 suites, an explicitly supplied (still unverified) client identity, an exact
AirPlay peer and Android Network, per-route authorization, bounded HTTP/Range parsing, and
whole-response deadlines. Local PSK handshake and slow-response loopback tests prove these
mechanics, not iPhone interoperability. A separate session-owned Media3/HLS player accepts only
independently revocable PIC, park and output grants; it fences delayed commands, checks every
manifest/segment/key request and buffered playback transition, and requests an immediate output
cover on revocation or asynchronous player error. It is not registered in the product, has no
product PIC issuer or trusted physical cover, and never claims first-frame or optical proof from
playlist acceptance. An optional exact-session factory is now owned by `CarPlayMediaEngine`;
missing authorities yield NOT_READY without claiming a PSK or player, and AirPlay STOPPING asks
its physical route to cover before any TLS retirement wait. Construction and failed-close owners
remain in a per-session retry journal. The product installs no authority provider.
VideoPlayback remains disabled; VideoOverlayUI and VideoSettings have only
detached carrier/subset-schema components, not registered RCS semantic handlers. VideoOverlayUI
also has a local, in-memory scene reducer; VideoSettings still has no settings UI.

The iOS 27 AirPlay sender also contains a distinct
`carEndpoint_checkAndUpdateVideoOverlayActivation` gate driven by screen entity and video-playback
state. Its diagnostic explicitly rejects CarPlay Video Overlay on Bonjour-discovery-based
accessories. The `AirPlayOverlaysServer` path (`0x24ea4db54–0x24ea4dbb8`) serializes one
`RenderActionProtocol` dictionary to binary plist (`0xc8`) per `FitnessUIOverlay` RCS message;
the opposite direction accepts NSData and parses a plist. Further AirPlayKit inspection found
that render `action` is a String with 12 literal values (`0x24e9da6e4`): `createLayer`,
`updateLayer`, `insertLayer`, `removeLayer`, `userAction`, `createAsset`, `removeAsset`,
`transaction`, `style`, `createAnimation`, `animate`, and `info`. The encoders at
`0x24e9cb83c`, `0x24e9cbaf8`, and `0x24e987604` confirm typed ring/text creation and update,
sub-layer insertion/removal, and UUID strings. The asset encoder at `0x24e9807bc` uses a
different envelope with `name`, raw `data`, and a manifest including the literal MIME values
`image/png` and `video/JPEG`; it does not base64-wrap asset bytes. `style` now has a strict
detached typed subset beyond numeric `opacity`: `origin` is a Point whose x/y axes each carry a
tagged `absolutePoint`, `fractional` or edge-and-offset `anchorPoint`; `size` is a width/height
pair of tagged `absolutePoint`, `offset`, `fractional` or `thatFits` Dimensions;
`centerAnchor` is an x/y Float-modeled pair; and `transform` is the six Float-modeled affine
fields `a,b,c,d,tx,ty`. The codec preserves finite decoded Float32/Float64 values without
silent narrowing. An image layer may carry the exact inner asset `identifier`, but the enclosing
ImageContent alternatives remain opaque. These are source-coordinate shapes, **not** Android
pixels or dp. DeviceInfo `scaleFactor`, `safeArea` and unit conversion, visual z-order, animation,
response ordering and most drawing semantics remain unproven. Render actions omit `messageID`; that
field belongs to a separate request/response path. A detached bounded classifier now recognizes
the exact observed ring/text `createLayer` and `updateLayer` shapes, layer insertion/removal,
numeric opacity, and named asset creation/removal (including optional sprite dimensions). It
retains the original bounded plist, preserves `Double` numeric precision, and leaves all other or
extended shapes opaque. The exact `transaction` envelope contains an ordered bounded `functions`
array. The decoder accepts only known nonrecursive children; a malformed, unknown or extended
child makes the whole bundle opaque. A separate detached `CarPlayVideoOverlaySceneSession` copies
and validates a candidate layer/asset graph, then commits a valid transaction as one revision;
duplicate IDs, invalid parent/child order or cycles, wrong layer subtype, out-of-range opacity,
invalid tagged geometry and per-scene limits leave the previous state unchanged. A layer snapshot
can retain its typed origin, size, center anchor, transform and image asset identifier atomically
with the rest of its fields, but does not resolve screen coordinates or renderability. Snapshots
defensively copy asset bytes,
and revocation clears the scene. This reducer draws no pixels, sends no ACK, owns no RCS channel
or renderer, and registers no VideoOverlayUI capability. There is no live touch vector,
animation execution, user-action semantics or response-ordering proof.

`CarPlayVideoSettings` is a separate `CARSessionChannel`, gated by
`videoPlaybackSupported` in `carkitd` (`0x1000436c0–0x1000436d8`). Its outer body is OPACK:
phone messages 1–4 carry initial subtitle settings, diagnostics Boolean, licenses request and
analytics request; type 5 carries binary-plist property replies/updates. Accessory property
requests use OPACK `params.data` containing a plist with `controlMessage` values such as
`properties`, `setProperty`, and `showSettings` (`0x100041150`, `0x1000419e0–0x100041a60`).
The detached codec models the confirmed direction/shape and bounds opaque property values.
Read-only firmware inspection identified four property names: DND button state and closed-caption
enablement as Foundation `NSNumber` values (the phone uses `boolValue`, not only literal 0/1),
selected caption style as a String, and custom caption styles as an array of dictionaries with
19 optional typed fields. Only closed-caption enablement and selected style were observed as
non-nil in the initial relay; the other two names are not evidence of live values. A separate
read-only property projection preserves unknown names or malformed known values as opaque, with
defensive copies of nested collections and Data. It is not registered, does not drive settings
UI, and has no live iPhone vector. Both video
control channels remain metadata-only in the RCS catalog and independent of MatchPoint's
playback path.

`CarPlayDisplayTopology` freezes and validates the observed physical-display geometry, origin,
physical size, z-order, panel identity namespaces (`ScreenID` versus `UID`), and bounded opaque
stream/plugin payloads. Its holder validates complete updates before atomically advancing a
revision, so a malformed update preserves the old snapshot. It is not advertised or connected to
the renderer until the product owns matching independent surfaces and decoders.

## Security and lifecycle

- Type-130 SETUP requires completed pair verification and encrypted AirPlay control traffic.
- RCS listeners bind the exact session address, accept the expected peer, and promote a candidate
  only after its first AEAD-authenticated frame.
- An authenticated event-channel disconnect terminates the control session; reconnecting cannot
  restart ChaCha20-Poly1305 counters under the same pair-verify keys.
- Active streams use `(type, streamConnectionID, clientTypeUUID)` identity, allowing concurrent
  ProtocolData, ProtocolData2, ClusterControl, and iAP channels.
- Video decoder, frame, configuration, selection, and Surface routing use an opaque stream handle
  bound to the private AirPlay session epoch. Identical wire identities from different sessions do
  not share decoder state or retirement watermarks.
- Decoder Surface leases use two phases: publish a generation-bound detach/replacement request,
  then wait for the decoder worker to acknowledge that it switched away or released the decoder.
  The same `Surface` object cannot cross active generations or decoders. Timeout, interruption, or
  `MediaCodec.release()` failure is not retirement proof; ownership is retained for retry and the
  caller fails closed instead of releasing or reusing the Surface.
- A prepared listener commits only after its own SETUP response is flushed; failed responses roll
  it back.
- OPACK, binary plist, APTransport, RCS, CAF, UI Sync, transaction queues, and retained custom-UI
  values have explicit size/depth/count/time limits.
- Profile authenticity may only be supplied by a future fixed deployment trust verifier. The
  open-source product configures none; structural self-consistency, a callback that returns success,
  or possession of package bytes is never treated as Apple/OEM authorization.
- The Debug composition root captures identity authority, deployment provider, manifest issuer
  and Ultra/UI Sync consent in one immutable policy epoch. A new Debug import, consent write or
  Host Ultra-toggle mutation first revokes the old epoch's admission and obtains exact old MAIN
  **and** CLUSTER Host UI-cover ACKs outside root/Store/coordinator locks, before publishing a
  replacement. A failed ACK preserves the old published configuration and an exact retryable
  retirement owner; it never treats a timed-out cover as success. Import staging starts this
  fence before creating a new ZIP copy, so a refused cover does not accumulate failed copies.
  The physical Authority also freezes active and in-flight borrowed-target tickets across bind,
  narrowing the partial-bind acquire/use TOCTOU gap; Debug resource close retains failed ACK
  work for retry. These are Android UI-layer covers, not optical or hardware blanking certificates.
  Release remains fixed `Disabled`/`Unavailable` with external-display trust `DENY_ALL`.
- Activity recreation detaches UI surfaces without owning the protocol runtime. Presentation state
  is replayed when the local UI or browser reconnects. Controller UI lifecycle replay is revisioned
  and exact-session-bound; delayed ended/host-UI/view-area failures from an old session cannot
  overwrite its replacement.
- Controller teardown first detaches AirPlay sessions, then closes the media engine and its shared
  Ultra provisioning. Per-session UI Sync descriptors and command controls are never shared across
  reconnects.
- A UI Sync child that activates a physical batch but fails before `openSession` returns now
  propagates its retryable cleanup through the Ultra runtime to the exact AirPlay session. A
  failed close keeps `onSessionEnded` pending rather than leaving only a process-root journal.
  The child is registered with its exact AirPlay stop-cover owner *before* a potentially blocking
  physical bind. STOPPING is published atomically without waiting for a command/renderer callback;
  the current MAIN and CLUSTER Android UI covers are requested before command-dispatch joining,
  TLS-PSK retirement or source quiescence. If physical bind is still in flight, cover cannot be
  proved and close stays retryable. Each borrowed MAIN or type-111 target retains a one-way
  ACK from its captured old gate through Host version replacement and partial backend-retirement
  retries, without covering a successor. None of these ACKs proves optical/hardware blanking.
- VPN/AirPlay service attachment uses a controller cancellation authority plus an exact generation
  token; stale teardown cannot detach a replacement attachment, and a late worker cannot publish
  after controller close. A close timeout is not reported as complete and the host does not start a
  replacement stack across a failed teardown barrier.
- Controller, sink, and deferred Surface/SurfaceTexture retirement are held by one process-level
  FIFO teardown barrier across Activity recreation. Blocking work runs outside its lock; success is
  the only removal path, ordinary failures release the exact claim for retry, and fatal errors keep
  the barrier fail-closed.
- The controller itself uses a success-only teardown journal. Receiver, authentication, transport,
  worker, media runtime, location and vehicle-provider stages retain their exact object on an
  operational close failure. `awaitClosed` cannot become true—and the Host cannot close the sink—
  until every stage succeeds; another `close` retries the failed head instead of discarding it.
- Wired restart also journals pre-mux USB sessions, unpublished mux/CSM candidates and late
  publications. A successor generation starts only after published CSM/VPN/mux retirement, an
  executor fence, and a final late-owner sweep; stale callbacks cannot overwrite a live slot.
- Wireless type-130 tunnel admission uses a one-slot exact-identity owner gate under the same
  teardown lock. A concurrent second or stale candidate retires itself without replacing the live
  owner; an old worker's `finally` cannot close its successor, and a failed close retains the exact
  owner until a retry succeeds.
- AirPlay control, event, keepalive, NTP, Screen and Audio workers have distinct STOPPING and
  RETIRED states with bounded join proof. Endpoint/media callback failure retains the exact owner;
  self-thread closure reports deferred retirement and an external coordinator retries after the
  callback stack exits. VPN accept candidates, Bonjour, file transfer and transport-error teardown
  use the same success-only ownership rule.
- RTSP type-110 TEARDOWN is nonterminal for the remaining UI Sync outputs. Its exact MAIN
  selector and video handle remain active while the physical MAIN cover is requested, before
  decoder/renderer disposal. A failed cover returns 503 without removing that selector; a later
  retirement failure also returns 503. Successful selected streams are removed individually;
  failed and not-yet-processed streams remain active for retry.
- `CarPlayMediaEngine` seals all prepare/commit/rollback/TEARDOWN admissions before completing
  close. Screen, renderer binding, audio, microphone and sink notification owners are removed only
  after their callbacks succeed, so a late or reentrant failure remains retryable.
- Activity/controller and Activity/media callbacks use identity-bound leases. Destroying an old
  Activity invalidates its posted generation and cannot detach the callbacks of a newly adopted UI.

## Display scale regression guard

AirPlay `/info` includes both pixel geometry and physical millimetres, so changing the latter can
look exactly like an Android DPI change even when `wm density` is untouched. An experimental
migration changed the physical-size basis from `WIDTH` to `LONGEST_EDGE` without converting the
stored millimetre value. On a 1200x2608 portrait display with a 200 mm setting this reduced the
advertised width to about 92 mm and could make phone-rendered text/icons roughly 2.17 times larger.
Version 3 selected `SHORTEST_EDGE` by default. That preserves rotation density, but a fresh
1280x720 landscape display with a 200 mm setting reports approximately 356x200 mm rather than the
historical `WIDTH`-based 200x113 mm, which can make phone-rendered text/icons much larger. The
connected Android test device has this version-3 shortest-edge setting. Version 4 restores the
ordinary CarPlay `WIDTH` default for new settings and repairs a known version-1 width-to-longest
migration. It deliberately preserves a stored version-3 shortest-edge choice: the data cannot tell
whether that value came from the old default or an explicit user selection. Such a device must
select `WIDTH` in settings to recover the historical landscape scale; this change does not rewrite
its preference. Tests lock the fresh landscape 200x113 mm declaration. Current maximum-window metrics replace persisted historical
Activity maxima. A fixed main/cluster WebDisplay derives millimetres from its own negotiated
geometry rather than the Activity orientation. Explicit height, shortest- and longest-edge modes
remain available.
Theme `density`, Surface geometry and WebDisplay CSS are not connected to this scaling path.

`MediaCodec` Surface replacement now has a generation-bound acknowledgement and a bounded forced
decoder-close fallback. The EGL ingress lease uses that proof before retiring its producer Surface,
and output retirement similarly waits for EGL/GPU proof. These mechanisms have unit/fake-driver
coverage, but the complete path is still disabled pending an authorized complete semantic/theme
factory, independent external-output trust/hardware integration, and real-device validation with simultaneous independent
physical displays.

## Research provenance

The implementation was checked against the project's existing CarPlaySDK reverse-engineering
notes and targeted, read-only inspection of iOS 27.0.1 (24A446) frameworks. That work established
wire names, UUID separation, UI Sync state/sequence behavior, CAF config types, exact detached APAP
framing/crypto/control layouts, corrected MatchPoint response shapes, and bounded UpdateData
framing; LoggingData and real-phone UpdateData exchange remain incomplete. No Apple binary,
extracted source, proprietary asset, credential,
key, or signature is stored in this repository.
