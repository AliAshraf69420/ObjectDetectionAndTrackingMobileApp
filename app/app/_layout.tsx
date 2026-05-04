import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { AppProvider } from '../src/store/AppContext';
import { colors } from '../src/components/theme';

export default function RootLayout() {
  return (
    <AppProvider>
      <StatusBar style="light" />
      <Stack
        screenOptions={{
          headerStyle: { backgroundColor: colors.surface },
          headerTintColor: colors.textPrimary,
          headerTitleStyle: { fontWeight: '700' },
          contentStyle: { backgroundColor: colors.bg },
          animation: 'slide_from_right',
        }}
      >
        <Stack.Screen name="index"      options={{ headerShown: false }} />
        <Stack.Screen name="record"     options={{ title: 'Record Run', headerBackVisible: true }} />
        <Stack.Screen name="processing" options={{ title: 'Processing', headerBackVisible: false, gestureEnabled: false }} />
        <Stack.Screen name="result"     options={{ title: 'Results' }} />
        <Stack.Screen name="debug"      options={{ title: 'Debug / Settings' }} />
      </Stack>
    </AppProvider>
  );
}
