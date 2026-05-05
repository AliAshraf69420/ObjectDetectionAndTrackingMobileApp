# RC Bowling Tracker 

An Android mobile app that records a toy RC car bowling setup, runs **fully offline** YOLOv8 object detection on every frame, tracks pin falls and car trajectory, and produces an annotated output video with fall order, path overlay, and scoring — all on-device.

Built with **React Native + Expo** (TypeScript) for the UI and **Kotlin native modules** for the heavy-lifting: video decode → TFLite inference → pin tracking → annotated video encoding via `MediaCodec` + OpenGL ES surface input.

---

## Features

- **Live video capture** via `expo-camera`
- **On-device inference** — no server, no network required at inference time
- **Custom-trained YOLOv8n** detector (fine-tuned on a toy bowling dataset)
- **4-class detection**: `ball`, `car`, `fallen-pins`, `standing-pins`
- **Pin fall tracking** with standing → fallen transition detection and hit-order assignment
- **RC car path tracking** with trajectory overlay
- **Annotated output video** rendered with:
  - Color-coded bounding boxes (green=car, yellow=ball, magenta=standing, red+glow=fallen)
  - Fall order labels and timestamps
  - Car path line (red)
  - Standing / fallen pin count panel
  - Fall History timeline panel
- **Result summary** — elapsed time, total pins knocked down, fall event table, car path data

---

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│  React Native / Expo (TypeScript)                        │
│  ├── RecordScreen  — camera preview, start/stop          │
│  ├── ProcessingScreen — progress bar + status            │
│  └── ResultScreen  — annotated video + score summary     │
└──────────────────┬───────────────────────────────────────┘
                   │  ProcessingModule.processVideo(uri)
                   ▼
┌──────────────────────────────────────────────────────────┐
│  Kotlin Native Module (Android)                          │
│  ├── VideoProcessor  — decode / encode / main loop       │
│  ├── TFLiteDetector  — YOLOv8n inference (640×640)       │
│  ├── PinTracker      — IoU + center-distance matching    │
│  └── EglSurfaceHelper — GPU surface input for encoder    │
└──────────────────────────────────────────────────────────┘
```

### Processing Pipeline

```
User records video
        ↓
Raw video saved to local storage
        ↓
Native module decodes frames (MediaCodec)
        ↓
Each frame → Bitmap → TFLite YOLOv8n inference (640×640)
        ↓
Detections split: pins → PinTracker, car → path log, ball → annotate
        ↓
PinTracker matches detections across frames (IoU/distance)
        ↓
Standing→fallen transitions detected, fall order assigned
        ↓
Annotated frame drawn (Canvas) → GPU texture → encoder input surface
        ↓
Encoded H.264 output via MediaCodec + MediaMuxer
        ↓
Saved to gallery, result metadata returned to JS
```

---

## Repository Layout

```
.
├── Docs/                          # Architecture docs, project spec
│   ├── BundlingPlan.md
│   ├── Layers.md
│   ├── Plan.md
│   ├── Stack.md
│   └── DSAI352_Bonus_Project_Specification.pdf
│
├── Notebooks/
│   └── CV_New_method.ipynb        # Reference inference notebook (Colab)
│
├── app/                           # Expo + React Native application
│   ├── app/                       # Expo Router screens
│   │   ├── _layout.tsx
│   │   └── record.tsx
│   ├── android/                   # Native Android project
│   │   └── app/src/main/
│   │       ├── assets/            # Bundled TFLite model
│   │       │   └── best_unquantized.tflite
│   │       └── java/.../processing/
│   │           ├── ProcessingModule.kt
│   │           ├── ProcessingPackage.kt
│   │           ├── VideoProcessor.kt
│   │           ├── TFLiteDetector.kt
│   │           └── PinTracker.kt
│   ├── Model/                     # Source model files
│   │   ├── best_float32.tflite
│   │   └── best_unquantized.tflite
│   ├── app.json
│   ├── package.json
│   └── tsconfig.json
│
└── README.md
```

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| **UI** | React Native, Expo, TypeScript, Expo Router |
| **Build** | EAS Development Build (custom native modules) |
| **Camera** | `expo-camera` |
| **Video Playback** | `expo-video` |
| **Native Code** | Kotlin, Expo Modules API |
| **Inference** | TensorFlow Lite Android (CPU, 4 threads) |
| **Model** | Fine-tuned YOLOv8n → TFLite (unquantized float32) |
| **Video I/O** | Android `MediaCodec`, `MediaMuxer`, `MediaExtractor` |
| **Encoder Input** | OpenGL ES 2.0 surface (EGL + GLES20) — no stride issues |
| **Tracking** | IoU + center-distance matching (rule-based) |
| **Rendering** | Android `Canvas` / `Bitmap` drawing |

---

## Model Details

- **Architecture**: YOLOv8n (nano) fine-tuned via transfer learning
- **Input size**: 640 × 640 pixels
- **Output**: `[1, 300, 6]` — up to 300 detections, each `[x1, y1, x2, y2, confidence, class_id]`
- **NMS**: Built into the exported model
- **Classes** (4):

| ID | Class | Color |
|----|-------|-------|
| 0 | `ball` | Yellow |
| 1 | `car` | Green |
| 2 | `fallen-pins` | Red (+ yellow glow) |
| 3 | `standing-pins` | Magenta |

- **Confidence threshold**: 0.35 (all classes)
- **Format**: TFLite float32 (unquantized, full precision), ~10 MB

---

## Tracking Logic

The `PinTracker` mirrors the logic from the reference Colab notebook:

| Parameter | Value |
|-----------|-------|
| Match distance | 70 px |
| Match IoU | 0.20 |
| Match score | `IoU - (distance / 1000)` |
| Fall detection | Instant on standing → fallen class transition |
| Memory cleanup | Prune tracks missing > 20 frames |

**Fall order** is assigned at the moment of the first standing → fallen transition for each tracked pin. The fall time is computed as `frameIndex / fps` in seconds.

---

## How to Build & Run

### Prerequisites

| Tool | Required version | Notes |
|------|-----------------|-------|
| **Node.js** | 18 or later | `node --version` to verify |
| **JDK** | **17 exactly** | 11 and 21 both fail; use `java -version` to verify |
| **Android SDK Platform** | **35** | Install via SDK Manager in Android Studio |
| **Android Build Tools** | **35.0.0** | Install via SDK Manager |
| **Android NDK** | **27.1.12297006** | Install via SDK Manager → SDK Tools → NDK (Side by side) |
| **Android device** | API 24+ (Android 7.0+) | USB debugging must be enabled |

### First-Time Setup (compile on the first try)

> **Critical**: The `android/` directory is already committed with custom Kotlin native modules. **Do not run `expo prebuild`** — it would overwrite the native code and break the build.

**1. Set your Android SDK path**

Create `app/android/local.properties` (not tracked by git) pointing at your SDK:

```
# macOS / Linux
sdk.dir=/Users/<you>/Library/Android/sdk

