# Switchboard

Android app for **userspace TCP/UDP port forwarding** between apps and
interfaces on a device — no `VpnService`, no system TUN, no root. It wires one
local port to another (e.g. an MCP server's fixed port to a reverse-SSH
forward, or a DAW to a VST host app) and can pin the upstream to a specific
network.

Switchboard is a thin Kotlin + Jetpack Compose (Material 3) front end over the
[**portmux**](https://github.com/XNet-NGO/portmux) forwarding engine, which it
consumes as **prebuilt static libraries** from portmux's GitHub releases.

## How it fits together

```
Compose UI ──> ForwarderService ──JNI──> libswitchboard.so
                 (foreground)              ├─ switchboard-jni.c  (bridge)
                                           ├─ libportmux.a       (engine)
                                           └─ libhev-task-system.a (runtime)
```

- `SwitchboardNative` (Kotlin) ↔ `switchboard-jni.c` (JNI) ↔ `portmux` (C).
- `ForwarderService` owns the portmux instance and runs its blocking event
  loop on a worker thread; survives UI as a foreground service.
- Egress pinning uses portmux's `CALLBACK` hook: the JNI bridge up-calls
  `SwitchboardNative.bindSocketToNetwork(fd, handle)`, which calls
  `Network.bindSocket(fd)` — the no-root way to route a connection over a
  chosen network.

## Build

Requires Android SDK with platform 37 + NDK r28 and the `ANDROID_HOME` /
`local.properties` set. The portmux artifacts are fetched automatically from
the pinned release (`portmuxVersion` in `gradle.properties`).

```sh
./gradlew :app:assembleDebug
```

The Gradle `fetchPortmux` task downloads the per-ABI release tarballs into
`app/.portmux/` and CMake links `libportmux.a` + `libhev-task-system.a` into
`libswitchboard.so`.

- `applicationId` / namespace: `org.xnet.switchboard`
- `compileSdk` / `targetSdk`: 37 · `minSdk`: 23
- ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`

## Status

v0.1 scaffold: add TCP/UDP routes in the UI, start/stop a foreground
forwarding service. Egress-network picker and per-route editing are the next
steps.

## License

MIT.
