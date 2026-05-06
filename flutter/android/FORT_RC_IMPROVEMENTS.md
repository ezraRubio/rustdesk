# Fort RC Sample → RustDesk Android: Improvement Points

Reference document for tracked improvement work. Each point is self-contained and intended to be addressed in a separate context window.

---

## Point 1 — Attended Sessions (Consent Flow) — Completely Unimplemented

**Priority:** High

**What the sample does:**
`RemoteCaptureService` fully handles `isUserConsentRequired=true` by launching `ConsentActivity`,
collecting user approval/rejection, and only proceeding to capture after consent is granted.
The flow respects the 30-second pre-running timeout window.

**What RustDesk does:**
`DispatcherActivity.handleGetSessionInfoReply()` logs a warning and calls `finish()` immediately
for attended sessions:
```kotlin
// Attended flow: not yet implemented.
Log.w(LOG_TAG, "Attended session (isUserConsentRequired=true) — not yet implemented, finishing")
finish()
```

**Files involved:**
- `android/app/src/main/kotlin/il/co/tmg/fort_rc/activities/DispatcherActivity.kt`
  — `handleGetSessionInfoReply()` — the unimplemented branch

**What needs to be done:**
Implement the attended consent flow. When `isUserConsentRequired == true`:
1. Launch a consent UI (either a native Android `ConsentActivity` mirroring the sample, or a Flutter
   route triggered via `MethodChannel`).
2. On user approval, continue the session flow (bind `CaptureService`, `initCapture`,
   `MSG_START_SESSION`).
3. On user rejection, call `teardownCurrentSession()` and `finish()`.
4. The entire consent + capture-init sequence must complete within Fort CT's 30-second
   pre-running timeout.

**Reference files:**
- `Fort-ControlTower/fort_rc_sample/app/src/main/java/il/co/tmg/fort_rc/activities/ConsentActivity.kt`
- `Fort-ControlTower/fort_rc_sample/app/src/main/java/il/co/tmg/fort_rc/services/RemoteCaptureService.kt`
  — `handleSessionInfo()`, `ACTION_CONSENT_RESULT` handling, `bindCaptureServiceIfNeeded()`
- `Fort-ControlTower/docs/REMOTE_CONTROL_INTEGRATION.md` — Session flow steps 9–12

---

## Point 2 — Architecture: Activity Stays Alive vs. Foreground Service

**Priority:** High

**What the sample does:**
Uses a dedicated `RemoteCaptureService` (foreground service) as the long-lived session owner.
`DispatcherActivity` is a pure trampoline: validates the intent, fires
`startForegroundService()`, and immediately calls `finish()`. All state, bindings, and IPC live
in the service for the full session lifetime.

**What RustDesk does:**
`DispatcherActivity` **stays alive for the entire session** (never calls `finish()`) in order to
keep `CaptureControlService` bound. The activity holds `KnoxCapturer`, `MainService` binding,
`controlMessenger`, and all session state. It also uses a static
`MainService.activeDispatcher: DispatcherActivity?` back-reference.

**Problems:**
- Activities can be killed by the OS under memory pressure without a guaranteed `onDestroy()`,
  silently dropping the `CaptureControlService` binding and ending the Fort CT session.
- `onNewIntent` → `recreate()` briefly unbinds `CaptureControlService` — may trigger Fort CT's
  30-second timeout.
- A static Activity reference in a Service is an anti-pattern prone to leaks and incorrect
  lifecycle assumptions.
- The session lives and dies with the Activity stack — wrong for background enterprise RC.

**Files involved:**
- `android/app/src/main/kotlin/il/co/tmg/fort_rc/activities/DispatcherActivity.kt`
  — entire file; activity should become a trampoline
- `android/app/src/main/kotlin/com/carriez/flutter_hbb/MainService.kt`
  — `activeDispatcher` static field, `onKnoxSessionReady()`, `onKnoxSessionEnded()`
- `android/app/src/main/AndroidManifest.xml`
  — `MainService` `foregroundServiceType` (also see Point 9)

**What needs to be done:**
Move all session state and both service bindings (`CaptureControlService` + `CaptureService` via
`KnoxCapturer`) into `KnoxCapturer` .
`DispatcherActivity` validates the intent extras, calls `startForegroundService(MainService)` with
the session ID, and finishes immediately. `MainService` owns the `Messenger` IPC loop,
session state machine, and keeps `CaptureControlService` bound for the session lifetime.
Remove `MainService.activeDispatcher`.

