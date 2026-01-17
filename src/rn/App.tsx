import { NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { matrixService } from '@shared/services/MatrixService.rn';
import React, { useState, useEffect } from 'react';
import { StatusBar, ActivityIndicator, View, StyleSheet } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { KeyDebugOverlay } from './components/KeyDebugOverlay';
import { ChatScreen } from './screens/ChatScreen';
import { ContactListScreen } from './screens/ContactListScreen';
import { colors } from './theme';

// Set to false to hide key debug overlay
const DEBUG_KEYS = true;

type RootStackParamList = {
  ContactList: undefined;
  Chat: { roomId: string; roomName: string };
};

const Stack = createNativeStackNavigator<RootStackParamList>();

function App(): React.JSX.Element {
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    const initializeAuth = async () => {
      try {
        console.log('[App] Attempting to restore session...');
        // Try to restore existing session first
        const restored = await matrixService.restoreSession();
        if (restored) {
          console.log('[App] Session restored successfully');
          return;
        }

        console.log('[App] No session found, attempting auto-login...');
        // If no session, auto-login with config credentials
        await matrixService.autoLogin();
        console.log('[App] Auto-login completed successfully');
      } catch (error) {
        console.error('[App] Auto-login failed:', error);
      } finally {
        setIsLoading(false);
      }
    };

    initializeAuth();
  }, []);

  if (isLoading) {
    return (
      <View style={styles.loadingContainer}>
        <StatusBar barStyle="light-content" backgroundColor={colors.bg} />
        <ActivityIndicator size="large" color={colors.primary} />
      </View>
    );
  }

  return (
    <SafeAreaProvider>
      <StatusBar barStyle="light-content" backgroundColor={colors.bg} />
      <View style={styles.container}>
        <NavigationContainer>
          <Stack.Navigator
            initialRouteName="ContactList"
            screenOptions={{
              headerShown: false,
              contentStyle: { backgroundColor: colors.bg },
              animation: 'slide_from_right',
            }}
          >
            <Stack.Screen name="ContactList">
              {({ navigation }) => (
                <ContactListScreen
                  onSelectContact={(roomId, roomName) => {
                    navigation.navigate('Chat', { roomId, roomName });
                  }}
                  onLogout={async () => {
                    await matrixService.logout();
                    // After logout, app will auto-login again on next launch
                  }}
                />
              )}
            </Stack.Screen>

            <Stack.Screen name="Chat">
              {({ navigation, route }) => (
                <ChatScreen
                  roomId={route.params.roomId}
                  roomName={route.params.roomName}
                  onBack={() => navigation.goBack()}
                />
              )}
            </Stack.Screen>
          </Stack.Navigator>
        </NavigationContainer>

        {DEBUG_KEYS && <KeyDebugOverlay />}
      </View>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.bg,
  },
  loadingContainer: {
    flex: 1,
    backgroundColor: colors.bg,
    justifyContent: 'center',
    alignItems: 'center',
  },
});

export default App;
