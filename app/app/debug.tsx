import React from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  Switch,
  TouchableOpacity,
} from 'react-native';
import { router } from 'expo-router';
import { useAppStore } from '../src/store/AppContext';
import { ProcessingModule } from '../src/native/ProcessingModule';
import { colors, spacing, font, radius } from '../src/components/theme';

export default function DebugScreen() {
  const { appState, inferenceStats, enableCarPath, error, dispatch } = useAppStore();

  const goToResult = () => router.replace('/result');

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container}>
      {/* Module status */}
      <Section title="Native Module">
        <Row label="Status" value={ProcessingModule.isNativeAvailable() ? 'Hardware (Kotlin)' : 'Stub / Demo mode'} accent={!ProcessingModule.isNativeAvailable()} />
        <Row label="App state" value={appState} />
      </Section>

      {/* Inference stats */}
      <Section title="Inference Stats">
        {inferenceStats ? (
          <>
            <Row label="Processing FPS"  value={String(inferenceStats.fps)} />
            <Row label="Frames processed" value={`${inferenceStats.processedFrames} / ${inferenceStats.totalFrames}`} />
            <Row label="Last error"       value={inferenceStats.lastError ?? 'None'} accent={!!inferenceStats.lastError} />
          </>
        ) : (
          <Row label="Stats" value="Not yet processed" />
        )}
      </Section>

      {/* Settings */}
      <Section title="Settings">
        <View style={styles.settingRow}>
          <View>
            <Text style={styles.settingLabel}>Car Path Detection</Text>
            <Text style={styles.settingDesc}>Track car trajectory across frames</Text>
          </View>
          <Switch
            value={enableCarPath}
            onValueChange={() => dispatch({ type: 'TOGGLE_CAR_PATH' })}
            trackColor={{ false: colors.border, true: colors.primary }}
            thumbColor={enableCarPath ? '#fff' : colors.textSecondary}
          />
        </View>
      </Section>

      {/* Thresholds — display only, values come from native config */}
      <Section title="Detection Thresholds (native config)">
        <Row label="Confidence threshold"  value="0.45" />
        <Row label="IoU matching threshold" value="0.40" />
        <Row label="Fall stability frames"  value="3"    />
        <Row label="Sampling FPS target"   value="12"   />
        <Row label="Model format"          value="TFLite float16" />
        <Row label="Classes"               value="standing_pin · fallen_pin · car" />
      </Section>

      {/* Error log */}
      {error && (
        <Section title="Last Error">
          <Text style={styles.errorText}>{error}</Text>
        </Section>
      )}

      {/* Quick-navigate */}
      <View style={styles.navRow}>
        <NavBtn label="← Record"    onPress={() => router.replace('/record')} />
        <NavBtn label="Results →"   onPress={goToResult} />
      </View>
    </ScrollView>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <View style={sectionStyles.wrapper}>
      <Text style={sectionStyles.title}>{title}</Text>
      <View style={sectionStyles.card}>{children}</View>
    </View>
  );
}

function Row({ label, value, accent }: { label: string; value: string; accent?: boolean }) {
  return (
    <View style={rowStyles.row}>
      <Text style={rowStyles.label}>{label}</Text>
      <Text style={[rowStyles.value, accent && rowStyles.accent]}>{value}</Text>
    </View>
  );
}

function NavBtn({ label, onPress }: { label: string; onPress: () => void }) {
  return (
    <TouchableOpacity style={navStyles.btn} onPress={onPress}>
      <Text style={navStyles.text}>{label}</Text>
    </TouchableOpacity>
  );
}

const sectionStyles = StyleSheet.create({
  wrapper: { gap: spacing.sm },
  title: { color: colors.textSecondary, fontSize: font.sm, fontWeight: '600', textTransform: 'uppercase', letterSpacing: 1 },
  card: { backgroundColor: colors.card, borderRadius: radius.md, padding: spacing.md, gap: spacing.sm },
});

const rowStyles = StyleSheet.create({
  row: { flexDirection: 'row', justifyContent: 'space-between', paddingVertical: spacing.xs },
  label: { color: colors.textSecondary, fontSize: font.md, flex: 1 },
  value: { color: colors.textPrimary, fontSize: font.md, flex: 1, textAlign: 'right' },
  accent: { color: colors.warning },
});

const navStyles = StyleSheet.create({
  btn: {
    flex: 1,
    backgroundColor: colors.surface,
    padding: spacing.md,
    borderRadius: radius.full,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: colors.border,
  },
  text: { color: colors.textPrimary, fontSize: font.base, fontWeight: '600' },
});

const styles = StyleSheet.create({
  scroll: { flex: 1, backgroundColor: colors.bg },
  container: { padding: spacing.lg, gap: spacing.lg, paddingBottom: spacing.xxl },
  settingRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  settingLabel: { color: colors.textPrimary, fontSize: font.base, fontWeight: '600' },
  settingDesc: { color: colors.textSecondary, fontSize: font.sm, marginTop: 2 },
  errorText: { color: colors.error, fontSize: font.md, fontFamily: 'monospace' },
  navRow: { flexDirection: 'row', gap: spacing.sm, marginTop: spacing.sm },
});