**Reference files:**
- `Fort-ControlTower/fort_rc_sample/app/src/main/java/il/co/tmg/fort_rc/activities/DispatcherActivity.kt`
  — trampoline pattern (88 lines, finishes in `onCreate`)
- `Fort-ControlTower/fort_rc_sample/app/src/main/java/il/co/tmg/fort_rc/services/RemoteCaptureService.kt`
  — service as session owner
- `Fort-ControlTower/docs/REMOTE_CONTROL_INTEGRATION.md` — "keep control service bound for the
  entire session" requirement

---

## Point 3 — Session State Machine: Missing Branches and Error Code Handling

**Priority:** Medium

**What the sample does:**
`RemoteSessionFlow.afterSessionInfo()` is a pure function returning a sealed `AfterSessionInfo`
type with four distinct cases:
- `Abort` — negative `arg1`, null/blank JSON, parse error, or session ID mismatch
- `ShowConsentUi` — `isUserConsentRequired == true`
- `ContinueToCapturePipeline` — normal unattended start
- `AlreadyRunningUseCaptureOnly` — `status == "running"` (duplicate intent for existing session)

Error codes are also mapped separately:
- `-1` → another client already active (could surface a specific message)
- `-2` → invalid state (possibly transient)
- `-3` → session mismatch (definitive abort)

**What RustDesk does:**
`handleGetSessionInfoReply()` only checks `msg.arg1 < 0` for a generic finish.
Missing:
1. No `status == "running"` → `AlreadyRunningUseCaptureOnly` branch — a duplicate Fort CT intent
   would trigger a new handshake that collides with the existing running session.
2. No per-error-code differentiation — `-1`, `-2`, `-3` all produce the same silent `finish()`.

**Files involved:**
- `android/app/src/main/kotlin/il/co/tmg/fort_rc/activities/DispatcherActivity.kt`
  — `handleGetSessionInfoReply()` and `parseSessionPayload()`

**What needs to be done:**
1. Add `AlreadyRunningUseCaptureOnly` handling: if `status == "running"` skip `MSG_GET_SESSION_INFO`
   re-validation, go straight to `bindCaptureServiceAndPrepare()` without re-sending
   `MSG_START_SESSION`.
2. Map each negative `arg1` to a distinct log message / user-visible state.
3. Optionally extract a `FortSessionFlow` object mirroring `RemoteSessionFlow` for testability.

**Reference files:**
- `Fort-ControlTower/fort_rc_sample/app/src/main/java/il/co/tmg/fort_rc/activities/DispatcherActivity.kt`
  — `RemoteSessionFlow.afterSessionInfo()` sealed return type
- `Fort-ControlTower/fort_rc_sample/app/src/main/java/il/co/tmg/fort_rc/ipc/CaptureControlContract.kt`
  — error code constants (`ERROR_ANOTHER_CLIENT_ACTIVE`, `ERROR_INVALID_STATE`,
    `ERROR_SESSION_MISMATCH`)

---

## Point 4 — Bind Race Conditions: Missing `unbindPending` Guards

**Priority:** Medium

**What the sample does:**
`RemoteCaptureService` uses explicit `fortControlUnbindPending` and `captureUnbindPending` boolean
guards. Every `bindService()` call sets the guard; every `unbindService()` call clears it.
`bindFortControlIfNeeded()` and `bindCaptureServiceIfNeeded()` check these guards as the first
thing to prevent double-bind races.

**What RustDesk does:**
`DispatcherActivity` uses `isControlBound` and `isMainServiceBound` booleans but has no
equivalent "bind in progress" guard. The binding of `CaptureService` is posted to a background
`Handler` thread — between the `bindService()` call and `onServiceConnected()`, additional
triggers could attempt a second bind.

**Files involved:**
- `android/app/src/main/kotlin/il/co/tmg/fort_rc/activities/DispatcherActivity.kt`
  — `bindMainService()`, `bindCaptureServiceAndPrepare()`

