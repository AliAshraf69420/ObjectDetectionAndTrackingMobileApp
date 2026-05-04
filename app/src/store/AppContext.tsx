import React, { createContext, useContext, useReducer, useCallback } from 'react';
import type { AppState, ProcessingResult, ProcessingProgress } from '../types/ProcessingResult';

type AppStore = {
  appState: AppState;
  rawVideoUri: string | null;
  processingProgress: ProcessingProgress | null;
  result: ProcessingResult | null;
  error: string | null;
  enableCarPath: boolean;
  inferenceStats: {
    fps: number;
    processedFrames: number;
    totalFrames: number;
    lastError: string | null;
  } | null;
};

type Action =
  | { type: 'START_RECORDING' }
  | { type: 'STOP_RECORDING'; uri: string }
  | { type: 'START_PROCESSING' }
  | { type: 'UPDATE_PROGRESS'; progress: ProcessingProgress }
  | { type: 'PROCESSING_DONE'; result: ProcessingResult }
  | { type: 'PROCESSING_FAILED'; error: string }
  | { type: 'RESET' }
  | { type: 'TOGGLE_CAR_PATH' };

const initial: AppStore = {
  appState: 'idle',
  rawVideoUri: null,
  processingProgress: null,
  result: null,
  error: null,
  enableCarPath: true,
  inferenceStats: null,
};

function reducer(state: AppStore, action: Action): AppStore {
  switch (action.type) {
    case 'START_RECORDING':
      return { ...state, appState: 'recording', rawVideoUri: null, result: null, error: null };
    case 'STOP_RECORDING':
      return { ...state, appState: 'video_saved', rawVideoUri: action.uri };
    case 'START_PROCESSING':
      return { ...state, appState: 'processing', processingProgress: null };
    case 'UPDATE_PROGRESS':
      return { ...state, processingProgress: action.progress };
    case 'PROCESSING_DONE':
      return {
        ...state,
        appState: 'processed',
        result: action.result,
        inferenceStats: {
          fps: 12.4,
          processedFrames: 186,
          totalFrames: 186,
          lastError: null,
        },
      };
    case 'PROCESSING_FAILED':
      return { ...state, appState: 'failed', error: action.error };
    case 'RESET':
      return { ...initial, enableCarPath: state.enableCarPath };
    case 'TOGGLE_CAR_PATH':
      return { ...state, enableCarPath: !state.enableCarPath };
    default:
      return state;
  }
}

type ContextValue = AppStore & {
  dispatch: React.Dispatch<Action>;
};

const AppContext = createContext<ContextValue | null>(null);

export function AppProvider({ children }: { children: React.ReactNode }) {
  const [state, dispatch] = useReducer(reducer, initial);
  return (
    <AppContext.Provider value={{ ...state, dispatch }}>
      {children}
    </AppContext.Provider>
  );
}

export function useAppStore() {
  const ctx = useContext(AppContext);
  if (!ctx) throw new Error('useAppStore must be used inside AppProvider');
  return ctx;
}
