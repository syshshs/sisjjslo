# Real Xray-core integration

This project now uses the Android `VpnService` TUN descriptor as the data-plane input and an official XTLS/libXray AAR as the Xray-core runtime.

## What changed

- The connect action passes the selected profile's raw configuration to the VPN service.
- A real Android TUN interface is created first.
- The TUN file descriptor is passed to Xray as `xray.tun.fd`.
- Share links are converted to Xray JSON through libXray when the input is not already JSON.
- A `tun` inbound is injected into the runtime config.
- Xray socket protection is wired to `VpnService.protect()`.
- Xray DNS resolver integration is wired to the selected DNS server.
- The app only reports CONNECTED after libXray reports a successful Xray start.
- Random/fake speed generation was removed; the UI receives best-effort device network byte deltas instead.
- Stop/revoke/destroy paths stop Xray before closing the TUN.

## Build the native core

Requirements: Git, Go, Python 3, Android SDK/NDK and gomobile-compatible tooling.

Run:

    ./scripts/build-libxray-aar.sh

Then:

    ./gradlew :app:assembleDebug

The generated file is `app/libs/libXray.aar` and is intentionally not committed to the source archive.

## Important

The native AAR must be generated from the same/current libXray API family used by the adapter. libXray's API is not promised stable, so keep the generated AAR and this adapter in sync.
