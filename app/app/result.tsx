import React, { useState } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  ScrollView,
  FlatList,
  Platform,
} from 'react-native';
import * as FileSystem from 'expo-file-system/legacy';
import { VideoView, useVideoPlayer } from 'expo-video';
import { router } from 'expo-router';
import * as MediaLibrary from 'expo-media-library';
import { useAppStore } from '../src/store/AppContext';
import { colors, spacing, font, radius } from '../src/components/theme';
import type { PinEvent } from '../src/types/ProcessingResult';

export default function ResultScreen() {
  const { result, appState, error, dispatch } = useAppStore();
  const [saveStatus, setSaveStatus] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle');
  const [mediaPermission, requestMediaPermission] = MediaLibrary.usePermissions();

  const player = useVideoPlayer(result?.outputVideoUri ?? null, p => {
    p.loop = true;
    p.play();
  });

  const handleReset = () => {
    dispatch({ type: 'RESET' });
    router.replace('/record');
  };

  const handleSaveToGallery = async () => {
    if (!result?.outputVideoUri) return;
    setSaveStatus('saving');
    try {
      if (!mediaPermission?.granted) {
        const { granted } = await requestMediaPermission();
        if (!granted) { setSaveStatus('error'); return; }
      }
      let uri = result.outputVideoUri;
      if (uri.startsWith('content://media/')) {
        // Already in MediaStore — nothing to save
        setSaveStatus('saved');
        return;
      }
      if (Platform.OS === 'android' && !uri.startsWith('file://')) {
        const dest = `${FileSystem.cacheDirectory}gallery_save.mp4`;
        await FileSystem.copyAsync({ from: uri, to: dest });
        uri = dest;
      }
      await MediaLibrary.saveToLibraryAsync(uri);
      setSaveStatus('saved');
    } catch (e) {
      console.error('[Save to Gallery]', e);
      setSaveStatus('error');
    }
  };

  if (appState === 'failed' || !result) {
    return (
      <View style={styles.center}>
        <Text style={styles.errorIcon}>✗</Text>
        <Text style={styles.errorTitle}>Processing Failed</Text>
        <Text style={styles.errorMsg}>{error ?? 'Unknown error occurred.'}</Text>
        <TouchableOpacity style={styles.btnPrimary} onPress={handleReset}>
          <Text style={styles.btnText}>Try Again</Text>
        </TouchableOpacity>
      </View>
    );
  }

  const elapsedSec = (result.elapsedMs / 1000).toFixed(2);

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container}>
      {/* Video player */}
      <View style={styles.videoCard}>
        <VideoView
          player={player}
          style={styles.video}
          fullscreenOptions={{ isEnabled: true }}
          allowsPictureInPicture
          contentFit="contain"
        />
      </View>

      {/* Score summary */}
      <View style={styles.scoreRow}>
        <ScoreCard label="Pins Down" value={String(result.pinsKnockedDown)} accent />
        <ScoreCard label="Elapsed" value={`${elapsedSec}s`} />
        {result.carPath && <ScoreCard label="Path Points" value={String(result.carPath.length)} />}
      </View>

      {/* Pin events table */}
      {result.pinEvents.length > 0 && (
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Pin Fall Order</Text>
          <View style={styles.tableHeader}>
            <Text style={[styles.cell, styles.cellSmall, styles.headerText]}>#</Text>
            <Text style={[styles.cell, styles.headerText]}>Pin ID</Text>
            <Text style={[styles.cell, styles.headerText]}>Time</Text>
          </View>
          {result.pinEvents.map(event => (
            <PinRow key={event.pinTrackId} event={event} />
          ))}
        </View>
      )}

      {/* Car path summary */}
      {result.carPath && result.carPath.length > 0 && (
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Car Path Detected</Text>
          <Text style={styles.bodyText}>
            {result.carPath.length} trajectory points recorded over {(result.carPath[result.carPath.length - 1].timeMs / 1000).toFixed(1)}s
          </Text>
        </View>
      )}

      {/* Actions */}
      <TouchableOpacity
        style={[styles.btnSave, saveStatus === 'saved' && styles.btnSaveDone, saveStatus === 'error' && styles.btnSaveError]}
        onPress={handleSaveToGallery}
        disabled={saveStatus === 'saving' || saveStatus === 'saved'}
      >
        <Text style={styles.btnText}>
          {saveStatus === 'idle' && 'Save to Gallery'}
          {saveStatus === 'saving' && 'Saving…'}
          {saveStatus === 'saved' && 'Saved!'}
          {saveStatus === 'error' && 'Save Failed — Tap to Retry'}
        </Text>
      </TouchableOpacity>
      <View style={styles.actionsRow}>
        <TouchableOpacity style={styles.btnSecondary} onPress={() => router.push('/debug')}>
          <Text style={styles.btnSecondaryText}>Debug Info</Text>
        </TouchableOpacity>
        <TouchableOpacity style={styles.btnPrimary} onPress={handleReset}>
          <Text style={styles.btnText}>New Recording</Text>
        </TouchableOpacity>
      </View>
    </ScrollView>
  );
}