**What needs to be done:**
Add `isMainServiceBindPending` and `isCaptureBindPending` boolean guards.
Check them at the top of `bindMainService()` and `bindCaptureServiceAndPrepare()`.
Clear them in the respective `onServiceDisconnected` callbacks.
Also add a `stopped` flag checked at entry to all async continuations.

**Reference files:**
- `Fort-ControlTower/fort_rc_sample/app/src/main/java/il/co/tmg/fort_rc/services/RemoteCaptureService.kt`
  — `bindFortControlIfNeeded()`, `bindCaptureServiceIfNeeded()`, guard fields
    `fortControlUnbindPending`, `captureUnbindPending`

---

## Point 5 — Sensitive Credential Logging

**Priority:** Medium

**What the sample does:**
`RemoteCaptureService.sendStartSession()` generates the token and remote ID without logging their
values. The docs explicitly state: "Treat `token` as sensitive. Avoid placing the real token in
URL query parameters if possible."

**What RustDesk does:**
`DispatcherActivity.startRunningSession()` logs `FFI.getTemporaryPassword()` at DEBUG level:
```kotlin
Log.d(LOG_TAG, "temp password retrieved through ffi: $tempPass")
```
This exposes a live session credential in logcat, readable by any app with `READ_LOGS`
permission (or ADB) on a non-production device.

**Files involved:**
- `android/app/src/main/kotlin/il/co/tmg/fort_rc/activities/DispatcherActivity.kt`
  — `startRunningSession()`, line with `Log.d(LOG_TAG, "temp password retrieved through ffi: $tempPass")`

**What needs to be done:**
Remove (or replace with a redacted placeholder) the log line that prints `tempPass`.
Optionally also mask `remoteId` in production builds using `BuildConfig.DEBUG` guards.

**Reference files:**
- `Fort-ControlTower/docs/REMOTE_CONTROL_INTEGRATION.md` — Security Guidance section
- `Fort-ControlTower/docs/FORT_SSO_CLIENT_INTEGRATION.md` — "Passwords should be stored as
  CharArray and wiped after use"

---

## Point 6 — `SharedMemory` Buffer Lifecycle: Memory Leak in `KnoxCapturer`

**Priority:** Medium

**What the sample does:**
`RemoteCaptureService.frameCallback.onFrameAvailable(sharedMemory)` increments the frame counter,
then immediately calls `sharedMemory?.close()`. Each `SharedMemory` object is treated as a
one-shot delivery and is closed after use.

**What RustDesk does:**
`KnoxCapturer.knoxFrameCallback.onFrameAvailable(memory)` maps the first received `SharedMemory`
into `knoxMappedBuffer` and reuses it for all subsequent frames via `mappingLock`. Problems:
1. If Fort CT delivers subsequent frames via a **different** `SharedMemory` instance (e.g., after
   a screen rotation changes dimensions), the old buffer is never unmapped and the new `memory`
   object is never mapped or closed — frames stop flowing silently.
2. The `memory` argument (the `SharedMemory` object itself) is **never closed** (`memory.close()`
   is not called), leaking the underlying file descriptor on every frame after the first.
3. `releaseCapture()` only unmaps `knoxMappedBuffer` — if it was mapped from a `SharedMemory`
   that has since been replaced, the unmap target is stale.

**Files involved:**
- `android/app/src/main/kotlin/com/carriez/flutter_hbb/KnoxCapturer.kt`
  — `knoxFrameCallback`, `releaseCapture()`

**What needs to be done:**
Option A (simpler, matches sample intent): Map, read into a `ByteArray`/`ByteBuffer` copy,
unmap, and close `memory` within each `onFrameAvailable` callback. Pass the copy to
`FFI.onVideoFrameUpdate()`.

Option B (performance, if large frame copies are a concern): Maintain a persistent mapping but
detect size changes (compare `memory.size` to the current mapped buffer size), remap on change,
and always call `memory.close()` after mapping (the mapping keeps the data alive; the
`SharedMemory` object itself can be closed once mapped).

In both cases: always call `memory.close()` before returning from `onFrameAvailable`.

**Reference files:**
- `Fort-ControlTower/fort_rc_sample/app/src/main/aidl/il/co/tmg/fort_ct/IFrameCallback.aidl`
- `Fort-ControlTower/docs/REMOTE_CONTROL_INTEGRATION.md` — `IFrameCallback` section

