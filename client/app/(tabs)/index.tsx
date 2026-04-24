import { Image } from 'expo-image';
import { CameraView, useCameraPermissions } from 'expo-camera';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  StyleSheet,
  View,
  type LayoutChangeEvent,
} from 'react-native';

import { ThemedText } from '@/components/themed-text';
import { ThemedView } from '@/components/themed-view';
import { SERVER_URL } from '@/constants/server';
import { useThemeColor } from '@/hooks/use-theme-color';

const MAX_PATH_LEN = 30;
const DOT_RADIUS = 4;

const TRACK_PALETTE = [
  '#FF4444', '#44AAFF', '#44FF88', '#FFAA00',
  '#FF44FF', '#00FFEE', '#FF8844', '#AAFF00',
];

function trackColor(id: number): string {
  return TRACK_PALETTE[id % TRACK_PALETTE.length];
}

type Box = {
  x: number;
  y: number;
  width: number;
  height: number;
  label?: string;
  confidence?: number;
  track_id?: number;
};

type TrackResponse = {
  width: number;
  height: number;
  boxes: Box[];
};

type PathPoint = { cx: number; cy: number };

function hexToRgb(hex: string): { r: number; g: number; b: number } | null {
  const raw = hex.replace('#', '').trim();
  if (raw.length === 3) {
    const r = parseInt(raw[0] + raw[0], 16);
    const g = parseInt(raw[1] + raw[1], 16);
    const b = parseInt(raw[2] + raw[2], 16);
    return { r, g, b };
  }
  if (raw.length === 6) {
    const r = parseInt(raw.slice(0, 2), 16);
    const g = parseInt(raw.slice(2, 4), 16);
    const b = parseInt(raw.slice(4, 6), 16);
    return { r, g, b };
  }
  return null;
}

function withAlpha(hex: string, alpha: number): string {
  const rgb = hexToRgb(hex);
  if (!rgb) return hex;
  return `rgba(${rgb.r}, ${rgb.g}, ${rgb.b}, ${alpha})`;
}

