# Driver Drowsiness Detection — Test Plan

## 1. Purpose

This document records functional, detection, lifecycle, and performance tests for the Driver Drowsiness Detection Android application.

## 2. Environment

- Platform: Android
- Implementation language: Java
- Camera pipeline: CameraX
- Facial landmark detection: MediaPipe Face Landmarker
- Device: Physical Android phone
- Test date: To be recorded

## 3. Tests Already Performed

| Test | Observed result | Status |
|---|---|---|
| Application build and launch | App builds and launches successfully | Passed |
| Camera preview and detection | Preview and detection operate during monitoring | Passed |
| Minimize application | Monitoring continued during the observed test | Passed |
| Lock and unlock screen | Monitoring continued until Stop Monitoring was tapped | Passed |
| 15-minute monitoring session | No interruption was observed during the session | Passed |
| Battery usage | No major issue noticed during initial use | Informal observation |

These results describe observed tests, not a guarantee of reliability on every Android device.

## 4. Functional Test Cases

| ID | Test | Expected result | Status |
|---|---|---|---|
| F01 | Grant camera permission | Camera monitoring can start | Pending |
| F02 | Deny camera permission | App reports that camera permission is required | Pending |
| F03 | Start monitoring | Service starts and status updates | Pending |
| F04 | Stop monitoring | Monitoring and camera processing stop | Pending |
| F05 | Acknowledge an active warning | Warning sound stops according to the implemented behavior | Pending |
| F06 | Camera unavailable | App reports a failure instead of claiming successful monitoring | Pending |

## 5. Detection Test Cases

Perform these tests while stationary, never while driving.

| ID | Test scenario | Expected behavior | Status |
|---|---|---|---|
| D01 | Face visible, eyes open | No prolonged-eye-closure warning | Pending |
| D02 | Brief eye closure | No prolonged-closure warning | Pending |
| D03 | Sustained eye closure | Eye-closure warning activates when the implemented condition is met | Pending |
| D04 | Normal talking | Should not repeatedly trigger a yawn warning | Pending |
| D05 | Simulated sustained yawn | Mouth-opening detector responds according to its configured threshold | Pending |
| D06 | Head lowered for a sustained period | Head-pose heuristic responds according to its configured threshold | Pending |
| D07 | Face leaves camera view | Detection should report that a usable face is unavailable | Pending |
| D08 | Different lighting and camera angles | Record detection behavior and failures | Pending |

## 6. Lifecycle and Performance Tests

| ID | Test | Measurements or expected result | Status |
|---|---|---|---|
| L01 | Lock screen during monitoring | Confirm monitoring continues | Passed in initial test |
| L02 | Minimize the app | Confirm monitoring continues | Passed in initial test |
| L03 | Repeat start/stop cycles | No stuck camera or service state | Pending |
| P01 | Measure CPU usage | Record measurement method and result | Pending |
| P02 | Measure memory usage | Record memory consumption | Pending |
| P03 | Measure detection latency | Record time from a controlled condition to the warning | Pending |
| P04 | Measure battery usage | Repeat a consistent-duration test | Informal initial observation |

## 7. Recording Results

For every test, record:

- Test ID and date
- Device and Android version
- Steps performed
- Expected behavior
- Actual behavior
- Pass, fail, or blocked
- Relevant screenshots or logs
- Defect and follow-up action, if any

## 8. Limitations

Passing these manual tests does not establish a validated detection accuracy rate or prove that the application prevents accidents. False positives, missed detections, environmental variation, and device-specific behavior must be evaluated separately.

The application is an educational prototype, not a certified driver-safety system.
