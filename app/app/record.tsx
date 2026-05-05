import React, { useRef, useCallback } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  Alert,
} from 'react-native';
import { CameraView, useCameraPermissions } from 'expo-camera';
import * as ImagePicker from 'expo-image-picker';
import { router } from 'expo-router';
import { useAppStore } from '../src/store/AppContext';
import { useRecordingTimer } from '../src/hooks/useRecordingTimer';
import { colors, spacing, font, radius } from '../src/components/theme';

export default function RecordScreen() {
  const { appState, dispatch } = useAppStore();
  const [cameraPermission, requestCameraPermission] = useCameraPermissions();
  const cameraRef = useRef<CameraView>(null);
  const isRecording = appState === 'recording';
  const timer = useRecordingTimer();

  const startRecording = useCallback(async () => {
    if (!cameraRef.current) return;
    dispatch({ type: 'START_RECORDING' });
    timer.start();
    try {
      const video = await cameraRef.current.recordAsync({ maxDuration: 60, mute: true });
      if (video?.uri) {
        timer.stop();
        dispatch({ type: 'STOP_RECORDING', uri: video.uri });
        router.push('/processing');
      }
    } catch (e) {
      timer.stop();
      dispatch({ type: 'RESET' });
      Alert.alert('Recording error', String(e));
    }
  }, [dispatch, timer]);

  const stopRecording = useCallback(() => {
    cameraRef.current?.stopRecording();
  }, []);

  const pickFromGallery = useCallback(async () => {
    const { status } = await ImagePicker.requestMediaLibraryPermissionsAsync();
    if (status !== 'granted') {
      Alert.alert('Permission required', 'Gallery access is needed to pick a video.');
      return;
    }
    const result = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ['videos'],
      allowsEditing: false,
      quality: 1,
    });
    if (!result.canceled && result.assets[0]?.uri) {
      dispatch({ type: 'STOP_RECORDING', uri: result.assets[0].uri });
      router.push('/processing');
    }
  }, [dispatch]);

  if (!cameraPermission) {
    return <View style={styles.center}><Text style={styles.textSec}>Requesting permissions…</Text></View>;
  }

  if (!cameraPermission.granted) {
    return (
      <View style={styles.center}>
        <Text style={styles.textSec}>Camera permission is required.</Text>
        <TouchableOpacity style={styles.btnPrimary} onPress={requestCameraPermission}>
          <Text style={styles.btnText}>Grant Permission</Text>
        </TouchableOpacity>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <CameraView
        ref={cameraRef}
        style={styles.camera}
        facing="back"
        mode="video"
        videoQuality="1080p"
      />

      {!isRecording && (
        <View style={styles.instructionBox}>
          <Text style={styles.instructionTitle}>RC Bowling Tracker</Text>
          <Text style={styles.instructionText}>
            Aim camera at the bowling lane, then tap record.
          </Text>
        </View>
      )}

      {isRecording && (
        <View style={styles.timerBox}>
          <View style={styles.recDot} />
          <Text style={styles.timerText}>{timer.formatted}</Text>
        </View>
      )}

      <View style={styles.controlsRow}>
        <TouchableOpacity style={styles.btnIcon} onPress={() => router.push('/debug')}>
          <Text style={styles.btnIconText}>⚙</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={[styles.recordBtn, isRecording && styles.recordBtnActive]}
          onPress={isRecording ? stopRecording : startRecording}
          activeOpacity={0.8}
        >
          <View style={[styles.recordInner, isRecording && styles.recordInnerStop]} />
        </TouchableOpacity>

        <TouchableOpacity
          style={[styles.btnIcon, isRecording && styles.btnIconDisabled]}
          onPress={pickFromGallery}
          disabled={isRecording}
        >
          <Text style={styles.btnIconText}>🖼</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#000' },
  camera: { flex: 1 },
  center: { flex: 1, backgroundColor: colors.bg, alignItems: 'center', justifyContent: 'center', padding: spacing.xl },
  textSec: { color: colors.textSecondary, fontSize: font.base, marginBottom: spacing.md, textAlign: 'center' },

  instructionBox: {
    position: 'absolute',
    top: spacing.xl,
    left: spacing.lg,
    right: spacing.lg,
    backgroundColor: colors.overlay,
    borderRadius: radius.md,
    padding: spacing.md,
    alignItems: 'center',
  },
  instructionTitle: {
    color: colors.textPrimary,
    fontSize: font.lg,
    fontWeight: '700',
    marginBottom: spacing.xs,
  },
  instructionText: {
    color: colors.textSecondary,
    fontSize: font.md,
    textAlign: 'center',
  },

  timerBox: {
    position: 'absolute',
    top: spacing.xl,
    alignSelf: 'center',
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    backgroundColor: colors.overlay,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    borderRadius: radius.full,
  },
  recDot: { width: 10, height: 10, borderRadius: 5, backgroundColor: colors.error },
  timerText: { color: colors.textPrimary, fontSize: font.lg, fontWeight: '700', letterSpacing: 2 },

  controlsRow: {
    position: 'absolute',
    bottom: spacing.xxl,
    left: 0,
    right: 0,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: spacing.xl,
  },

  recordBtn: {
    width: 80,
    height: 80,
    borderRadius: 40,
    borderWidth: 4,
    borderColor: colors.textPrimary,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'transparent',
  },
  recordBtnActive: { borderColor: colors.error },
  recordInner: {
    width: 60,
    height: 60,
    borderRadius: 30,
    backgroundColor: colors.error,
  },
  recordInnerStop: {
    width: 28,
    height: 28,
    borderRadius: 6,
    backgroundColor: colors.error,
  },

  btnIcon: {
    width: 48,
    height: 48,
    borderRadius: 24,
    backgroundColor: colors.overlay,
    alignItems: 'center',
    justifyContent: 'center',
  },
  btnIconDisabled: { opacity: 0.3 },
  btnIconText: { color: colors.textPrimary, fontSize: font.xl },

  btnPrimary: {
    backgroundColor: colors.primary,
    paddingHorizontal: spacing.xl,
    paddingVertical: spacing.md,
    borderRadius: radius.full,
    marginTop: spacing.md,
  },
  btnText: { color: '#fff', fontSize: font.base, fontWeight: '700' },
});