export default function HomeScreen() {
  const tint = useThemeColor({}, 'tint');
  const text = useThemeColor({}, 'text');
  const background = useThemeColor({}, 'background');

  const [permission, requestPermission] = useCameraPermissions();
  const cameraRef = useRef<CameraView>(null);

  const [isTracking, setIsTracking] = useState(false);
  const [isSending, setIsSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastLatencyMs, setLastLatencyMs] = useState<number | null>(null);

  const [lastFrameUri, setLastFrameUri] = useState<string | null>(null);
  const [lastFrameSize, setLastFrameSize] = useState<{ width: number; height: number } | null>(null);
  const [track, setTrack] = useState<TrackResponse | null>(null);

  const [renderSize, setRenderSize] = useState<{ width: number; height: number } | null>(null);

  // Path history stored in server pixel coords so it survives renderSize changes.
  const [pathHistory, setPathHistory] = useState<Map<number, PathPoint[]>>(new Map());

  const trackEndpoint = useMemo(() => `${SERVER_URL}/track-frame`, []);

  // Clear path history when tracking stops.
  useEffect(() => {
    if (!isTracking) setPathHistory(new Map());
  }, [isTracking]);

  useEffect(() => {
    if (!isTracking) return;

    let cancelled = false;

    const loop = async () => {
      while (!cancelled) {
        if (isSending) {
          await new Promise((r) => setTimeout(r, 50));
          continue;
        }

        try {
          setIsSending(true);
          setError(null);

          const picture = await cameraRef.current?.takePictureAsync({
            quality: 0.25,
            skipProcessing: true,
          });

          if (!picture?.uri) {
            throw new Error('Camera capture failed');
          }

          setLastFrameUri(picture.uri);
          if (typeof picture.width === 'number' && typeof picture.height === 'number') {
            setLastFrameSize({ width: picture.width, height: picture.height });
          }

          const form = new FormData();
          form.append(
            'file',
            {
              uri: picture.uri,
              name: 'frame.jpg',
              type: 'image/jpeg',
            } as unknown as Blob
          );

          const start = Date.now();
          const response = await fetch(trackEndpoint, {
            method: 'POST',
            body: form,
          });
          const latency = Date.now() - start;
          setLastLatencyMs(latency);

          if (!response.ok) {
            const maybeText = await response.text().catch(() => '');
            throw new Error(maybeText || `Server error: ${response.status}`);
          }

          const json = (await response.json()) as TrackResponse;
          setTrack(json);

          // Update path history: append center points, trim stale tracks.
          setPathHistory((prev) => {
            const next = new Map(prev);
            const activeIds = new Set(json.boxes.map((b) => b.track_id ?? -1));
            for (const id of next.keys()) {
              if (!activeIds.has(id)) next.delete(id);
            }
            for (const b of json.boxes) {
              const id = b.track_id ?? -1;
              const cx = b.x + b.width / 2;
              const cy = b.y + b.height / 2;
              const pts = next.get(id) ?? [];
              next.set(id, [...pts, { cx, cy }].slice(-MAX_PATH_LEN));
            }
            return next;
          });
        } catch (e: unknown) {
          setError(e instanceof Error ? e.message : 'Unknown error');
        } finally {
          setIsSending(false);
        }

        await new Promise((r) => setTimeout(r, 20));
      }
    };

    void loop();
    return () => {
      cancelled = true;
    };
  }, [isTracking, isSending, trackEndpoint]);

  const onRenderLayout = (e: LayoutChangeEvent) => {
    const { width, height } = e.nativeEvent.layout;
    setRenderSize({ width, height });
  };

  const { scale, offsetX, offsetY } = useMemo(() => {
    if (!track || !renderSize) return { scale: 1, offsetX: 0, offsetY: 0 };
    const s = Math.min(renderSize.width / track.width, renderSize.height / track.height);
    return {
      scale: s,
      offsetX: (renderSize.width - track.width * s) / 2,
      offsetY: (renderSize.height - track.height * s) / 2,
    };
  }, [track, renderSize]);

  const overlayBoxes = useMemo(() => {
    if (!track || !renderSize) return [];
    return track.boxes.map((b) => {
      const id = b.track_id ?? 0;
      const color = trackColor(id);
      return {
        key: String(id),
        left: offsetX + b.x * scale,
        top: offsetY + b.y * scale,
        width: b.width * scale,
        height: b.height * scale,
        label: b.label,
        confidence: b.confidence,
        color,
        id,
      };
    });
  }, [track, renderSize, scale, offsetX, offsetY]);

  const pathDots = useMemo(() => {
    if (!track || !renderSize || pathHistory.size === 0) return [];
    const dots: { key: string; x: number; y: number; color: string; alpha: number; size: number }[] = [];
    for (const [id, pts] of pathHistory.entries()) {
      const color = trackColor(id);
      const rgb = hexToRgb(color);
      if (!rgb) continue;
      pts.forEach((pt, i) => {
        const progress = (i + 1) / pts.length;
        dots.push({
          key: `${id}-${i}`,
          x: offsetX + pt.cx * scale,
          y: offsetY + pt.cy * scale,
          color: `rgba(${rgb.r}, ${rgb.g}, ${rgb.b}, ${progress * 0.85})`,
          alpha: progress,
          size: DOT_RADIUS * (0.4 + 0.6 * progress) * 2,
        });
      });
    }
    return dots;
  }, [pathHistory, track, renderSize, scale, offsetX, offsetY]);

  if (!permission) {
    return <ThemedView style={styles.fill} />;
  }

  if (!permission.granted) {
    return (
      <ThemedView style={[styles.fill, styles.center, { padding: 16 }]}>
        <ThemedText type="title">Camera permission</ThemedText>
        <ThemedText style={{ marginTop: 8 }}>
          This app needs camera access to capture frames for tracking.
        </ThemedText>
        <Pressable
          onPress={() => requestPermission()}
          style={[styles.button, { borderColor: tint, backgroundColor: withAlpha(tint, 0.12) }]}>
          <ThemedText type="defaultSemiBold">Grant permission</ThemedText>
        </Pressable>
      </ThemedView>
    );
  }

  return (
    <ThemedView style={styles.fill}>
      <View style={styles.preview} onLayout={onRenderLayout}>
        <CameraView ref={cameraRef} style={StyleSheet.absoluteFill} facing="back" />

        {lastFrameUri ? (
          <Image
            source={{ uri: lastFrameUri }}
            style={StyleSheet.absoluteFill}
            contentFit="contain"
          />
        ) : null}

        {/* Path trail dots */}
        {pathDots.map((d) => (
          <View
            key={d.key}
            style={[
              styles.dot,
              {
                left: d.x - d.size / 2,
                top: d.y - d.size / 2,
                width: d.size,
                height: d.size,
                borderRadius: d.size / 2,
                backgroundColor: d.color,
              },
            ]}
          />
        ))}

        {/* Bounding boxes */}
        {overlayBoxes.map((b) => (
          <View
            key={b.key}
            style={[
              styles.box,
              {
                borderColor: b.color,
                left: b.left,
                top: b.top,
                width: b.width,
                height: b.height,
              },
            ]}>
            {b.label ? (
              <View style={[styles.boxLabel, { backgroundColor: withAlpha(b.color, 0.5) }]}>
                <ThemedText style={{ fontSize: 11, color: text }}>
                  {b.label} #{b.id}
                  {typeof b.confidence === 'number' ? ` ${(b.confidence * 100).toFixed(0)}%` : ''}
                </ThemedText>
              </View>
            ) : null}
          </View>
        ))}

        <View style={[styles.statusPanel, { backgroundColor: withAlpha(background, 0.75) }]}>
          <ThemedText style={{ fontSize: 12 }} numberOfLines={1}>
            {SERVER_URL}
          </ThemedText>
          <ThemedText style={{ fontSize: 12 }}>
            {isTracking ? 'Tracking: ON' : 'Tracking: OFF'}
            {typeof lastLatencyMs === 'number' ? ` · ${lastLatencyMs}ms` : ''}
            {track ? ` · ${track.boxes.length} obj` : ''}
          </ThemedText>
          {lastFrameSize ? (
            <ThemedText style={{ fontSize: 12 }}>
              Frame: {lastFrameSize.width}×{lastFrameSize.height}
            </ThemedText>
          ) : null}
          {error ? (
            <ThemedText style={{ fontSize: 12 }} numberOfLines={2}>
              Error: {error}
            </ThemedText>
          ) : null}
        </View>

        {isSending ? (
          <View style={styles.sending}>
            <ActivityIndicator />
          </View>
        ) : null}
      </View>

      <View style={styles.controls}>
        <Pressable
          onPress={() => setIsTracking((v) => !v)}
          style={[
            styles.button,
            {
              borderColor: tint,
              backgroundColor: isTracking ? withAlpha(tint, 0.22) : withAlpha(tint, 0.12),
            },
          ]}>
          <ThemedText type="defaultSemiBold">
            {isTracking ? 'Stop tracking' : 'Start tracking'}
          </ThemedText>
        </Pressable>
      </View>
    </ThemedView>
  );
}

const styles = StyleSheet.create({
  fill: {
    flex: 1,
  },
  center: {
    justifyContent: 'center',
    alignItems: 'center',
    gap: 12,
  },
  preview: {
    flex: 1,
    overflow: 'hidden',
  },
  statusPanel: {
    position: 'absolute',
    top: 12,
    left: 12,
    right: 12,
    padding: 10,
    borderRadius: 12,
    gap: 4,
  },
  controls: {
    padding: 12,
  },
  button: {
    borderWidth: 1,
    borderRadius: 14,
    paddingVertical: 12,
    paddingHorizontal: 16,
    alignItems: 'center',
  },
  sending: {
    position: 'absolute',
    bottom: 12,
    right: 12,
  },
  box: {
    position: 'absolute',
    borderWidth: 2,
    borderRadius: 6,
  },
  boxLabel: {
    position: 'absolute',
    left: 0,
    top: -22,
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 8,
  },
  dot: {
    position: 'absolute',
  },
});
