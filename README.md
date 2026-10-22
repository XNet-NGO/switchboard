# Switchboard

Userspace **TCP/UDP port forwarder for Android** — no `VpnService`, no system
TUN device, no root. It wires one local port to another and can pin each
forward's upstream connection to a specific network (Wi‑Fi, cellular,
ethernet), without any elevated privilege.

Switchboard is a thin Kotlin + Jetpack Compose (Material 3) front end over the
[**portmux**](https://github.com/XNet-NGO/portmux) forwarding engine, which it
consumes as **prebuilt static libraries** from portmux's GitHub releases and
links into a single JNI shared library (`libswitchboard.so`).

- **Platform:** Android, `compileSdk`/`targetSdk` 37 (Android 17), `minSdk` 23
- **ABIs:** arm64‑v8a, armeabi‑v7a, x86_64, x86
- **License:** MIT

---

## Table of contents

- [What it's for](#what-its-for)
- [How it works](#how-it-works)
- [Architecture & data flow](#architecture--data-flow)
- [Component reference](#component-reference) — every file, its purpose, and its functions
  - [Native layer](#native-layer)
  - [Kotlin bridge](#kotlin-bridge)
  - [Domain model & persistence](#domain-model--persistence)
  - [Networking](#networking)
  - [Service](#service)
  - [UI](#ui)
- [Egress modes](#egress-modes)
- [Permissions](#permissions)
- [Build](#build)
- [Usage examples](#usage-examples)
- [Verified behavior](#verified-behavior)
- [Limitations](#limitations)

---

## What it's for

A single device often runs several apps that each listen on a fixed local port,
and you want to connect them without root, without a VPN, and without the OS's
one-and-only `VpnService` TUN slot. Concrete cases:

- **MCP server → reverse‑SSH tunnel.** An MCP server listens on a fixed port; a
  reverse‑SSH forward lands on another local port. Switchboard bridges them.
- **DAW → VST host app.** Route a DAW's network port to a VST host app's port.
- **Reach a privileged local service via a high port.** Listen on `:8443`,
  forward to a service on `:443` (connecting *out* to a privileged port needs
  no privilege).
- **Pin a forward to a chosen network.** Force a forward's upstream over
  cellular while Wi‑Fi is the default (or vice‑versa) — e.g. keep one flow on a
  metered/unmetered link deliberately.

---

## How it works

The engine terminates the client's TCP/UDP connection on a local listener, then
opens a *separate* kernel socket to the target and splices bytes between the two
with backpressure. UDP is handled per source‑address flow with idle eviction.
Everything runs in userspace on a single cooperative coroutine scheduler
(`hev-task`). There is no packet capture and no TUN — so no `VpnService`
permission and no interference with a real VPN.

Egress pinning works by calling the NDK's `android_setsocknetwork()` on the
upstream socket *before* it connects, binding that connection to a specific
Android `Network`. For a non‑default network the app first `requestNetwork()`s
the transport so the framework permits the bind.

---

## Architecture & data flow

```
┌───────────────────────────── app process ──────────────────────────────┐
│                                                                         │
│  Compose UI ──> MainViewModel ──> ForwarderService (foreground)         │
│   (routes)        (state)              │                                │
│                                        │ worker thread                  │
│                         ┌──────────────┴───────────────┐                │
│                         │ NetworkRequester.resolveHandle│  (pin routes) │
│                         └──────────────┬───────────────┘                │
│                                        │ JNI                            │
│                            libswitchboard.so                            │
│                              ├─ switchboard-jni.c  (bridge + bind_hook) │
│                              ├─ libportmux.a       (engine)             │
│                              └─ libhev-task-system.a (coroutines)       │
└─────────────────────────────────────────────────────────────────────────┘

Per TCP connection:  client ──> :listenPort (portmux)
                                   accept, then connect(target) with egress pin
                                   splice bytes both ways ──> target
```

---

## Component reference

Every source file, its purpose, and its public functions.

### Native layer

#### `app/src/main/cpp/switchboard-jni.c` — JNI bridge to portmux

The C glue between Kotlin and the portmux engine. Loaded as `libswitchboard.so`.

| Function (JNI) | Kotlin name | Purpose |
|---|---|---|
| `Java_..._nativeNew` | `nativeNew()` | Create a portmux instance; returns an opaque `long` handle. |
| `Java_..._nativeAddRoute` | `nativeAddRoute(...)` | Register one forwarding route (proto, listen, target, egress kind, egress arg, network handle). Builds a `PortmuxEgress` and, for CALLBACK egress, allocates a per‑route context carrying the `net_handle_t`. |
| `Java_..._nativeRun` | `nativeRun(handle)` | **Blocks**: runs the portmux event loop until stopped. Must be on a worker thread. |
| `Java_..._nativeStop` | `nativeStop(handle)` | Signal the loop to shut down (wakes the listener/flow tasks). |
| `Java_..._nativeDestroy` | `nativeDestroy(handle)` | Free the instance. |
| `bind_hook(fd, ctx)` *(static)* | — | portmux's CALLBACK egress hook. Runs on the worker thread before `connect()`. Calls `android_setsocknetwork(handle, fd)` to pin the upstream socket to a `Network`. Pure native — no JNI up‑call (an earlier JNI up‑call crashed with an invalid‑transition‑frame abort). |

#### `app/src/main/cpp/CMakeLists.txt` — native build

Imports the two prebuilt static archives (`libportmux.a`, `libhev-task-system.a`)
for the current ABI from `PORTMUX_LIBS_DIR`, compiles `switchboard-jni.c`, and
links `portmux` + `hevtask` + `android` (for `android_setsocknetwork`) + `log`
into `libswitchboard.so`. Link order matters: portmux depends on hevtask.

### Kotlin bridge

#### `SwitchboardNative.kt` — `object SwitchboardNative`

Thin wrapper over the JNI methods plus the enum constants mirrored from
`portmux.h`.

- `external fun nativeNew(): Long`
- `external fun nativeAddRoute(handle, proto, listenAddr, listenPort, targetAddr, targetPort, egressKind, egressArg, networkHandle): Int`
- `external fun nativeRun(handle: Long)` — blocks; call off the main thread.
- `external fun nativeStop(handle: Long)`
- `external fun nativeDestroy(handle: Long)`
- Constants: `PROTO_TCP=0`, `PROTO_UDP=1`; `EGRESS_NONE=0`, `EGRESS_SOURCE=1`,
  `EGRESS_DEVICE=2`, `EGRESS_CALLBACK=3`.
- `init { System.loadLibrary("switchboard") }`

### Domain model & persistence

#### `Route.kt`

- `enum Proto { TCP, UDP }`
- `enum EgressMode { NONE, SOURCE, DEVICE, NETWORK }` — see [Egress modes](#egress-modes).
- `enum NetTransport { ANY, WIFI, CELLULAR, ETHERNET }` — which transport a
  NETWORK route should `requestNetwork()`.
- `data class Route(id, name, proto, listenAddr, listenPort, targetAddr,
  targetPort, egress, egressArg, networkHandle, transport)`
  - `fun summary(): String` — one‑line human label, e.g.
    `tcp  127.0.0.1:6001 → 1.1.1.1:80  via cellular`.

#### `RouteStore.kt` — `object RouteStore`

Persists routes as a JSON array in `SharedPreferences` (`switchboard.routes` /
key `routes`). No external JSON dependency.

- `fun load(ctx): List<Route>` — parse persisted routes (tolerant of missing
  fields).
- `fun save(ctx, routes: List<Route>)` — serialize and store.

### Networking

#### `NetworkRepository.kt` — `object NetworkRepository`

Enumerates currently‑available networks for the egress picker.

- `data class EgressNetwork(handle: Long, label: String, transport: NetTransport)`
- `fun available(ctx): List<EgressNetwork>` — walks `ConnectivityManager`'s
  networks and returns a label (`Wi‑Fi · internet · active`, …), the opaque
  `networkHandle`, and the transport. Needs no location permission.

#### `NetworkRequester.kt` — `class NetworkRequester(context)`

Requests specific transports via `ConnectivityManager.requestNetwork()` and
holds the requests for the forwarder's lifetime, which is what lets the app pin
a socket to a network that is **not** the active default.

- `fun resolveHandle(transport: NetTransport, fallbackHandle: Long, timeoutMs=8000): Long`
  — ensure a network of `transport` is requested, block until it resolves (or
  timeout), return its live handle (falling back to the stored handle). For
  `ANY`, returns `fallbackHandle` unchanged.
- `fun releaseAll()` — unregister all held requests; call on stop.

### Service

#### `ForwarderService.kt` — `class ForwarderService : Service`

Foreground service that owns the portmux instance and runs it on a dedicated
worker thread. No `VpnService`.

- `onStartCommand(...)` — loads routes; enters the foreground; on a worker
  thread creates the portmux instance, resolves each NETWORK route's handle via
  `NetworkRequester`, adds every route, and calls `nativeRun` (blocks).
- `onDestroy()` — `nativeStop`, joins the worker, `nativeDestroy`, and
  `NetworkRequester.releaseAll()`.
- `startForegroundSafely(routeCount): Boolean` — enters foreground passing
  `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on API 34+ (required at runtime there),
  wrapped so a refusal stops the service instead of crashing.
- `buildNotification(routeCount): Notification` — ongoing, low‑importance,
  `category=service`, with a tap `PendingIntent` to open the app.
- companion `start(ctx)` / `stop(ctx)` — start as a foreground service / stop.

### UI

#### `MainActivity.kt` — `class MainActivity : ComponentActivity`

- `onCreate(...)` — requests `POST_NOTIFICATIONS` at runtime on API 33+ (without
  it the FGS notification is silently suppressed), then sets the Compose
  content.

#### `MainViewModel.kt` — `class MainViewModel : AndroidViewModel`

- `routes: StateFlow<List<Route>>`, `running: StateFlow<Boolean>`,
  `networks: StateFlow<List<EgressNetwork>>` — observable UI state.
- `fun addRoute(r)`, `fun removeRoute(id)` — mutate + persist routes.
- `fun refreshNetworks()` — re‑enumerate available networks.
- `fun start()` / `fun stop()` — start/stop `ForwarderService`.

#### `ui/SwitchboardApp.kt` — `@Composable SwitchboardApp(vm)`

Top‑level screen: Start/Stop button + status, the route list (each card shows
`Route.summary()` and a Delete action), and a FAB to add a route.

#### `ui/AddRouteDialog.kt` — `@Composable AddRouteDialog(networks, onRefreshNetworks, onDismiss, onAdd)`

Form to create a route: name, TCP/UDP, listen address/port, target
address/port, and an egress selector (Default / Network / Source IP). For
Network it lists available networks to pick (recording both handle and
transport). Height‑capped and scrollable.

---

## Egress modes

Each route chooses how its **upstream** (target) socket leaves the device.
Mirrors `PortmuxEgress` in the engine.

| Mode (`EgressMode`) | JNI kind | Needs | Behavior |
|---|---|---|---|
| `NONE` | 0 | — | Default route / loopback. |
| `SOURCE` | 1 | — | `bind()` the upstream socket to a local source IP (`egressArg`). |
| `DEVICE` | 2 | root / `CAP_NET_RAW` | `SO_BINDTODEVICE` to an interface name. Not exposed in the UI (needs privilege). |
| `NETWORK` | 3 (CALLBACK) | `CHANGE_NETWORK_STATE` | `requestNetwork()` the chosen transport, then `android_setsocknetwork()` the upstream socket to it. The no‑root way to pin egress. |

---

## Permissions

Declared in `AndroidManifest.xml`:

| Permission | Why |
|---|---|
| `INTERNET` | Open sockets. |
| `ACCESS_NETWORK_STATE` | Enumerate networks for the egress picker. |
| `CHANGE_NETWORK_STATE` | `requestNetwork()` + bind to a non‑default network. |
| `POST_NOTIFICATIONS` | Show the foreground‑service notification (API 33+, requested at runtime). |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | Run the forwarder as a foreground service. |

Notably **not** required: location, `NEARBY_WIFI_DEVICES`, or `VpnService` /
`BIND_VPN_SERVICE` — Switchboard neither scans Wi‑Fi nor uses a TUN.

---

## Build

Requires the Android SDK (platform 37) and NDK r28. The portmux prebuilt
artifacts are fetched automatically from the pinned release
(`portmuxVersion` in `gradle.properties`).

```sh
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

The Gradle `fetchPortmux` task downloads the per‑ABI release tarballs into
`app/.portmux/` and CMake links them into `libswitchboard.so`. A plain clone
builds without any submodule init.

Toolchain: AGP 9.3.2, Gradle 9.7.1, Kotlin 2.4.10, JVM target 21.

---

## Usage examples

In the app: tap **+**, fill the form, **Add**, then **Start**.

**1 — MCP server to a reverse‑SSH forward (loopback):**

```
Proto:   TCP
Listen:  127.0.0.1 : 39811     (where the MCP client connects)
Target:  127.0.0.1 : 2222      (where ssh -R landed the tunnel)
Egress:  Default
```

**2 — DAW to a VST host app (loopback):**

```
Proto:   TCP
Listen:  127.0.0.1 : 9000
Target:  127.0.0.1 : 9001
Egress:  Default
```

**3 — Forward to a privileged target port:**

```
Proto:   TCP
Listen:  127.0.0.1 : 8443
Target:  127.0.0.1 : 443
Egress:  Default
```

**4 — Pin a forward to cellular while Wi‑Fi is the default:**

```
Proto:   TCP
Listen:  127.0.0.1 : 6001
Target:  1.1.1.1 : 80
Egress:  Network → (pick the Cellular entry)
```

The upstream connection then egresses over cellular even though Wi‑Fi is the
system default.

**5 — UDP relay with idle eviction:**

```
Proto:   UDP
Listen:  127.0.0.1 : 9000
Target:  127.0.0.1 : 9001
Egress:  Default
```

### Scripted testing (adb)

Routes persist as JSON in the app's prefs, so a route can be seeded without the
UI (debug builds):

```sh
# one TCP loopback route 6001 -> 6002
JSON='[{"id":"t","name":"demo","proto":0,"listenAddr":"127.0.0.1","listenPort":6001,"targetAddr":"127.0.0.1","targetPort":6002,"egress":0,"egressArg":"","networkHandle":0,"transport":0}]'
# write it into shared_prefs/switchboard.routes.xml via run-as, then launch + Start.
```

Verify the forwarder is live and its egress:

```sh
adb shell 'toybox netstat -ltn | grep :6001'          # listener up
adb shell 'toybox netstat -tn  | grep <target>'       # upstream src IP = chosen egress
adb logcat -d | grep -E 'bind_hook|NetworkRequester'  # pin mechanism
```

---

## Verified behavior

Confirmed on a Pixel 10 Pro XL (Android 17 / API 37, arm64‑v8a):

- **TCP forwarding** — loopback relay, bidirectional, multiple connections.
- **UDP forwarding** — per‑flow relay, round‑trips, idle eviction.
- **Forward to a privileged target port** (e.g. `:443`) from the unprivileged app.
- **Foreground service + notification** — `isForeground=true`,
  `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`, ongoing `NO_CLEAR` notification on the
  `switchboard.forwarding` channel.
- **Non‑default network pinning** — with Wi‑Fi as the default, a route pinned to
  cellular egressed from the cellular source IP (`10.160.120.239`) instead of
  the Wi‑Fi default (`192.168.200.24`); the identical route under Default egress
  used Wi‑Fi. Logs showed `requestNetwork()` resolving the cellular network and
  `android_setsocknetwork()` succeeding.
- **Clean start/stop** — the listener opens on Start and closes on Stop; network
  requests are released.

---

## Limitations

- **Listening on a privileged port** (binding `< 1024`) is **not** possible from
  the sandboxed app — the OS gates it. Forwarding *to* a privileged port is
  fine. Binding a privileged listener would require running the portmux CLI with
  elevation (root / Shizuku), outside this app.
- Pinning to a non‑default network can still be denied by a **non‑bypassable
  (lockdown) VPN** holding the default route — that's an OS policy, not an app
  bug.
- Changing a route requires Stop/Start (portmux routes are fixed at `run()`).
- `DEVICE` egress (`SO_BINDTODEVICE`) needs root and is not exposed in the UI.