---

## Point 7 — Capture Service Loss: No Callback to Session Owner

**Priority:** Medium

**What the sample does:**
`RemoteCaptureService.captureConnection.onServiceDisconnected()` nulls `captureService`,
sets `isCaptureBound = false` and `isCapturePrepared = false`, then calls
`stopAll("Fort Control Tower capture service disconnected")` — cleanly ending the entire session.

**What RustDesk does:**
`KnoxCapturer.serviceConnection.onServiceDisconnected()` only logs and nulls internal fields.
It does **not** notify `MainService` or `DispatcherActivity` that the capture service has died.
`onBindingDied()` attempts a rebind via `bind()` (which itself has the synchronous wait
`bindLock.wait(600)` called on the binder thread — a potential ANR source).
The session appears to continue from the RustDesk/Flutter side while frames have silently stopped.

**Files involved:**
- `android/app/src/main/kotlin/com/carriez/flutter_hbb/KnoxCapturer.kt`
  — `serviceConnection.onServiceDisconnected()`, `onBindingDied()`

**What needs to be done:**
1. Add an `onCaptureLost: () -> Unit` callback parameter to `KnoxCapturer` (or use an interface).
2. Call it from `onServiceDisconnected()` after clearing internal state.
3. In `MainService.onKnoxSessionReady()`, pass a lambda that calls `onKnoxSessionEnded()`.
4. Remove the synchronous `bind()` call from `onBindingDied()` on the binder thread — rebind
   should be posted to the service's `Handler` thread, or handled by the session owner.

**Reference files:**
- `Fort-ControlTower/fort_rc_sample/app/src/main/java/il/co/tmg/fort_rc/services/RemoteCaptureService.kt`
  — `captureConnection.onServiceDisconnected()`

---

## Point 8 — Ordered Teardown Broadcast Pattern Not Implemented

**Priority:** Low

**What the sample does:**
`RemoteCaptureService.stopAll()` uses `sendOrderedBroadcast(ACTION_PREPARE_STOP)` with priority
ordering:
- `RemoteSessionActivity` registers its receiver at priority `10_000` — it unbinds from the
  service first.
- `finishStopOrderedReceiver` (priority `0`) then calls `stopSelf()`.
This ensures the UI releases its binding before the service stops, avoiding the OS keeping a
dead service alive because a client still holds a binding.

**What RustDesk does:**
Session teardown calls `mainService.onKnoxSessionEnded()` directly, which calls `stopCapture()`.
The Flutter UI layer is not coordinated — it may attempt to render frames or query service state
after the underlying capturer has been torn down. No ordered cleanup sequence exists.

**Files involved:**
- `android/app/src/main/kotlin/com/carriez/flutter_hbb/MainService.kt`
  — `onKnoxSessionEnded()`, `stopCapture()`
- `android/app/src/main/kotlin/il/co/tmg/fort_rc/activities/DispatcherActivity.kt`
  — `teardownCurrentSession()`, `onSessionEndedExternally()`

**What needs to be done:**
When a Knox session ends (either from `DispatcherActivity.onDestroy` or from
`onSessionEndedExternally`):
1. Notify the Flutter layer via `MainActivity.flutterMethodChannel?.invokeMethod("on_state_changed", ...)`
   so the UI can clean up before capture stops.
2. Only after the Flutter ACK (or a short timeout), proceed with `releaseCapture()` /
   `unbindService()`.
Alternatively, implement an ordered broadcast equivalent using Android `LocalBroadcastManager`
with priority receivers, matching the sample's `ACTION_PREPARE_STOP` pattern.

**Reference files:**
- `Fort-ControlTower/fort_rc_sample/app/src/main/java/il/co/tmg/fort_rc/services/RemoteCaptureService.kt`
  — `stopAll()`, `finishStopOrderedReceiver`, `ACTION_PREPARE_STOP`
- `Fort-ControlTower/fort_rc_sample/app/src/main/java/il/co/tmg/fort_rc/activities/RemoteSessionActivity.kt`
  — ordered broadcast receiver with priority `10_000`

---

## Point 9 — `foregroundServiceType` Incorrect for Knox Path

**Priority:** Low

