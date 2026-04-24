import Constants from 'expo-constants';

function normalizeBaseUrl(url: string): string {
  return url.replace(/\/+$/, '');
}

/**
 * Server base URL for the FastAPI backend.
 *
 * Set via Expo env: EXPO_PUBLIC_SERVER_URL (recommended).
 */
export const SERVER_URL: string = (() => {
  const fromEnv = process.env.EXPO_PUBLIC_SERVER_URL;
  if (fromEnv && fromEnv.trim().length > 0) return normalizeBaseUrl(fromEnv.trim());

  // Fallback: allows `app.json` "extra" to override without code changes.
  const fromExtra = (Constants.expoConfig?.extra as { serverUrl?: string } | undefined)?.serverUrl;
  if (fromExtra && fromExtra.trim().length > 0) return normalizeBaseUrl(fromExtra.trim());

  return 'http://localhost:8000';
})();
