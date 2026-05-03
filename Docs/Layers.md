# Mobile App Layers

## Layer overview

The application is divided into layers so that UI, native execution, model inference, and result rendering remain separate and defensible.

```text
UI Layer
  ↓
Application Orchestration Layer
  ↓
React Native Bridge / Native Module Interface
  ↓
Native Android CV Layer
  ↓
Model Inference Layer
  ↓
Tracking and Scoring Layer
  ↓
Rendering and Storage Layer
```

## 1. UI Layer

Technology:

- React Native
- Expo development build
- TypeScript
- Expo Router or React Navigation

Responsibilities:

- Start a recording
- Stop/save the recording
- Show processing progress
- Display annotated output video
- Display final score summary
- Display errors clearly

Main screens:

```text
RecordScreen
  - Camera preview
  - Start/stop recording
  - Basic instructions

ProcessingScreen
  - Progress indicator
  - Current stage: decoding, inference, tracking, rendering

ResultScreen
  - Annotated video playback
  - Total elapsed time
  - Total pins knocked down
  - Optional event table: pin order and timestamp
```

The UI layer does not perform inference. It only passes the captured video URI to the native processing layer.

## 2. Application Orchestration Layer

Technology:

- TypeScript service modules
- React state / Zustand / simple context state

Responsibilities:

- Manage app states
- Validate that a video exists before processing
- Call the native module
- Store result metadata in JS state
- Route from recording to processing to result

Example states:

```text
idle
recording
video_saved
processing
processed
failed
```

Example TypeScript interface:

```ts
export type ProcessingResult = {
  outputVideoUri: string;
  elapsedMs: number;
  pinsKnockedDown: number;
  pinEvents: Array<{
    pinTrackId: number;
    order: number;
    timeMs: number;
  }>;
  carPath?: Array<{
    timeMs: number;
    x: number;
    y: number;
  }>;
};
```

## 3. React Native Bridge / Native Module Interface

Technology:

- Expo native module
- Kotlin module exposed to JavaScript

Responsibilities:

- Expose a stable JS API
- Accept an input video URI
- Return output video URI and metadata
- Emit progress updates if needed

Example JS-facing API:

```ts
const result = await ProcessingModule.processVideo({
  inputVideoUri,
  enableCarPath: true,
});
```

Native module responsibilities:

- Validate the input path
- Start video processing on a background thread
- Avoid blocking the UI thread
- Return structured result data

## 4. Native Android CV Layer

Technology:

- Kotlin or Java
- Android Media APIs
- Optional OpenCV Android

Responsibilities:

- Decode video into frames
- Control frame sampling rate
- Resize frames
- Convert color format
- Prepare tensors for inference
- Render overlays onto frames
- Encode the output video

Important components:

```text
VideoDecoder
FramePreprocessor
FrameSampler
AnnotatedVideoWriter
```

Frame sampling recommendation:

- Process every frame if performance allows.
- Otherwise process at a stable reduced FPS, such as 10–15 FPS, and interpolate/hold annotations between processed frames.
- For pin fall ordering, do not sample too sparsely, because hit order depends on timing.

## 5. Model Inference Layer

Recommended technology:

- TensorFlow Lite Android runtime
- TFLite GPU delegate if stable on the test phone
- CPU fallback required

Responsibilities:

- Load model from bundled assets
- Run object detection on frames
- Return bounding boxes, class IDs, and confidence scores

Recommended classes:

```text
standing_pin
fallen_pin
car
```

Alternative class setup:

```text
pin
car
```

Then classify pin state using geometry/rules:

- Standing pin: tall vertical bounding box
- Fallen pin: wider horizontal bounding box
- Transition: standing in earlier frames, fallen in later frames

The explicit class setup is recommended because it makes the scoring logic simpler and easier to explain.

## 6. Tracking Layer

Technology:

- Native Kotlin logic
- Optional OpenCV trackers
- Rule-based association

Responsibilities:

- Keep object IDs stable across frames
- Track each physical pin over time
- Track the car center over time
- Smooth noisy detections

Recommended tracking approach:

- Use detector outputs per frame.
- Match detections between frames using IoU and distance between bounding-box centers.
- Assign persistent `trackId`s.
- For pins, use relatively stable locations because pins start in fixed positions.
- For the car, use the center of the detected car box as the trajectory point.

Pin state timeline:

```text
pinTrackId = 2
frame 0: standing
frame 1: standing
frame 2: standing
frame 3: uncertain
frame 4: fallen
frame 5: fallen
```

A pin is counted as fallen only after the fallen state is stable for several frames. This avoids false positives.

## 7. Scoring Layer

Responsibilities:

- Detect first reliable falling moment per pin
- Assign hit order
- Count total fallen pins
- Compute elapsed time

Recommended fall event rule:

```text
A pin becomes fallen when:
1. It was previously standing, and
2. It is detected as fallen for N consecutive processed frames, and
3. Its confidence is above threshold, and
4. It has not already been assigned a fall order.
```

Recommended parameters:

```text
N = 2 or 3 consecutive processed frames
confidence threshold = 0.4 to 0.6
IoU matching threshold = 0.3 to 0.5
```

Hit-order assignment:

```text
Sort fall events by first stable fallen timestamp.
Assign order labels 1, 2, 3, ...
```

## 8. Rendering Layer

Technology:

- Android Canvas / Bitmap drawing
- OpenCV drawing functions
- MediaCodec / MediaMuxer for encoding

Responsibilities:

- Draw bounding boxes or markers around pins
- Draw numeric fall-order labels on fallen pins
- Draw car path if enabled
- Draw optional timestamp / summary overlays
- Encode the annotated frames into an output video

Output video overlays:

- Label `1`, `2`, `3`, etc. on pins in fall order
- Car trajectory line
- Optional frame timestamp
- Optional final summary frame or result screen metadata

## 9. Storage Layer

Technology:

- Expo FileSystem on JS side
- Android app files directory on native side

Responsibilities:

- Store raw recording
- Store output annotated video
- Store result JSON
- Clean temporary files

Recommended files:

```text
recordings/run_001_raw.mp4
outputs/run_001_annotated.mp4
outputs/run_001_result.json
```

## 10. Training / Conversion Layer

This layer is outside the mobile app but required for development.

Technology:

- Python
- Ultralytics YOLO
- OpenCV
- Roboflow or manual annotation tools
- TensorFlow Lite export pipeline

Responsibilities:

- Collect videos/images
- Label frames
- Train/fine-tune detector
- Validate model
- Export to mobile format
- Test converted model
- Copy model into Android assets

## Layer dependency rule

Each layer should depend only on the layer directly below it:

```text
UI depends on orchestration.
Orchestration depends on native module interface.
Native module depends on CV/inference/tracking/rendering internals.
Model inference does not depend on React Native.
Training pipeline does not run inside the app.
```

This separation makes the project easier to debug and easier to defend during the instructor discussion.
