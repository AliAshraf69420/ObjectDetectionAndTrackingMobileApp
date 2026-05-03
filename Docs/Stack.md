# Project Stack

## Mobile app stack

### Core framework

- React Native
- Expo
- EAS Development Build
- TypeScript

Reason:

Expo with a custom development build lets you keep the React Native development experience while still adding native Android modules and ML/CV libraries. Expo Go is not enough because the project needs native inference and video processing.

### Target platform

- Android

Reason:

You are developing on Arch Linux, and Android development is much more practical from Linux than iOS development. Android also supports native ML runtimes and custom native modules without requiring macOS/Xcode.

### Navigation

Recommended:

- Expo Router

Alternative:

- React Navigation

## Camera and video stack

### Camera capture

Recommended:

- `expo-camera`

Alternative:

- `react-native-vision-camera`

Recommendation:

Start with `expo-camera` because it fits the Expo workflow. Consider `react-native-vision-camera` only if you need lower-level frame access later. For this project, it is acceptable to record video first, then process the saved video.

### Video playback

Recommended:

- `expo-video`

Alternative:

- `expo-av` if needed, but `expo-video` is the newer direction in Expo projects.

### File handling

Recommended:

- `expo-file-system`

Used for:

- Raw recorded video path
- Annotated output video path
- Result JSON path
- Temporary file cleanup

## Native Android stack

### Native language

Recommended:

- Kotlin

Reason:

Kotlin is the standard modern Android language and is suitable for native modules, threading, Android Media APIs, and ML runtime integration.

### Native module system

Recommended:

- Expo Modules API

Used for exposing native functions such as:

```ts
ProcessingModule.processVideo({ inputVideoUri, enableCarPath })
```

## Computer vision and inference stack

### Recommended inference runtime

Recommended:

- TensorFlow Lite Android

Reason:

TensorFlow Lite is mature for Android deployment, supports model files bundled in app assets, and has CPU/GPU delegate options.

Possible alternatives:

- ONNX Runtime Mobile
- NCNN
- PyTorch Mobile / ExecuTorch

Recommendation:

Use TensorFlow Lite unless you have a strong reason not to. It is easier to explain and package for Android.

### Recommended model

Recommended:

- Fine-tuned YOLOv8n or YOLO11n object detector exported to TensorFlow Lite

Classes:

```text
standing_pin
fallen_pin
car
```

Why this model:

- Small enough for mobile if using the nano version
- Easy to train using transfer learning
- Good object-detection tooling
- Can detect multiple pins and the car in the same frame
- Can be exported to TFLite
- Easier to defend than a completely hand-made pipeline

Do not use the model as a purely off-the-shelf detector. Fine-tune it on your own toy-car bowling dataset.

### Model export format

Recommended:

- TensorFlow Lite float16 model

Possible files:

```text
pin_detector_float16.tflite
labels.txt
model_config.json
```

Quantization recommendation:

- Start with float16 quantization.
- Use int8 only if speed or app size becomes a serious problem.
- Keep the unquantized or float16 model as a fallback during testing.

## Dataset recommendation

### Best dataset choice

Recommended:

- Build a custom dataset from your own toy-car bowling setup.

Reason:

The project domain is specific: small toy pins, toy car, phone camera angle, indoor lighting, and live demonstration constraints. A generic dataset will not match the task well enough.

### Dataset collection plan

Record multiple short videos with:

- Different lighting conditions
- Different camera distances
- Slightly different camera angles
- At least five pins
- Different pin layouts
- Different car colors/positions
- Runs where all pins fall
- Runs where only some pins fall
- Runs with occlusion
- Runs with motion blur

Extract frames from these videos and label them.

### Label classes

Recommended labels:

```text
standing_pin
fallen_pin
car
```

Optional extra labels:

```text
occluded_pin
uncertain_pin
```

But keep the first version simple. Too many classes may hurt model quality if the dataset is small.

### Annotation tools

Recommended:

- Roboflow
- CVAT
- Label Studio

Simplest path:

1. Extract frames from videos.
2. Upload frames to Roboflow or CVAT.
3. Label bounding boxes.
4. Export in YOLO format.
5. Train YOLOv8n / YOLO11n.
6. Export to TFLite.

