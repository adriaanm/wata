import React, { useState, useCallback } from 'react';
import { Pressable, PressableProps, StyleProp, ViewStyle } from 'react-native';

interface FocusablePressableProps extends Omit<PressableProps, 'style'> {
  style?: StyleProp<ViewStyle>;
  focusedStyle?: StyleProp<ViewStyle>;
  children: React.ReactNode;
}

export function FocusablePressable({
  style,
  focusedStyle,
  children,
  onFocus,
  onBlur,
  ...props
}: FocusablePressableProps) {
  const [isFocused, setIsFocused] = useState(false);

  const handleFocus = useCallback(
    (e: unknown) => {
      setIsFocused(true);
      onFocus?.(e as never);
    },
    [onFocus],
  );

  const handleBlur = useCallback(
    (e: unknown) => {
      setIsFocused(false);
      onBlur?.(e as never);
    },
    [onBlur],
  );

  return (
    <Pressable
      style={[style, isFocused && focusedStyle]}
      onFocus={handleFocus}
      onBlur={handleBlur}
      {...props}
    >
      {children}
    </Pressable>
  );
}
