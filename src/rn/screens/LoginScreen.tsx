import { useAuth } from '@shared/hooks/useMatrix';
import React, { useState, useRef } from 'react';
import {
  View,
  Text,
  TextInput,
  StyleSheet,
  ActivityIndicator,
} from 'react-native';

import { FocusablePressable } from '../components/FocusablePressable';
import { colors, typography, spacing, components } from '../theme';

interface Props {
  onLoginSuccess: () => void;
}

export function LoginScreen({ onLoginSuccess }: Props) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [focusedField, setFocusedField] = useState<string | null>(null);
  const { login, isLoading, error } = useAuth();

  const passwordRef = useRef<TextInput>(null);

  const handleLogin = async () => {
    if (!username.trim() || !password.trim()) return;

    try {
      await login(username.trim(), password);
      onLoginSuccess();
    } catch {
      // Error is handled by useAuth hook
    }
  };

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.title}>WATA</Text>
      </View>

      <View style={styles.form}>
        <TextInput
          style={[
            styles.input,
            focusedField === 'username' && styles.inputFocused,
          ]}
          placeholder="Username"
          placeholderTextColor={colors.textMuted}
          value={username}
          onChangeText={setUsername}
          autoCapitalize="none"
          autoCorrect={false}
          editable={!isLoading}
          onFocus={() => setFocusedField('username')}
          onBlur={() => setFocusedField(null)}
          onSubmitEditing={() => passwordRef.current?.focus()}
          returnKeyType="next"
        />

        <TextInput
          ref={passwordRef}
          style={[
            styles.input,
            focusedField === 'password' && styles.inputFocused,
          ]}
          placeholder="Password"
          placeholderTextColor={colors.textMuted}
          value={password}
          onChangeText={setPassword}
          secureTextEntry
          editable={!isLoading}
          onFocus={() => setFocusedField('password')}
          onBlur={() => setFocusedField(null)}
          onSubmitEditing={handleLogin}
          returnKeyType="go"
        />

        {error && <Text style={styles.error}>{error}</Text>}

        <FocusablePressable
          style={[styles.button, isLoading && styles.buttonDisabled]}
          focusedStyle={styles.buttonFocused}
          onPress={handleLogin}
          disabled={isLoading || !username.trim() || !password.trim()}
        >
          {isLoading ? (
            <ActivityIndicator color={colors.text} size="small" />
          ) : (
            <Text style={styles.buttonText}>CONNECT</Text>
          )}
        </FocusablePressable>
      </View>

      <View style={styles.footer}>
        <Text style={styles.hint}>matrix.org</Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    ...components.screen,
    paddingHorizontal: spacing.md,
  },
  header: {
    paddingVertical: spacing.lg,
    alignItems: 'center',
  },
  title: {
    ...typography.title,
    letterSpacing: 4,
  },
  form: {
    flex: 1,
    justifyContent: 'center',
  },
  input: {
    ...components.input,
  },
  inputFocused: {
    ...components.inputFocused,
  },
  button: {
    ...components.button,
    marginTop: spacing.md,
  },
  buttonFocused: {
    ...components.buttonFocused,
  },
  buttonDisabled: {
    opacity: 0.5,
  },
  buttonText: {
    ...components.buttonText,
  },
  error: {
    ...typography.small,
    color: colors.error,
    textAlign: 'center',
    marginBottom: spacing.sm,
  },
  footer: {
    paddingVertical: spacing.md,
    alignItems: 'center',
  },
  hint: {
    ...typography.small,
    color: colors.textMuted,
  },
});
