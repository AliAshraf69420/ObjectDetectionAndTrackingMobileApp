# iOS Integration Plan
---

## 1. Motivation

Although the Android layer satisfies all project requirements, iOS support would:

- **Expand the user base** to iOS devices, which represent a large share of the mobile market.
- **Leverage Apple hardware acceleration** — Neural Engine and GPU cores on A12+ / M-series chips for significantly faster inference.
- **Demonstrate true cross-platform capability** of the React Native / Expo architecture.

---

## 2. Architecture Overview — Platform Equivalences

| Component         | Android (Current)               | iOS (Planned)                                   |
| ----------------- | ------------------------------- | ----------------------------------------------- |
| Native Language   | Kotlin                          | Swift                                           |
| Bridge            | `ReactContextBaseJavaModule`    | `RCTBridgeModule` or **Expo Modules Swift API** |
| Video Decode      | `MediaCodec` + `MediaExtractor` | `AVAssetReader` + `AVAssetReaderTrackOutput`    |
| Video Encode      | `MediaCodec` + `MediaMuxer`     | `AVAssetWriter` + `AVAssetWriterInput`          |
| Encoder Surface   | EGL + OpenGL ES 2.0 surface     | `AVAssetWriterInputPixelBufferAdaptor`          |
| Inference Runtime | TensorFlow Lite Android         | **Core ML** (`.mlmodel`) or TensorFlow Lite iOS |
| Frame Drawing     | Android `Canvas` / `Bitmap`     | Core Graphics (`CGContext`) / `UIImage`         |
| Gallery Save      | `MediaStore` content provider   | `PHPhotoLibrary`                                |

---

## 3. Model Conversion for iOS

Two inference runtime options:

### Option A — Core ML *(recommended)*

Convert the trained YOLOv8n model to `.mlmodel` format using `coremltools`:

```
YOLOv8n (PyTorch) → ONNX → Core ML (.mlmodel)
```

Ultralytics provides a one-step command:

```bash
yolo export model=best.pt format=coreml
# or in Python:
# model.export(format="coreml")
```

> [!TIP]
> Core ML automatically leverages the **Apple Neural Engine (ANE)** on A12+ and M-series chips, yielding significantly faster inference than CPU-only execution.

### Option B — TensorFlow Lite iOS

Use the same `.tflite` model as Android with the TFLite Swift/Obj-C runtime.

- **Pro:** Maximum model parity with the Android build.
- **Con:** Forfeits ANE acceleration (TFLite iOS supports CPU and GPU delegate only, not the Neural Engine).

> [!IMPORTANT]
> **Core ML is the recommended path** because it provides hardware acceleration with zero additional configuration and integrates natively with the Apple toolchain.

---

## 4. Video Processing Pipeline on iOS

The iOS module (`VideoProcessorIOS.swift`) mirrors the Android decode–infer–annotate–encode loop:

### 4.1 Decode

- Use `AVAssetReader` with `kCVPixelFormatType_32BGRA` output setting.
- Frames arrive as `CVPixelBuffer` objects with well-defined strides.
- **No YUV conversion fallback needed** — unlike Android, iOS decoders produce consistent pixel buffers.

### 4.2 Infer

- Resize each `CVPixelBuffer` to 640 × 640 (via `vImage` or Core Graphics).
- Feed to the Core ML model using either:
  - `VNCoreMLRequest` (Vision framework integration), or
  - Direct `MLModel.prediction()` calls.

### 4.3 Track

- Re-implement `PinTracker` in Swift — same IoU + centre-distance matching algorithm.
- The logic is purely arithmetic and translates directly from Kotlin.

**Tracking Parameters (unchanged):**

| Parameter | Value |
|---|---|
| Match distance threshold | 70 px |
| Match IoU threshold | 0.20 |
| Match score | `IoU - (dist / 1000)` |
| Fall detection | Instant on standing → fallen transition |
| Track pruning | Remove after >20 missing frames |

### 4.4 Annotate

