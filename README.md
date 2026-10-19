# Driver Drowsiness Detection

An Android prototype that uses the front-facing camera and MediaPipe Face Landmarker to detect visual signs associated with driver drowsiness and provide an audible/vibration alert.

> **Project status:** Prototype under active development. Background camera monitoring, especially with the screen off, must be implemented and validated on supported Android versions and devices before this project is described as ready for real-world cab-driver use. This is not a certified driver-safety system and must not be relied on as the sole protection against drowsy driving.

## Features

- **Eye-closure detection:** Estimates Eye Aspect Ratio (EAR) from facial landmarks and triggers the existing alert after sustained eye closure.
- **Yawn detection:** Estimates Mouth Aspect Ratio (MAR) and detects a sustained mouth opening.
- **Audible and vibration alert:** Reuses the app's alarm behavior for confirmed eye-closure and yawn warnings, with an acknowledgement control.
- **Head-pose indicator:** Displays a landmark-based head-down estimate. Treat this as a prototype signal, not a validated head-pose measurement.
- **Experimental risk score:** Combines eye-closure duration, the yawn signal, and the head-down signal into a heuristic score from 0 to 100. It is not a probability or a medically validated fatigue score.
- **On-device vision processing:** Uses MediaPipe Face Landmarker with a local `.task` model asset.

## Technology stack

- Java
- Android SDK and Android Studio
- XML layouts
- CameraX
- MediaPipe Tasks Vision (`com.google.mediapipe:tasks-vision:0.10.35`)
- Gradle
- JUnit tests for the risk-scoring logic

The current project package is `com.example.driverdrowsiness`.

## How detection works

### Eye closure

The app calculates an Eye Aspect Ratio (EAR) from selected MediaPipe face landmarks and uses a smoothing step to reduce brief fluctuations. Sustained closure is evaluated by the drowsiness detector. The current prototype uses a 1,500 ms warning duration.

### Yawning

The app calculates a Mouth Aspect Ratio (MAR) using upper/lower lip and mouth-corner landmarks. The current prototype considers a yawn when MAR is at least `0.60` for at least `800 ms`. These are experimental thresholds and may need calibration across people, camera positions, and lighting.

### Risk score

The experimental scorer assigns points for eye-closure duration, a possible yawn, and a sustained head-down signal. Its output is a rule-based score, not a probability. It is intended for prototype evaluation and should not be used to make safety-critical decisions.

## Requirements

- Android Studio with a compatible Android SDK and Gradle setup for this project
- An Android phone with a working front-facing camera
- Camera permission granted
- The MediaPipe model file at:

  `app/src/main/assets/face_landmarker.task`

## Run from Android Studio

1. Clone the repository:

   ```bash
   git clone https://github.com/Akashvenkatesh-347/DriverDrowsiness.git
   ```

2. Open the project directory in Android Studio.
3. Allow Gradle sync to finish.
4. Connect an Android phone or start a compatible emulator. A physical device with a front camera is recommended for meaningful vision testing.
5. Grant camera permission when prompted.
6. Run the `app` configuration and verify camera preview, EAR/MAR readings, warnings, acknowledgement, and the experimental score.

If the model asset is missing, add the correct `face_landmarker.task` file to `app/src/main/assets/` before running the app.

## Build an APK

### Debug APK for testing

In Android Studio:

1. Select **Build → Build Bundle(s) / APK(s) → Build APK(s)** (menu wording may vary by Android Studio version).
2. Wait for the build to complete.
3. Click **Locate** in the build notification.

The debug APK is normally created at:

`app/build/outputs/apk/debug/app-debug.apk`

Or run from the project root in PowerShell:

```powershell
.\gradlew.bat assembleDebug
```

Then check:

`app\build\outputs\apk\debug\app-debug.apk`

A debug APK is useful for testing; do not treat it as a production release.

### Signed release APK

1. In Android Studio, select **Build → Generate Signed Bundle / APK**.
2. Choose **APK**, then select **Next**.
3. Choose the `app` module.
4. Create a new keystore or select an existing one.
5. Store the keystore and passwords securely. Do not commit them to Git.
6. Select the `release` build variant and complete the wizard.
7. Find the generated APK in the `app/build/outputs/` directory (the exact subfolder depends on the selected variant and configuration).
8. Install it on a test device and validate it before sharing.

Keep the same signing key for future updates. Losing the signing key can prevent users from installing updates over an existing installation.

## Background monitoring status

The current project baseline described in this README uses CameraX from the app's activity. **Do not assume camera monitoring continues when the app is minimized or the screen is off.** That behavior requires a deliberate Android lifecycle/foreground-service design, appropriate manifest declarations and permissions for the app's target SDK, visible user consent, and testing on real devices.

Planned background-monitoring work:

- Add a clear **Start monitoring / Stop monitoring** flow.
- Start camera monitoring while the activity is visible, after camera permission is granted.
- Investigate a camera foreground service for minimized operation, with a persistent notification and clear monitoring status.
- Handle service startup failures, camera contention, permission revocation, and service termination visibly.
- Test screen-off behavior separately; Android version, target SDK, device policy, and manufacturer battery restrictions can affect it.
- Never show “monitoring” as active if camera frames are not actually being processed.

## Reliability testing checklist

- [ ] Normal face, eyes open, mouth closed: no alert.
- [ ] Brief eye closure: no prolonged-closure alert.
- [ ] Sustained eye closure: existing alarm activates.
- [ ] Ordinary talking: no repeated yawn alert.
- [ ] Sustained simulated yawn: yawn warning and alarm activate.
- [ ] Acknowledge silences the alarm as designed.
- [ ] Face leaves the frame: UI reports unavailable/no face rather than implying monitoring is effective.
- [ ] Test in different lighting and camera angles.
- [ ] Minimize the app and verify whether frame processing continues.
- [ ] Test screen-off behavior separately on each target device.
- [ ] Verify monitoring status when permission is denied or the camera becomes unavailable.

Record false positives and missed detections. A small number of successful demonstrations is not enough to establish real-world accuracy.

## Repository layout

```text
DriverDrowsiness/
├── app/
│   ├── src/main/
│   │   ├── assets/
│   │   │   └── face_landmarker.task
│   │   ├── java/com/example/driverdrowsiness/
│   │   │   ├── MainActivity.java
│   │   │   ├── DrowsinessDetector.java
│   │   │   ├── DrowsinessRiskScorer.java
│   │   │   ├── EarSmoother.java
│   │   │   ├── HeadPoseDetector.java
│   │   │   └── YawnDetector.java
│   │   └── res/layout/activity_main.xml
│   └── src/test/java/com/example/driverdrowsiness/
│       └── DrowsinessRiskScorerTest.java
└── README.md
```

This tree reflects the known project files; minor differences may exist in the current checkout.

## Limitations and safety

- This is an educational prototype, not a certified driver-monitoring system.
- Detection performance can vary with lighting, glasses, camera placement, facial differences, motion, and occlusion.
- The heuristic risk score is not a medical diagnosis or validated measure of fatigue.
- Background and screen-off camera behavior is not considered supported until explicitly implemented and tested.
- Do not test or interact with the app while driving. Test while stationary and with the vehicle safely parked.
- Drivers should stop in a safe location and rest if they feel sleepy; this app cannot guarantee prevention of an accident.

## License

No license has been specified yet. Add a `LICENSE` file before presenting this repository as open source.
