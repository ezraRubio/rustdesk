# Knox Auto-Start Testing Guide

## Overview

This document provides testing instructions for the Knox auto-start implementation in RustDesk Android. The implementation allows RustDesk to automatically start screen capture using the Knox service without prompting for MediaProjection permissions.

## Prerequisites

- RustDesk Android app built with Knox integration
- Knox service app (il.co.tmg.screentool) installed on test device
- Android Studio for viewing logcat output
- Test device with Knox capabilities

## Test Scenarios

### Test 1: Knox Service Available - Auto-Start Success

**Objective**: Verify that RustDesk automatically starts with Knox capture when the Knox service is available.

**Setup**:
1. Install Knox service app (il.co.tmg.screentool) on the test device
2. Ensure Knox service app has necessary permissions
3. Install RustDesk app
4. Ensure RustDesk is NOT currently running

**Test Steps**:
1. Launch RustDesk app
2. Observe the UI immediately upon launch

**Expected Results**:
- ✅ No "Start service" button is displayed
- ✅ Server info/ID is displayed immediately
- ✅ No MediaProjection permission dialog appears
- ✅ Service status shows as "ready"

**Logcat Verification**:
Look for these log messages in order:
```
D/LOG_SERVICE: Attempting auto-start with Knox
D/LOG_SERVICE: Knox service available: true
I/LOG_SERVICE: Knox CaptureService connected
I/LOG_SERVICE: Knox capture initialized: [width]x[height]
I/LOG_SERVICE: Knox auto-start successful, service ready
```

**Test Actual Capture**:
1. From another device, connect to this RustDesk instance
2. Verify screen content is visible and updates correctly

**Logcat for Capture Start**:
```
D/LOG_SERVICE: Start Capture
I/LOG_SERVICE: Using pre-initialized Knox capture
```

---

### Test 2: Knox Service Unavailable - Fallback to MediaProjection

**Objective**: Verify that RustDesk falls back to standard MediaProjection flow when Knox is not available.

**Setup**:
1. Uninstall Knox service app (il.co.tmg.screentool) from the test device
2. OR disable the Knox service app in Settings > Apps
3. Ensure RustDesk is NOT currently running

**Test Steps**:
1. Launch RustDesk app
2. Observe the UI upon launch

**Expected Results**:
- ✅ "Start service" button IS displayed
- ✅ Server info is NOT displayed yet
- ✅ Service status shows as "not ready"

**Logcat Verification**:
```
D/LOG_SERVICE: Attempting auto-start with Knox
I/LOG_SERVICE: Knox service not available, waiting for manual start
```

**Continue Test - Click Start Service**:
1. Click the "Start service" button
2. MediaProjection permission dialog should appear
3. Grant permission

**Expected Results**:
- ✅ MediaProjection permission dialog is shown
- ✅ After granting permission, button disappears and server info appears
- ✅ Service uses MediaProjection capture (not Knox)

**Logcat for MediaProjection**:
```
D/LOG_SERVICE: Start Capture
I/LOG_SERVICE: Using MediaProjection screen capture
```

---

### Test 3: Knox Binding Timeout - Graceful Fallback

**Objective**: Verify graceful fallback when Knox service is installed but fails to bind in time.

**Setup**:
1. Install Knox service app but ensure it's in a problematic state (e.g., force stopped)
2. Ensure RustDesk is NOT currently running

**Test Steps**:
1. Launch RustDesk app
2. Wait for binding timeout (5 seconds)
3. Observe UI behavior

**Expected Results**:
- ✅ "Start service" button IS displayed after timeout
- ✅ No crash or error dialog
- ✅ User can proceed with MediaProjection flow

**Logcat Verification**:
```
D/LOG_SERVICE: Attempting auto-start with Knox
D/LOG_SERVICE: Knox service available: true
E/LOG_SERVICE: Knox service binding timeout
W/LOG_SERVICE: Knox auto-start: Failed to bind service
I/LOG_SERVICE: Knox service not available, waiting for manual start
```

---

### Test 4: Knox Service Disconnects After Init

**Objective**: Verify behavior when Knox service disconnects after successful initialization.

**Setup**:
1. Install Knox service app
2. Ensure RustDesk starts successfully with Knox auto-start

**Test Steps**:
1. Launch RustDesk (should auto-start with Knox)
2. Force stop Knox service app from Settings
3. From another device, attempt to connect to this RustDesk instance
4. Observe behavior

**Expected Results**:
- ✅ Connection attempt may fail initially
- ✅ On retry or next `startCapture()` call, should fall back to MediaProjection
- ✅ No crash

**Logcat Verification**:
```
W/LOG_SERVICE: Knox CaptureService disconnected
```

---

### Test 5: Start on Boot with Knox

**Objective**: Verify Knox auto-start works when service is started on device boot.

**Setup**:
1. Install Knox service app
2. Install RustDesk app
3. Enable "Start on boot" in RustDesk settings
4. Grant necessary permissions for boot start

