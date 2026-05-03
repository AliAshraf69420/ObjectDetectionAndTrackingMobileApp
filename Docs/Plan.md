# Step-by-Step Project Plan

## Milestone 1 — Confirm the minimum viable pipeline

Goal:

Build the smallest version that satisfies the core project requirement.

Minimum target:

```text
Record video → process locally → detect fallen pins → assign fall order → render annotated output video → show summary
```

Do not start with the optional car-path feature. Add it only after the pin-order pipeline works.

## Milestone 2 — Create the Expo development-build project

Tasks:

- Create the React Native Expo project.
- Configure TypeScript.
- Set up EAS development builds.
- Generate or maintain the Android native project through Expo prebuild.
- Test the app on a real Android phone.
- Confirm that custom native modules can be included.

Expected output:

- App opens on the phone.
- You can make a development build.
- You are no longer relying on Expo Go.

## Milestone 3 — Build the recording UI

Tasks:

- Add camera permissions.
- Add video recording screen.
- Save captured video locally.
- Display the recorded video in the app.
- Add basic run metadata: start time, end time, duration.

Expected output:

- You can record a toy-car bowling run inside the app.
- The raw video is saved locally and playable.

## Milestone 4 — Create the native processing module skeleton

Tasks:

- Create a Kotlin native module.
- Expose `processVideo(inputVideoUri, options)` to React Native.
- Return fake/dummy result data first.
- Add background-thread execution.
- Add error handling.

Expected output:

- React Native can call native Android code.
- The native module can return an output object to JS.

## Milestone 5 — Implement video decoding and frame extraction

Tasks:

- Decode the recorded video in the native layer.
- Extract frames at a fixed processing FPS.
- Convert frames to bitmap/matrix format.
- Log frame dimensions, FPS, and duration.

Expected output:

- Native code can read the video frame by frame.
- Processing does not crash on real phone videos.

## Milestone 6 — Build a temporary classical-CV prototype

Purpose:

Create an early test pipeline before the ML model is ready.

Tasks:

- Use OpenCV or basic image processing to test frame annotation.
- Draw dummy labels on frames.
- Encode an annotated video.
- Return the annotated video URI to React Native.

Expected output:

- The app can produce a new output video from an input video.
- Rendering and encoding are proven before ML integration.

## Milestone 7 — Collect and label the dataset

Tasks:

- Record toy-car bowling videos using the same phone or a similar camera.
- Extract frames from videos.
- Label pins and car.
- Include different lighting conditions, camera angles, pin layouts, and car positions.
- Label at least these classes:
  - `standing_pin`
  - `fallen_pin`
  - `car`

Recommended labeling tools:

- Roboflow
- CVAT
- LabelImg / Label Studio

Expected output:

- A labeled object-detection dataset in YOLO format.

## Milestone 8 — Fine-tune the detector

Recommended model:

- YOLOv8n or YOLO11n object detector.

Tasks:

- Train/fine-tune on the labeled dataset.
- Validate detection quality on held-out videos.
- Check if the model distinguishes standing pins from fallen pins.
- Check if the car is detected reliably.

Expected output:

- A trained detector that can identify pins, fallen pins, and car on test frames.

## Milestone 9 — Export the model to mobile format

Tasks:

- Export the model to TensorFlow Lite.
- Prefer float16 quantization first.
- Test int8 quantization only if needed for speed.
- Verify that the exported model produces correct outputs.
- Place the model in Android assets.

Expected output:

- `pin_detector_float16.tflite` bundled with the app.

## Milestone 10 — Integrate on-device inference

Tasks:

- Load the TFLite model from Android assets.
- Preprocess frames to match the model input.
- Run inference per sampled frame.
- Decode detections.
- Apply confidence filtering and NMS if required.

Expected output:

- The app can detect standing pins, fallen pins, and car in recorded video frames fully offline.

## Milestone 11 — Implement pin tracking

Tasks:

- Assign stable track IDs to pins across frames.
- Match pins by location and IoU.
- Maintain per-pin state history.
- Smooth noisy detections.

Expected output:

- The same physical pin keeps the same track ID across the video.

## Milestone 12 — Implement fall detection and hit ordering

Tasks:

- Detect transition from standing to fallen.
- Require fallen state to persist for multiple frames.
- Assign the first stable fallen timestamp.
- Sort pins by fall timestamp.
- Assign labels `1`, `2`, `3`, etc.

Expected output:

- The app knows which pin fell first, second, third, and so on.

## Milestone 13 — Render the annotated output video

Tasks:

- Draw labels on fallen pins.
- Draw optional boxes or markers.
- Draw labels at the correct pin locations.
- Encode the final annotated video.
- Return the final video URI and JSON metadata to React Native.

Expected output:

- The result screen plays the annotated video.
- The labels correspond to fall order.

## Milestone 14 — Add final score summary

Tasks:

- Display total elapsed time.
- Display total number of knocked-down pins.
- Display optional pin event table.

Expected output:

- The app satisfies the base functional requirements.

## Milestone 15 — Add optional car-path detection

Only start this after the core pipeline works.

Tasks:

- Track the car center across frames.
- Smooth the trajectory.
- Remove jumps caused by missed detections.
- Draw the trajectory as a line on the output video.

Expected output:

- The output video shows the path taken by the toy car.

## Milestone 16 — Optimize for real-device performance

Tasks:

- Reduce model input size if needed.
- Tune frame sampling FPS.
- Use float16 model.
- Try TFLite GPU delegate if stable.
- Keep CPU fallback.
- Avoid processing on the UI thread.
- Clean temporary files.

Expected output:

- The app can process a short live-recorded run on the real Android phone without crashing.

## Milestone 17 — Prepare discussion defense material

Tasks:

- Prepare a short explanation of the full pipeline.
- Keep training notebooks/scripts available.
- Keep dataset examples available.
- Keep model export logs available.
- Be ready to explain preprocessing, model choice, fall detection, tracking, and limitations.

Expected output:

- You can defend that the app is your own work and that inference runs on-device.

## Recommended implementation order

```text
1. Expo dev build works on Android
2. Camera recording works
3. Native module bridge works
4. Video decoding works
5. Annotated video writing works
6. Dataset collection and labeling
7. Model training
8. TFLite export
9. On-device inference
10. Pin tracking
11. Fall ordering
12. Result UI
13. Optional car path
14. Optimization and defense preparation
```
