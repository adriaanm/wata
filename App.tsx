import { NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import React, { useState, useEffect } from 'react';
import { StatusBar, ActivityIndicator, View, StyleSheet } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { KeyDebugOverlay } from './src/components/KeyDebugOverlay';
import { ChatScreen } from './src/screens/ChatScreen';
import { ContactListScreen } from './src/screens/ContactListScreen';
import { LoginScreen } from './src/screens/LoginScreen';
import { matrixService } from './src/services/MatrixService';
import { colors } from './src/theme';

// Set to false to hide key debug overlay
const DEBUG_KEYS = true;

type RootStackParamList = {
  Login: undefined;
  ContactList: undefined;
  Chat: { roomId: string; roomName: string };
};

const Stack = createNativeStackNavigator<RootStackParamList>();

function App(): React.JSX.Element {
  const [isLoading, setIsLoading] = useState(true);
  const [isLoggedIn, setIsLoggedIn] = useState(false);

  useEffect(() => {
    const restoreSession = async () => {
      try {
        const restored = await matrixService.restoreSession();
        setIsLoggedIn(restored);
      } catch {
        setIsLoggedIn(false);
      } finally {
        setIsLoading(false);
      }
    };

    restoreSession();
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
            initialRouteName={isLoggedIn ? 'ContactList' : 'Login'}
            screenOptions={{
              headerShown: false,
              contentStyle: { backgroundColor: colors.bg },
              animation: 'slide_from_right',
            }}
          >
            <Stack.Screen name="Login">
              {({ navigation }) => (
                <LoginScreen
                  onLoginSuccess={() => {
                    setIsLoggedIn(true);
                    navigation.reset({
                      index: 0,
                      routes: [{ name: 'ContactList' }],
                    });
                  }}
                />
              )}
            </Stack.Screen>

            <Stack.Screen name="ContactList">
              {({ navigation }) => (
                <ContactListScreen
                  onSelectContact={(roomId, roomName) => {
                    navigation.navigate('Chat', { roomId, roomName });
                  }}
                  onLogout={async () => {
                    await matrixService.logout();
                    setIsLoggedIn(false);
                    navigation.reset({
                      index: 0,
                      routes: [{ name: 'Login' }],
                    });
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
