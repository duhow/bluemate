# Bluemate

<p align="center">
  <strong>Find nearby Bluemate users via BLE iBeacon with distance and compass.</strong>
</p>

<p align="center">
  <a href="https://github.com/duhow/bluemate/actions/workflows/build.yml">
    <img src="https://github.com/duhow/bluemate/actions/workflows/build.yml/badge.svg" alt="Build">
  </a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-brightgreen?logo=android" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin" alt="Kotlin">
</p>

<p align="center">
<a href="https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/duhow/bluemate">
  <img src="https://github.com/ImranR98/Obtainium/blob/main/assets/graphics/badge_obtainium.png"
       alt="Get it on Obtainium" align="center" height="54" />
</a>

<a href="https://github.com/duhow/bluemate/releases/latest">
  <img src="https://raw.githubusercontent.com/Kunzisoft/Github-badge/4711835e032fe2735dc80c1329beb4685899aa91/get-it-on-github.png"
       alt="Download APK" align="center" height="81" />
</a>
</p>

---

Bluemate uses Bluetooth Low Energy (BLE) to discover other nearby users running the app. Each device advertises as an iBeacon and scans for others, reporting their estimated distance in meters. A built-in compass shows your current heading to help locate nearby users.

### Features

- **iBeacon advertising & scanning** – automatically broadcasts and discovers nearby Bluemate users.
- **Distance estimation** – calculates approximate distance in meters from BLE signal strength (RSSI).
- **Compass** – displays your device orientation using accelerometer and magnetometer sensors.
- **Background service** – runs as an Android foreground service so scanning continues when the app is in the background.
- **Battery optimization** – requests exemption from battery restrictions to ensure continuous operation.

### Keystore

To sign your APK, create a keystore. **Keep it safe**.

```sh
keytool -genkeypair -v -keystore release.jks -alias bluemate -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA -validity 10000
```

You can upload it to GitHub Actions as Secret `ANDROID_KEYSTORE_BASE64` to generate Release APKs.

```sh
base64 -w 0 release.jks ; echo
```

Then define `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_PASSWORD` (default is the same), and `ANDROID_KEY_ALIAS` as configured.