- Use Core Graphics (`CGContext`) to draw:
  - Colour-coded bounding boxes
  - Pin IDs and fall-order labels
  - Car path line (purple with black shadow)
  - Standing/fallen pin count panel (top-left)
  - Fall history timeline panel (top-right)

### 4.5 Encode

- `AVAssetWriter` with H.264 `AVAssetWriterInput`.
- `AVAssetWriterInputPixelBufferAdaptor` manages the pixel buffer pool and presentation timestamps.
- **No manual EGL/OpenGL setup needed** — Apple's API handles surface management internally.

---

## 5. React Native Bridge on iOS

The JavaScript API remains **identical** on both platforms:

```javascript
// Same JS call, different native implementation
const result = await ProcessingModule.processVideo(uri, true);
```

Expo Modules Swift bridge definition:

```swift
import ExpoModulesCore

public class ProcessingModule: Module {
  public func definition() -> ModuleDefinition {
    Name("ProcessingModule")

    AsyncFunction("processVideo") {
      (uri: String, enableCarPath: Bool) -> [String: Any] in
      return try await VideoProcessorIOS.process(
        inputUri: uri, enableCarPath: enableCarPath
      )
    }

    Events("ProcessingProgress")
  }
}
```

> [!NOTE]
> Because the React Native / TypeScript layer is already platform-agnostic, **no changes to the UI code are required** — only the native module registration.

---

## 6. Key Differences & Challenges

| # | Topic | Details |
|---|---|---|
| 1 | **Development environment** | iOS builds require **macOS + Xcode**. Current dev is on Arch Linux. Needs a macOS machine or CI service (e.g., EAS Build cloud). |
| 2 | **Pixel format handling** | iOS decoders output BGRA pixel buffers with consistent strides → eliminates the YUV conversion and stride mismatch issues from Android. Simplifies the decode stage significantly. |
| 3 | **Neural Engine acceleration** | Core ML on A12+ chips can run YOLOv8n on the 16-core Neural Engine, potentially achieving **sub-10 ms per-frame inference** — much faster than CPU-only TFLite on Android. |
| 4 | **Video encoding** | `AVAssetWriter` is a higher-level API than `MediaCodec`/`MediaMuxer`. No manual EGL surface management or monotonic timestamp enforcement needed. |
| 5 | **Privacy & permissions** | Requires explicit `NSCameraUsageDescription` and `NSPhotoLibraryAddUsageDescription` in `Info.plist`, plus runtime permission prompts via the Expo permissions API. |

---

## 7. Implementation Roadmap

The iOS layer should be implemented in the following order:

| Phase | Task | Status |
|---|---|---|
| **1** | Convert YOLOv8n model to Core ML format and validate inference output parity with the TFLite model. | ⬜ Not started |
| **2** | Implement Swift `VideoProcessorIOS` with decode–encode loop (without inference) to verify video I/O. | ⬜ Not started |
| **3** | Integrate Core ML inference and validate detection outputs on sample frames. | ⬜ Not started |
| **4** | Port `PinTracker` to Swift and validate fall-order assignment. | ⬜ Not started |
| **5** | Implement the annotation renderer using Core Graphics. | ⬜ Not started |
| **6** | Wire up the Expo Module bridge and test end-to-end from the React Native UI. | ⬜ Not started |
| **7** | Device testing on multiple iPhone models (iPhone 12+ recommended for Neural Engine perf). | ⬜ Not started |

---

## 8. File Structure (Planned)

```
app/
├── android/          # ← existing Kotlin native module
│   └── ...
├── ios/              # ← new iOS native module
│   ├── ProcessingModule.swift        # Expo Modules bridge
│   ├── VideoProcessorIOS.swift       # Decode–infer–annotate–encode loop
│   ├── CoreMLDetector.swift          # Core ML inference wrapper
│   ├── PinTrackerIOS.swift           # Pin tracking (Swift port)
│   └── Resources/
│       └── best.mlmodel              # Core ML model file
└── src/              # ← shared TypeScript UI (no changes needed)
    └── ...
```
