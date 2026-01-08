import { NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import React, { useState, useEffect } from 'react';
import { StatusBar, ActivityIndicator, View, StyleSheet } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { KeyDebugOverlay } from './src/components/KeyDebugOverlay';
import { ChatScreen } from './src/screens/ChatScreen';
import { ContactListScreen } from './src/screens/ContactListScreen';
import { matrixService } from './src/services/MatrixService';
import { colors } from './src/theme';

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
        // Try to restore existing session first
        const restored = await matrixService.restoreSession();
        if (restored) {
          return;
        }

        // If no session, auto-login with config credentials
        await matrixService.autoLogin();
      } catch (error) {
        console.error('Auto-login failed:', error);
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