function ScoreCard({ label, value, accent }: { label: string; value: string; accent?: boolean }) {
  return (
    <View style={scoreStyles.card}>
      <Text style={[scoreStyles.value, accent && scoreStyles.accent]}>{value}</Text>
      <Text style={scoreStyles.label}>{label}</Text>
    </View>
  );
}

function PinRow({ event }: { event: PinEvent }) {
  const ms = (event.timeMs / 1000).toFixed(2);
  const orderColors = ['#FFD700', '#C0C0C0', '#CD7F32'];
  const orderColor = orderColors[event.order - 1] ?? colors.textSecondary;
  return (
    <View style={tableStyles.row}>
      <Text style={[tableStyles.cell, tableStyles.cellSmall, { color: orderColor, fontWeight: '700' }]}>
        {event.order}
      </Text>
      <Text style={tableStyles.cell}>Pin {event.pinTrackId}</Text>
      <Text style={tableStyles.cell}>{ms}s</Text>
    </View>
  );
}

const scoreStyles = StyleSheet.create({
  card: {
    flex: 1,
    backgroundColor: colors.card,
    borderRadius: radius.md,
    padding: spacing.md,
    alignItems: 'center',
    gap: spacing.xs,
  },
  value: { color: colors.textPrimary, fontSize: font.xxl, fontWeight: '700' },
  accent: { color: colors.primary },
  label: { color: colors.textSecondary, fontSize: font.sm },
});

const tableStyles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    borderTopWidth: 1,
    borderTopColor: colors.border,
    paddingVertical: spacing.sm,
  },
  cell: { flex: 1, color: colors.textPrimary, fontSize: font.md },
  cellSmall: { flex: 0.5 },
});

const styles = StyleSheet.create({
  scroll: { flex: 1, backgroundColor: colors.bg },
  container: {
    padding: spacing.lg,
    gap: spacing.lg,
    paddingBottom: spacing.xxl,
  },
  center: {
    flex: 1,
    backgroundColor: colors.bg,
    alignItems: 'center',
    justifyContent: 'center',
    padding: spacing.xl,
    gap: spacing.md,
  },

  videoCard: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    overflow: 'hidden',
    aspectRatio: 16 / 9,
    width: '100%',
  },
  video: { width: '100%', height: '100%' },

  scoreRow: { flexDirection: 'row', gap: spacing.sm },

  section: {
    backgroundColor: colors.card,
    borderRadius: radius.md,
    padding: spacing.md,
    gap: spacing.sm,
  },
  sectionTitle: {
    color: colors.textPrimary,
    fontSize: font.lg,
    fontWeight: '700',
    marginBottom: spacing.xs,
  },
  bodyText: { color: colors.textSecondary, fontSize: font.md },

  tableHeader: {
    flexDirection: 'row',
    paddingBottom: spacing.sm,
    borderBottomWidth: 1,
    borderBottomColor: colors.border,
  },
  cell: { flex: 1 },
  cellSmall: { flex: 0.5 },
  headerText: { color: colors.textSecondary, fontSize: font.sm, fontWeight: '600' },

  btnSave: {
    backgroundColor: colors.primary,
    paddingVertical: spacing.md,
    borderRadius: radius.full,
    alignItems: 'center',
  },
  btnSaveDone: { backgroundColor: '#2e7d32' },
  btnSaveError: { backgroundColor: colors.error },
  actionsRow: { flexDirection: 'row', gap: spacing.sm },
  btnPrimary: {
    flex: 1,
    backgroundColor: colors.primary,
    paddingVertical: spacing.md,
    borderRadius: radius.full,
    alignItems: 'center',
  },
  btnSecondary: {
    flex: 1,
    backgroundColor: colors.surface,
    paddingVertical: spacing.md,
    borderRadius: radius.full,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: colors.border,
  },
  btnText: { color: '#fff', fontSize: font.base, fontWeight: '700' },
  btnSecondaryText: { color: colors.textSecondary, fontSize: font.base, fontWeight: '600' },

  errorIcon: { fontSize: 56, color: colors.error },
  errorTitle: { color: colors.textPrimary, fontSize: font.xxl, fontWeight: '700' },
  errorMsg: { color: colors.textSecondary, fontSize: font.base, textAlign: 'center' },
});