**Test Steps**:
1. Reboot the device
2. Wait for device to fully boot
3. Check RustDesk service status (notification should appear)

**Expected Results**:
- ✅ RustDesk notification appears after boot
- ✅ Service is ready (Knox initialized)
- ✅ No MediaProjection permission prompt

**Logcat Verification**:
```
D/tagBootReceiver: onReceive android.intent.action.BOOT_COMPLETED
D/LOG_SERVICE: Attempting auto-start with Knox
I/LOG_SERVICE: Knox auto-start successful, service ready
```

---

### Test 6: Service Restart Cycle

**Objective**: Verify Knox auto-start works correctly across service restart cycles.

**Test Steps**:
1. Launch RustDesk (Knox auto-starts)
2. Stop the service
3. Start the service again
4. Repeat steps 2-3 multiple times

**Expected Results**:
- ✅ Each service start correctly attempts Knox auto-start
- ✅ No memory leaks or resource leaks
- ✅ Consistent behavior across cycles

**Logcat Verification**:
```
I/LOG_SERVICE: Knox auto-start successful, service ready
(service stop logs)
I/LOG_SERVICE: Knox auto-start successful, service ready
(repeat)
```

---

### Test 7: onStartCommand with Knox Already Ready

**Objective**: Verify that `onStartCommand` correctly skips MediaProjection when Knox is already initialized.

**Setup**:
1. Install Knox service app
2. Launch RustDesk (Knox should auto-start in onCreate)

**Test Steps**:
1. Observe initial app launch
2. Check logcat when `init_service` is called from Flutter

**Expected Results**:
- ✅ Knox initializes in `onCreate`
- ✅ `onStartCommand` detects Knox is ready and skips MediaProjection request
- ✅ No permission dialog shown

**Logcat Verification**:
```
D/LOG_SERVICE: Attempting auto-start with Knox (from onCreate)
I/LOG_SERVICE: Knox auto-start successful, service ready
...
I/LOG_SERVICE: Knox already initialized, skipping MediaProjection request (from onStartCommand)
```

---

## Debugging Commands

### View RustDesk Logs
```bash
adb logcat -s LOG_SERVICE:D LOG_SERVICE:I LOG_SERVICE:W LOG_SERVICE:E
```

### View Knox Service Logs
```bash
adb logcat -s CaptureService:*
```

### Check if Knox Service is Installed
```bash
adb shell pm list packages | grep il.co.tmg.screentool
```

### Check if Knox Service is Running
```bash
adb shell dumpsys activity services | grep il.co.tmg.screentool
```

### Force Stop Knox Service (for testing)
```bash
adb shell am force-stop il.co.tmg.screentool
```

### Simulate Boot Completed (for testing boot receiver)
```bash
adb shell am broadcast -a com.carriez.flutter_hbb.DEBUG_BOOT_COMPLETED
```

---

## Common Issues and Solutions

### Issue: Knox auto-start fails every time
**Check**:
- Is Knox service app installed? (`adb shell pm list packages | grep il.co.tmg`)
- Does Knox service app have necessary permissions?
- Is Knox service responsive? (Try binding manually)

### Issue: MediaProjection permission shown even with Knox installed
**Check**:
- Verify Knox auto-start logs in logcat
- Check if Knox binding timed out (5 second timeout)
- Verify Knox service is not force-stopped

### Issue: Screen capture works but frames are not delivered
**Check**:
- Verify `_isStart` is set to true (check when `startCapture()` is called)
- Check Knox callback logs in Knox service
- Verify `FFI.onVideoFrameUpdate()` is being called

---

## Test Result Template

Use this template to document test results:

```
## Test Results - [Date]

### Test 1: Knox Auto-Start Success
- [ ] PASS / [ ] FAIL
- Notes: 

### Test 2: Fallback to MediaProjection
- [ ] PASS / [ ] FAIL
- Notes: 

### Test 3: Knox Binding Timeout
- [ ] PASS / [ ] FAIL
- Notes: 

### Test 4: Knox Service Disconnects
- [ ] PASS / [ ] FAIL
- Notes: 

### Test 5: Start on Boot
- [ ] PASS / [ ] FAIL
- Notes: 

### Test 6: Service Restart Cycle
- [ ] PASS / [ ] FAIL
- Notes: 

### Test 7: onStartCommand Skip MediaProjection
- [ ] PASS / [ ] FAIL
- Notes: 

### Device Info:
- Device Model: 
- Android Version: 
- RustDesk Version: 
- Knox Service Version: 
```

---

## Success Criteria

All tests must pass with the following criteria:

1. ✅ Knox auto-start works when service is available
2. ✅ Silent fallback to MediaProjection when Knox is unavailable
3. ✅ No crashes or ANR (Application Not Responding)
4. ✅ Consistent behavior across service restart cycles
5. ✅ No memory leaks (verify with Android Profiler)
6. ✅ Screen capture quality identical to non-Knox capture
7. ✅ Performance acceptable (frame delivery latency < 100ms)
