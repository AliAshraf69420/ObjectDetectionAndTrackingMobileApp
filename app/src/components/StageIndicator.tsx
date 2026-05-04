import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { colors, spacing, font, radius } from './theme';
import type { ProcessingStage } from '../types/ProcessingResult';

const STAGES: { key: ProcessingStage; label: string }[] = [
  { key: 'decoding',      label: 'Decode'   },
  { key: 'preprocessing', label: 'Prep'     },
  { key: 'inference',     label: 'Infer'    },
  { key: 'tracking',      label: 'Track'    },
  { key: 'rendering',     label: 'Render'   },
];

const STAGE_ORDER: ProcessingStage[] = STAGES.map(s => s.key);

type Props = { currentStage: ProcessingStage };

export function StageIndicator({ currentStage }: Props) {
  const currentIndex = STAGE_ORDER.indexOf(currentStage);

  return (
    <View style={styles.row}>
      {STAGES.map((stage, i) => {
        const done    = i < currentIndex;
        const active  = i === currentIndex;
        const pending = i > currentIndex;
        return (
          <React.Fragment key={stage.key}>
            <View style={styles.step}>
              <View style={[styles.dot, done && styles.dotDone, active && styles.dotActive]}>
                {done && <Text style={styles.check}>✓</Text>}
                {active && <View style={styles.pulse} />}
              </View>
              <Text style={[styles.label, active && styles.labelActive, pending && styles.labelPending]}>
                {stage.label}
              </Text>
            </View>
            {i < STAGES.length - 1 && (
              <View style={[styles.connector, done && styles.connectorDone]} />
            )}
          </React.Fragment>
        );
      })}
    </View>
  );
}

const DOT = 28;

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    justifyContent: 'center',
    paddingHorizontal: spacing.md,
  },
  step: {
    alignItems: 'center',
    gap: spacing.xs,
  },
  dot: {
    width: DOT,
    height: DOT,
    borderRadius: DOT / 2,
    backgroundColor: colors.surface,
    borderWidth: 2,
    borderColor: colors.border,
    alignItems: 'center',
    justifyContent: 'center',
  },
  dotDone: {
    backgroundColor: colors.success,
    borderColor: colors.success,
  },
  dotActive: {
    borderColor: colors.primary,
    backgroundColor: colors.primaryDim,
  },
  pulse: {
    width: 10,
    height: 10,
    borderRadius: 5,
    backgroundColor: colors.primary,
  },
  connector: {
    flex: 1,
    height: 2,
    backgroundColor: colors.border,
    marginTop: DOT / 2 - 1,
  },
  connectorDone: {
    backgroundColor: colors.success,
  },
  label: {
    fontSize: font.sm,
    color: colors.textSecondary,
  },
  labelActive: {
    color: colors.primary,
    fontWeight: '600',
  },
  labelPending: {
    color: colors.border,
  },
  check: {
    color: '#fff',
    fontSize: font.sm,
    fontWeight: '700',
  },
});
