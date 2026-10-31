# DropLink 2.0

Offline, peer-to-peer file sharing for Android phones (Android 8 → 17), built on two standard,
widely used protocols instead of a home-made one:

| Method | Used when | How it works |
|---|---|---|
| **Google Nearby Connections** | both phones have Google Play services (Samsung, Pixel, Nothing, Motorola, global Xiaomi/OPPO/vivo ROMs…) | the same engine as Quick Share: Bluetooth finds the phone, then Nearby switches to a direct Wi-Fi link (Wi-Fi Direct / hotspot / LAN). **No shared Wi-Fi network needed.** |
| **LocalSend protocol v2** | every phone, including ones without Google (Huawei/HMS, China ROMs), and PCs running the [LocalSend](https://localsend.org) app | HTTPS on the local network: multicast discovery on `224.0.0.167:53317`, then `prepare-upload` / `upload`. Both devices must be on the **same Wi-Fi or one device's hotspot**. |

**Automatic** mode (default) tries Nearby first when the receiver was found over Nearby, and
falls back to LocalSend when Nearby can't connect. Either method can be forced from the Send
screen's chip or in Settings.

- No internet, no account, no ads, no analytics. Nothing leaves the local link.
- TLS for LocalSend (self-signed per-install certificate, as the LocalSend spec requires);
  Nearby Connections encrypts its links itself and shows a 4-character code on both phones.
- One universal APK (pure Kotlin/Java, no native code).

> Version 1.x used a custom protocol plus Wi-Fi Direct/QR/USB modes. 2.0 removes all of that.
> The LocalSend implementation is covered by JVM tests and was checked against an independent
> LocalSend implementation (both directions, HTTP and HTTPS, identical SHA-256).

## Speed: what to expect

Speed is limited by the slower phone's Wi-Fi radio, whatever the protocol:

| Phones | Typical |
|---|---|
| Two 5 GHz Wi-Fi phones (Wi-Fi 5/6) | 15–40 MB/s |
| One phone 2.4 GHz-only (e.g. Galaxy A12) | 3–6 MB/s |
| Nearby stuck on Bluetooth (Wi-Fi off / upgrade failed) | < 0.2 MB/s |

The Settings → *Copy diagnostic log* output says which link Nearby used ("link quality 3" = Wi-Fi).

## Build

Requirements: **JDK 17+**, Android SDK platform 37. Gradle 9.6.0 (wrapper), AGP 9.5.0,
Kotlin 2.4.21, KSP 2.3.11. `compileSdk 37`, `targetSdk 36`, `minSdk 26`.

```bash
./gradlew testDebugUnitTest   # JVM tests (LocalSend server/client/discovery over real sockets)
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # signed + R8-minified (needs a release keystore)
```

`targetSdk` is 36 on purpose: an app targeting 37 must request Android 17's runtime "local
network" permission, while at 36 it is granted together with `INTERNET`.

Debug builds are signed with `app/droplink-debug.keystore` (a public, throw-away key kept in the
repo), so each new CI debug APK installs over the previous one without uninstalling.

### Release keystore (optional)

```bash
keytool -genkeypair -v -keystore droplink-release.jks -alias droplink \
  -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12
```

Add the repository secrets `KEYSTORE_BASE64` (`base64 -w0 droplink-release.jks`),
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Without them CI builds the debug APK.

## CI (GitHub Actions)

`.github/workflows/build.yml`: JDK 17 → Android SDK → unit tests → `assembleRelease` (or
`assembleDebug` without secrets) → uploads the `DropLink-apk` artifact; on a `v*` tag it also
attaches the APK to a GitHub Release.

## Using it

1. **Receiver:** tap *Receive*. Allow *Nearby devices* (Bluetooth) when asked. The screen shows
   whether Nearby and LocalSend are ready, and this phone's Wi-Fi address.
2. **Sender:** *Send* → pick photos/files/folder/apps → tap the receiver's name. The chips show
   how it was found (Nearby, LocalSend or both).
3. Over Nearby both phones show the same 4-character code: accept on the receiver.
4. Files land in **Download/DropLink** (subfolders preserved) or the folder chosen in Settings.

No Google services on one phone? Put both phones on the same Wi-Fi, or turn on one phone's
hotspot and connect the other phone to it, then use LocalSend.

## Permissions and why

| Permission | Why |
|---|---|
| `BLUETOOTH_SCAN/ADVERTISE/CONNECT` (12+), `NEARBY_WIFI_DEVICES` (13+), location (8–12 only) | Nearby Connections discovery and its direct Wi-Fi link. `neverForLocation`; location is never read. |
| `INTERNET`, Wi-Fi state, multicast | LocalSend server/client and multicast discovery on the local network only |
| `ACCESS_LOCAL_NETWORK` | declared for Android 17 |
| Foreground service (dataSync), wake lock, notifications | keep transfers running with the screen off; accept from the notification |
| `WRITE_EXTERNAL_STORAGE` (≤ 28) | Android 8–9 save to Download/DropLink. 10+ uses MediaStore/SAF |

## Architecture

```
ui/ (Compose)  →  transfer/SendManager, ReceiveManager, TransferTracker
                    ├─ engine/NearbyEngine        Google Nearby Connections (P2P_POINT_TO_POINT)
                    └─ engine/LocalSendEngine     Android wrapper around core/localsend
core/localsend/  LsServer (HTTPS/HTTP), LsClient, LsDiscovery (multicast + subnet scan), models
core/security/   per-install EC P-256 identity + self-signed cert (BouncyCastle), TLS helpers
storage/         pickers → OutgoingItem; MediaStore / SAF / legacy sinks for received files
```

## OEM setup tips

The in-app *Phone setup* screen detects the brand and opens the right battery/autostart screens
(Xiaomi, Huawei/Honor, OPPO/realme/OnePlus, vivo, Samsung, Tecno/Infinix/itel).

## Known limitations

- Nearby needs Google Play services on **both** phones; otherwise LocalSend (same Wi-Fi/hotspot) is used.
- LocalSend's optional PIN is not supported; turn "Require PIN" off in the LocalSend app.
- Over Nearby the receiver stores the file in Nearby's temporary folder first, then copies it to
  Download/DropLink and deletes the temporary copy (Nearby doesn't let apps choose the folder).
- Transfers don't resume after the app is killed; send again.
- "Share installed app" exports the base APK; split-APK apps may not install from it alone.