## Training stack

Recommended:

- Python
- Ultralytics YOLO
- OpenCV
- Jupyter Notebook or Python scripts

Training environment:

- Arch Linux laptop if GPU/Python setup works
- Google Colab if local training is inconvenient

Example training flow:

```text
Collect videos
  ↓
Extract frames
  ↓
Label frames
  ↓
Train/fine-tune YOLO nano detector
  ↓
Validate on held-out videos
  ↓
Export to TFLite
  ↓
Bundle model into Android app
```

## Tracking stack

### Pin tracking

Recommended:

- Rule-based tracking using IoU and center-distance matching

Reason:

Pins are mostly stationary until they fall, so complex multi-object tracking is unnecessary at first.

Tracking logic:

- Detect pins per frame.
- Match detections to existing pin tracks.
- Maintain state history for each pin.
- Detect standing-to-fallen transition.

### Car path tracking

Recommended:

- Detection-based tracking from the `car` class
- Track center point of the car bounding box
- Smooth path using a moving average

Alternative:

- OpenCV optical flow / CSRT tracker after initial car detection

Recommendation:

Use detection-based tracking first. It is easier to implement and explain.

## Rendering stack

Recommended:

- Android Bitmap / Canvas drawing
- Android MediaCodec / MediaMuxer for video encoding

Alternative:

- OpenCV drawing + Android video encoding

Rendering overlays:

- Pin order labels
- Optional pin bounding boxes
- Car path line
- Optional elapsed timestamp

## App screens

Recommended screens:

```text
RecordScreen
ProcessingScreen
ResultScreen
SettingsDebugScreen
```

The debug screen is useful during development and can show:

- Model loaded or not
- Inference FPS
- Number of processed frames
- Detection thresholds
- Last error log

## Recommended repository structure

```text
RC-CarBowlingScoreDetection/
  app/
    src/
      screens/
      components/
      native/
      types/
      utils/
  android/
    app/src/main/assets/models/
    app/src/main/java/.../processing/
  ml/
    datasets/
    notebooks/
    scripts/
    training/
    exports/
  docs/
    bundlingPlan.md
    Layers.md
    Plan.md
    Stack.md
  README.md
```

## Final recommended stack summary

```text
Mobile UI: React Native + Expo + TypeScript
Build system: EAS Development Build
Platform: Android
Native code: Kotlin + Expo Modules API
Camera: expo-camera
Video playback: expo-video
File storage: expo-file-system
Inference runtime: TensorFlow Lite Android
Model: fine-tuned YOLOv8n or YOLO11n nano detector
Model format: TFLite float16
Classes: standing_pin, fallen_pin, car
Dataset: custom recorded and labeled toy-car bowling dataset
Annotation: Roboflow or CVAT
Training: Python + Ultralytics YOLO + OpenCV
Tracking: IoU/center-distance pin tracking; detection-based car tracking
Rendering: Android Canvas/OpenCV drawing + MediaCodec/MediaMuxer
```

## Main risks

### Risk 1: Model does not detect fallen pins reliably

Mitigation:

- Collect more examples of fallen pins.
- Use consistent camera angle.
- Add augmentation.
- Use explicit `standing_pin` and `fallen_pin` classes.

### Risk 2: Inference is too slow on the phone

Mitigation:

- Use YOLO nano model.
- Reduce input size.
- Process fewer frames per second.
- Use float16 quantization.
- Try GPU delegate.

### Risk 3: Pin order is unstable

Mitigation:

- Require fallen state for multiple consecutive frames.
- Smooth detections.
- Use timestamps from processed frames.
- Avoid too-low frame sampling rate.

### Risk 4: Car path is unreliable

Mitigation:

- Treat car path as optional.
- Use a visually distinct car if possible.
- Train the car class with many motion-blur examples.
- Smooth the path and ignore sudden impossible jumps.

## Recommended first version

Build this first:

```text
Android Expo dev build
+ video recording
+ native video decoding
+ TFLite YOLO detector
+ pin tracking
+ fall-order labels
+ annotated output video
+ elapsed time and knocked-down count
```

Then add:

```text
car detection
+ car center tracking
+ path rendering
```
