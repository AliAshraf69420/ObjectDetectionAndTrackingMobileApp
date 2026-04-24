# Object Tracker Mobile App

Real-time object tracker: a Python FastAPI server runs YOLOv8 detection and a React Native (Expo) mobile client captures camera frames, ships them to the server, and overlays bounding boxes on the live preview.

## Architecture

```
┌──────────────────────────────────────┐        Wi-Fi / LAN
│  Phone (Expo Go)                     │ ──────────────────────►  FastAPI server
│  • Captures JPEG frames via camera   │  POST /track-frame         (Python)
│  • Overlays bounding boxes on screen │ ◄──────────────────────  YOLOv8 inference
└──────────────────────────────────────┘        JSON boxes
```

## Repository layout

```
server/   Python FastAPI backend (YOLOv8 detection)
client/   Expo / React Native frontend (file-based routing via expo-router)
```

## How to run

Both the server and client must be running at the same time, and both your development machine and phone must be on the **same Wi-Fi network**.

---

### 1. Server

#### Prerequisites

- Python 3.11 or 3.12 (PyTorch wheels are not yet available for Python 3.13+)

#### Install dependencies

```bash
cd server
python3.12 -m venv .venv
source .venv/bin/activate

pip install -r requirements.txt          # FastAPI, OpenCV, etc.
pip install -r requirements-yolo.txt     # YOLOv8 via ultralytics
```

#### Configure environment

```bash
cp .env.example .env
```

Edit `server/.env` if needed. Key variables:

| Variable | Default | Description |
|---|---|---|
| `DETECTION_BACKEND` | `yolo` | Set to `none` to disable detection |
| `TARGET_CLASS` | `bottle` | COCO class name to track |
| `YOLO_MODEL` | `yolov8n.pt` | Model file (auto-downloaded on first run) |
| `YOLO_CONF` | `0.35` | Minimum detection confidence |
| `YOLO_DEVICE` | `cpu` | `cpu` or `0` for CUDA GPU 0 |

#### Start the server

```bash
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

`--host 0.0.0.0` is required so that your phone on the same Wi-Fi can reach the server. On first run, `yolov8n.pt` (~6 MB) is downloaded automatically.

Find your machine's LAN IP address — you will need it in the next step:

```bash
# Linux
ip route get 1 | awk '{print $7; exit}'

# macOS
ipconfig getifaddr en0
```

---

### 2. Client

#### Prerequisites

- Node.js 18+
- **Expo Go** installed on your iOS device  
  → Download from the [App Store](https://apps.apple.com/app/expo-go/id982107779)

#### Install dependencies

```bash
cd client
npm install
```

#### Configure environment

```bash
cp .env.example .env
```

Edit `client/.env` and set the server URL to your machine's LAN IP from the previous step:

```
EXPO_PUBLIC_SERVER_URL=http://192.168.x.x:8000
```

#### Start the Expo dev server

```bash
npm start
```

This prints a QR code in the terminal.

#### Connect your iPhone

1. Open the **Camera** app on your iPhone (or open **Expo Go** directly).
2. Point the camera at the QR code shown in the terminal.
3. Tap the banner that appears — this opens the app in **Expo Go**.
4. Grant camera permission when prompted.
5. Tap **Start Tracking** and point the camera at a bottle (or whichever `TARGET_CLASS` you configured).

---

## API reference

| Endpoint | Method | Description |
|---|---|---|
| `/health` | GET | Server status, backend name, model info |
| `/track-frame` | POST | Multipart `file` field (JPEG); returns `{ width, height, boxes: [{ x, y, width, height, label, confidence }] }` |
