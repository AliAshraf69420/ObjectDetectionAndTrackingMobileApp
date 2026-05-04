export type PinEvent = {
  pinTrackId: number;
  order: number;
  timeMs: number;
};

export type CarPathPoint = {
  timeMs: number;
  x: number;
  y: number;
};

export type ProcessingResult = {
  outputVideoUri: string;
  elapsedMs: number;
  pinsKnockedDown: number;
  pinEvents: PinEvent[];
  carPath?: CarPathPoint[];
};

export type ProcessingStage =
  | 'decoding'
  | 'preprocessing'
  | 'inference'
  | 'tracking'
  | 'rendering';

export type ProcessingProgress = {
  stage: ProcessingStage;
  percent: number;
  message: string;
};

export type AppState =
  | 'idle'
  | 'recording'
  | 'video_saved'
  | 'processing'
  | 'processed'
  | 'failed';