# Windows
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\sdk
```

Or export the environment variable instead:

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk   # macOS
export ANDROID_HOME=$HOME/Android/Sdk            # Linux
```

**2. Verify your JDK**

```bash
java -version   # must print "17"
```

If you have multiple JDKs, set `JAVA_HOME` explicitly:

```bash
export JAVA_HOME=/path/to/jdk-17
```

**3. Install JS dependencies**

```bash
cd app
npm install
```

**4. Connect your device**

- Enable **Developer Options** on the device (tap Build Number 7 times in Settings → About)
- Enable **USB Debugging**
- Connect via USB and accept the RSA fingerprint prompt on the device
- Verify the device is visible: `adb devices` (should list your device, not `unauthorized`)

**5. Build and run**

```bash
npx expo run:android
```

The first build downloads Gradle 8.14.3 and all Maven dependencies (~500 MB). This typically takes **10–15 minutes** on a fresh machine. Subsequent builds are fast.

### Common First-Build Failures

| Error | Fix |
|-------|-----|
| `SDK location not found` | Create `android/local.properties` with `sdk.dir` (step 1) |
| `Unsupported class file major version` | JDK is not 17; switch versions and set `JAVA_HOME` |
| `Failed to find NDK` | Install NDK **27.1.12297006** via Android Studio SDK Manager → SDK Tools → NDK (Side by side) |
| `No connected devices` | Run `adb devices`; check USB debugging is on and RSA prompt was accepted |
| `Gradle build daemon disappeared` | Increase heap: add `org.gradle.jvmargs=-Xmx4096m` to `android/gradle.properties` |
| `Could not resolve org.tensorflow:tensorflow-lite` | No internet during first build; Gradle must download Maven deps — connect and retry |

### Model Setup

The model file `best_unquantized.tflite` is already bundled in `app/android/app/src/main/assets/`. If you want to use a different model:

1. Place your `.tflite` file in `app/android/app/src/main/assets/`
2. Update the `MODEL_FILE` constant in `TFLiteDetector.kt`
3. Rebuild the app

---

## Output

The app produces:

1. **Annotated video** saved to the device gallery, containing:
   - Bounding boxes for all detected objects
   - Pin IDs and fall order labels
   - Car trajectory path
   - Standing/fallen pin count panel (top-left)
   - Fall History timeline (top-right)

2. **Structured result data** returned to the JS layer:

```json
{
  "outputVideoUri": "content://...",
  "elapsedMs": 8500,
  "pinsKnockedDown": 4,
  "pinEvents": [
    { "pinTrackId": 2, "order": 1, "timeMs": 1200 },
    { "pinTrackId": 5, "order": 2, "timeMs": 1800 },
    { "pinTrackId": 1, "order": 3, "timeMs": 2400 },
    { "pinTrackId": 3, "order": 4, "timeMs": 3100 }
  ],
  "carPath": [
    { "timeMs": 0, "x": 120, "y": 600 },
    { "timeMs": 100, "x": 135, "y": 580 }
  ]
}
```

---

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **Surface encoder input** (EGL/GLES20) | Eliminates stride/format mismatch corruption across all Android hardware encoders |
| **Direct ByteBuffer YUV decode** | Hardware `getOutputImage()` returns null on many devices; raw buffer fallback is universal |
| **Monotonic timestamp enforcement** | Non-monotonic PTS corrupts the native MP4 muxer (`IllegalStateException`) |
| **try-finally resource cleanup** | Prevents "Failed to stop the muxer" errors from leaked `MediaCodec`/`MediaMuxer` instances |
| **Instant fall detection** (no multi-frame confirm) | Matches notebook reference implementation; avoids delayed/missed fall events |
| **Unquantized model** | Better detection accuracy for the small toy bowling objects vs. quantized variant |

---

## Course Context

This project was developed for **DSAI 352** (Bonus Project) at Zewail City. The task requires building a mobile application that:

- Captures a live demonstration video of an RC car knocking down bowling pins
- Processes the video **entirely on-device** (no cloud/server inference)
- Uses a **custom-trained** or fine-tuned object detection model
- Detects and orders pin falls
- Optionally tracks the car's trajectory
- Produces an annotated output video with scoring overlays