**What the sample does:**
`RemoteCaptureService` declares `android:foregroundServiceType="dataSync"` in the manifest and
calls `startForeground(..., ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)`. This correctly
describes a data-streaming foreground service that does not use `MediaProjection`.

**What RustDesk does:**
`MainService` declares `android:foregroundServiceType="mediaProjection"` — correct for the
standard capture path. However, when the Knox path is active, `MediaProjection` is not used at
all. On Android 14+, the foreground service type must match the service's actual behavior, and
using `mediaProjection` type without an active `MediaProjection` object could cause the OS to
flag or kill the service.

**Files involved:**
- `android/app/src/main/AndroidManifest.xml`
  — `<service android:name=".MainService" android:foregroundServiceType="mediaProjection" />`
- `android/app/src/main/kotlin/com/carriez/flutter_hbb/MainService.kt`
  — `startForeground()` calls

**What needs to be done:**
1. Update the manifest to declare both types:
   `android:foregroundServiceType="mediaProjection|dataSync"`
2. In `MainService`, when starting foreground in Knox mode, use
   `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC` instead of
   `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`.
3. Add `android.permission.FOREGROUND_SERVICE_DATA_SYNC` to the manifest (as the sample does).

**Reference files:**
- `Fort-ControlTower/fort_rc_sample/app/src/main/AndroidManifest.xml`
  — `foregroundServiceType="dataSync"` and `FOREGROUND_SERVICE_DATA_SYNC` permission
- `Fort-ControlTower/fort_rc_sample/app/src/main/java/il/co/tmg/fort_rc/services/RemoteCaptureService.kt`
  — `ensureForeground()` using `FOREGROUND_SERVICE_TYPE_DATA_SYNC`

---

## Point 10 — IPC Contract: Constants Scattered, No Central Contract Object

**Priority:** Low

**What the sample does:**
All IPC constants (package names, class names, message codes, bundle keys, error codes) live in
`CaptureControlContract.kt` as a single Kotlin `object`. Intent factory methods
`newControlServiceIntent()` and `newCaptureServiceIntent()` ensure consistent construction.
All files import from one source of truth.

**What RustDesk does:**
Constants are defined as private `companion object` members inside `DispatcherActivity.kt`:
```kotlin
private const val FORT_CT_PACKAGE = "il.co.tmg.fort_ct"
private const val CAPTURE_CONTROL_SERVICE = "il.co.tmg.fort_ct.ipc.CaptureControlService"
private const val CAPTURE_SERVICE = "il.co.tmg.fort_ct.ipc.CaptureService"
private const val MSG_GET_SESSION_INFO = 1
private const val MSG_START_SESSION = 2
```
Bundle key strings (e.g., `"remote_session_id"`, `"session_info"`, `"remote_id"`, `"token"`) are
inlined as string literals with no central definition. If Fort CT changes a key name or adds a
new message code, every usage must be found and updated individually.

**Files involved:**
- `android/app/src/main/kotlin/il/co/tmg/fort_rc/activities/DispatcherActivity.kt`
  — all `private const val` declarations in companion object
- `android/app/src/main/kotlin/com/carriez/flutter_hbb/KnoxCapturer.kt`
  — `KNOX_PACKAGE`, `KNOX_SERVICE` top-level constants

**What needs to be done:**
Create `android/app/src/main/kotlin/com/carriez/flutter_hbb/FortCaptureContract.kt` containing:
- Package and service class name constants
- Message code constants (`MSG_GET_SESSION_INFO`, `MSG_START_SESSION`)
- Bundle key constants (`KEY_REMOTE_SESSION_ID`, `KEY_SESSION_INFO`, `KEY_REMOTE_ID`, `KEY_TOKEN`)
- Error code constants (`ERROR_ANOTHER_CLIENT_ACTIVE`, `ERROR_INVALID_STATE`, `ERROR_SESSION_MISMATCH`)
- `newControlServiceIntent()` and `newCaptureServiceIntent()` factory methods
- `SessionPayload` data class

Replace all scattered definitions in `DispatcherActivity` and `KnoxCapturer` with imports from
this object.

**Reference files:**
- `Fort-ControlTower/fort_rc_sample/app/src/main/java/il/co/tmg/fort_rc/ipc/CaptureControlContract.kt`
  — the exact pattern to replicate
