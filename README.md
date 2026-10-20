# Driver Drowsiness Detection System

## 1. Overview

Driver Drowsiness Detection is an Android application prototype that analyzes facial landmarks using the device's front-facing camera to identify visual indicators associated with drowsiness. It monitors eye closure, mouth opening, and head pose, then presents detection status and can trigger an audible and vibration warning.

The application uses a foreground service to support continued monitoring when its Activity is not visible.

**Project type:** Android application / computer vision prototype  
**Primary language:** Java  
**Platform:** Android  
**Computer vision:** MediaPipe Face Landmarker

## 2. Problem Statement

Driver fatigue can reduce attention and reaction time. This project explores how a smartphone camera and on-device facial landmark detection can be used to identify potential visual signs of drowsiness and provide an alert.

The application is an educational prototype. It is not a certified driver-monitoring or accident-prevention system.

## 3. Objectives

- Detect facial landmarks from the front-facing camera.
- Estimate eye closure using the Eye Aspect Ratio (EAR).
- Identify sustained mouth opening as a possible yawn.
- Estimate head orientation using a geometric heuristic.
- Display detection status and an experimental risk score.
- Provide audible and vibration warnings.
- Support user-controlled start, stop, and warning acknowledgment.
- Maintain monitoring through a foreground service when the Activity is not visible.

## 4. Technology Stack

| Technology | Purpose |
|---|---|
| Java | Application and detection logic |
| Android SDK | Mobile application platform |
| XML | User interface layout |
| CameraX | Camera preview and frame analysis |
| MediaPipe Tasks Vision | Facial landmark detection |
| Android LifecycleService | Ongoing monitoring service |
| Android notification APIs | Persistent monitoring notification |
| Android Ringtone and Vibrator APIs | Warning feedback |
| Gradle | Build and dependency management |

## 5. System Architecture

The application is divided into three logical layers.

### Presentation layer
`MainActivity` manages the interface, monitoring controls, camera preview attachment, detection status, EAR values, head-pose information, and risk-score display.

### Monitoring and vision layer
`DrowsinessMonitoringService` manages the camera pipeline and MediaPipe Face Landmarker. CameraX supplies frames for analysis, which are converted into images for inference. The service publishes detection updates to the Activity when it is bound.

### Detection and alert layer
Separate Java classes process the measurements:

- `EarSmoother`: smooths eye measurements using a small sample window.
- `DrowsinessDetector`: evaluates sustained eye closure.
- `YawnDetector`: evaluates sustained mouth opening.
- `HeadPoseDetector`: estimates head orientation using a geometric heuristic.
- `DrowsinessRiskScorer`: produces an experimental, display-oriented risk score.

The service also manages the warning sound, vibration, and acknowledgment behavior.

## 6. Detection Workflow

1. The user grants camera permission and starts monitoring.
2. The monitoring service starts and displays its foreground notification.
3. CameraX opens the front-facing camera and provides frames.
4. Image analysis throttles incoming frames before processing.
5. MediaPipe Face Landmarker estimates facial landmarks.
6. Eye, mouth, and head-pose measurements are calculated.
7. Detection components evaluate the measurements over time.
8. The application updates its status and experimental risk score.
9. When the eye-closure warning condition is met, the application can activate an audible and vibration alert.
10. The user can acknowledge a warning or stop monitoring.

## 7. Current Detection Configuration

| Component | Current configuration |
|---|---|
| Face detection | MediaPipe Face Landmarker, configured for one face |
| Eye closure | EAR threshold: 0.18 |
| Sustained eye closure | 1,500 ms |
| EAR smoothing | Five-sample median smoother |
| Yawn detection | Mouth-opening heuristic with a sustained-duration condition |
| Head-pose detection | Geometric heuristic with a sustained-duration condition |
| Frame analysis | Throttled to approximately 10 frames per second |
| Risk score | Experimental heuristic score, capped at 100 |

These values are implementation settings, not validated universal thresholds. Their suitability must be evaluated under controlled conditions and across different users, lighting, camera angles, and devices.

## 8. Reliability and Testing

Initial manual testing on a physical Android device demonstrated that:

- The application builds and launches successfully.
- Camera preview and detection operate while monitoring is active.
- Monitoring continued when the application was minimized and the screen was locked during the tested session.
- A 15-minute monitoring session completed without an observed interruption.
- Battery usage did not appear to be a major concern during the user's initial assessment.

These observations are preliminary, not a comprehensive validation.

Recommended additional tests include:

- Normal face with eyes open and mouth closed.
- Brief and sustained eye closure.
- Ordinary talking compared with a simulated yawn.
- Head movement and changes in camera angle.
- Poor lighting, glasses, and partial facial occlusion.
- Camera permission denial and camera unavailability.
- Warning acknowledgment and alarm recovery.
- Repeated start/stop cycles and longer screen-off sessions.
- CPU usage, memory usage, detection latency, and battery consumption.

Record false positives and missed detections rather than relying only on successful demonstrations.

## 9. Limitations

- EAR thresholds can vary with facial characteristics, camera placement, and lighting.
- Mouth opening does not uniquely indicate yawning.
- The head-pose implementation is heuristic and may not reliably classify every head movement.
- The risk score is experimental and has not been validated against a recognized fatigue assessment standard.
- Camera access, background execution, and service behavior can vary by Android version and device manufacturer.
- The prototype has not been established as suitable for real-world driving or safety-critical use.

**Safety note:** Test the application only while stationary, with the vehicle safely parked. Never interact with or rely on the prototype while driving. Anyone feeling sleepy should stop in a safe location and rest.

## 10. Future Improvements

- Evaluate detection accuracy with a structured test dataset.
- Measure false-positive and false-negative rates.
- Improve robustness to lighting, occlusion, and camera positioning.
- Add structured logging and diagnostic information.
- Profile CPU, memory, inference latency, and energy consumption.
- Improve error handling and monitoring-state reporting.
- Add automated tests for the detection algorithms.
- Prepare a reproducible test report before making accuracy claims.

## 11. Conclusion

This project demonstrates an Android-based computer vision pipeline that uses facial landmarks to estimate visual indicators associated with drowsiness. Its modular detection components, camera-analysis pipeline, and foreground monitoring service provide a foundation for further experimentation, testing, and improvement.

The current implementation should be considered a working educational prototype rather than a validated driver-safety solution.
