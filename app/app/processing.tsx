import React, { useEffect, useRef } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ActivityIndicator,
  Animated,
  ScrollView,
} from 'react-native';
import { router } from 'expo-router';
import { useAppStore } from '../src/store/AppContext';
import { ProcessingModule } from '../src/native/ProcessingModule';
import { StageIndicator } from '../src/components/StageIndicator';
import { colors, spacing, font, radius } from '../src/components/theme';

export default function ProcessingScreen() {
  const { rawVideoUri, enableCarPath, processingProgress, appState, dispatch } = useAppStore();
  const progressAnim = useRef(new Animated.Value(0)).current;
  const hasStarted = useRef(false);

  useEffect(() => {
    if (hasStarted.current || !rawVideoUri) return;
    hasStarted.current = true;
    dispatch({ type: 'START_PROCESSING' });

    ProcessingModule.processVideo({
      inputVideoUri: rawVideoUri,
      enableCarPath,
      onProgress: progress => {
        dispatch({ type: 'UPDATE_PROGRESS', progress });
        Animated.timing(progressAnim, {
          toValue: progress.percent / 100,
          duration: 300,
          useNativeDriver: false,
        }).start();
      },
    })
      .then(result => {
        dispatch({ type: 'PROCESSING_DONE', result });
        router.replace('/result');
      })
      .catch(e => {
        dispatch({ type: 'PROCESSING_FAILED', error: String(e) });
        router.replace('/result');
      });
  }, [rawVideoUri]);

  const percent = processingProgress?.percent ?? 0;
  const message = processingProgress?.message ?? 'Starting pipeline…';
  const stage   = processingProgress?.stage;

  const barWidth = progressAnim.interpolate({ inputRange: [0, 1], outputRange: ['0%', '100%'] });

  return (
    <ScrollView
      style={styles.scroll}
      contentContainerStyle={styles.container}
      scrollEnabled={false}
    >
      <Text style={styles.title}>Processing Video</Text>
      <Text style={styles.subtitle}>Please keep the app open</Text>

      {/* Stage indicator */}
      {stage && (
        <View style={styles.stageWrapper}>
          <StageIndicator currentStage={stage} />
        </View>
      )}

      {/* Progress bar */}
      <View style={styles.progressTrack}>
        <Animated.View style={[styles.progressFill, { width: barWidth }]} />
      </View>
      <Text style={styles.percentText}>{percent}%</Text>

      {/* Message */}
      <View style={styles.messageBox}>
        <ActivityIndicator color={colors.primary} style={{ marginRight: spacing.sm }} />
        <Text style={styles.messageText}>{message}</Text>
      </View>

      {/* Info */}
      <View style={styles.infoCard}>
        <InfoRow label="Input video" value={rawVideoUri ? '…' + rawVideoUri.slice(-30) : '—'} />
        <InfoRow label="Car path" value={enableCarPath ? 'Enabled' : 'Disabled'} />
        <InfoRow label="Native module" value={ProcessingModule.isNativeAvailable() ? 'Hardware' : 'Stub / Demo'} accent={!ProcessingModule.isNativeAvailable()} />
      </View>
    </ScrollView>
  );
}

function InfoRow({ label, value, accent }: { label: string; value: string; accent?: boolean }) {
  return (
    <View style={infoStyles.row}>
      <Text style={infoStyles.label}>{label}</Text>
      <Text style={[infoStyles.value, accent && infoStyles.accent]}>{value}</Text>
    </View>
  );
}

const infoStyles = StyleSheet.create({
  row: { flexDirection: 'row', justifyContent: 'space-between', paddingVertical: spacing.sm },
  label: { color: colors.textSecondary, fontSize: font.md },
  value: { color: colors.textPrimary, fontSize: font.md, maxWidth: '60%', textAlign: 'right' },
  accent: { color: colors.warning },
});

const styles = StyleSheet.create({
  scroll: { flex: 1, backgroundColor: colors.bg },
  container: {
    flexGrow: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: spacing.xl,
    gap: spacing.lg,
  },
  title: {
    color: colors.textPrimary,
    fontSize: font.xxl,
    fontWeight: '700',
  },
  subtitle: {
    color: colors.textSecondary,
    fontSize: font.base,
  },
  stageWrapper: {
    width: '100%',
    paddingVertical: spacing.md,
  },
  progressTrack: {
    width: '100%',
    height: 8,
    backgroundColor: colors.surface,
    borderRadius: radius.full,
    overflow: 'hidden',
  },
  progressFill: {
    height: '100%',
    backgroundColor: colors.primary,
    borderRadius: radius.full,
  },
  percentText: {
    color: colors.primary,
    fontSize: font.xl,
    fontWeight: '700',
  },
  messageBox: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: colors.surface,
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.md,
    borderRadius: radius.md,
    width: '100%',
  },
  messageText: {
    color: colors.textPrimary,
    fontSize: font.base,
    flex: 1,
  },
  infoCard: {
    width: '100%',
    backgroundColor: colors.card,
    borderRadius: radius.md,
    padding: spacing.md,
    gap: spacing.xs,
  },
});
