import type { ProcessingResult, ProcessingProgress } from '../types/ProcessingResult';

export type ProcessVideoOptions = {
  inputVideoUri: string;
  enableCarPath?: boolean;
  onProgress?: (progress: ProcessingProgress) => void;
};

// Stub implementation — replaced when the native Kotlin module is wired up.
// Simulates the full pipeline with fake data so the UI layer can be tested end-to-end.
export const ProcessingModule = {
  async processVideo(options: ProcessVideoOptions): Promise<ProcessingResult> {
    const stages: Array<{ stage: ProcessingProgress['stage']; label: string; duration: number }> = [
      { stage: 'decoding',       label: 'Decoding video frames…',         duration: 800  },
      { stage: 'preprocessing',  label: 'Preprocessing frames for model…', duration: 600  },
      { stage: 'inference',      label: 'Running on-device inference…',    duration: 1400 },
      { stage: 'tracking',       label: 'Tracking pins across frames…',    duration: 700  },
      { stage: 'rendering',      label: 'Rendering annotated output…',     duration: 900  },
    ];

    let elapsed = 0;
    const total = stages.reduce((s, st) => s + st.duration, 0);

    for (const st of stages) {
      options.onProgress?.({ stage: st.stage, percent: Math.round((elapsed / total) * 100), message: st.label });
      await delay(st.duration);
      elapsed += st.duration;
    }

    options.onProgress?.({ stage: 'rendering', percent: 100, message: 'Done!' });

    return {
      outputVideoUri: options.inputVideoUri,
      elapsedMs: 13_200,
      pinsKnockedDown: 5,
      pinEvents: [
        { pinTrackId: 3, order: 1, timeMs: 1450 },
        { pinTrackId: 1, order: 2, timeMs: 2200 },
        { pinTrackId: 5, order: 3, timeMs: 3100 },
        { pinTrackId: 2, order: 4, timeMs: 4800 },
        { pinTrackId: 4, order: 5, timeMs: 6500 },
      ],
      carPath: options.enableCarPath
        ? [
            { timeMs: 0,    x: 120, y: 600 },
            { timeMs: 200,  x: 132, y: 575 },
            { timeMs: 400,  x: 148, y: 540 },
            { timeMs: 600,  x: 160, y: 500 },
            { timeMs: 800,  x: 168, y: 460 },
            { timeMs: 1000, x: 172, y: 420 },
          ]
        : undefined,
    };
  },

  isNativeAvailable(): boolean {
    return false;
  },
};

const delay = (ms: number) => new Promise<void>(res => setTimeout(res, ms));
