import { StyleSheet, ViewStyle } from 'react-native';

// Color palette - high contrast for small screen
export const colors = {
  // Backgrounds
  bg: '#000000',
  bgSecondary: '#1a1a1a',
  bgHighlight: '#333333',

  // Text
  text: '#FFFFFF',
  textSecondary: '#AAAAAA',
  textMuted: '#666666',

  // Accent
  primary: '#00AAFF',
  primaryDark: '#0088CC',

  // Status
  recording: '#FF3333',
  playing: '#33FF33',
  error: '#FF6666',

  // Focus indicator
  focus: '#FFAA00',
};

// Typography - large sizes for 1.77" screen
export const typography = {
  // Headers
  title: {
    fontSize: 24,
    fontWeight: 'bold' as const,
    color: colors.text,
  },
  header: {
    fontSize: 20,
    fontWeight: '600' as const,
    color: colors.text,
  },

  // Body text
  large: {
    fontSize: 18,
    color: colors.text,
  },
  body: {
    fontSize: 16,
    color: colors.text,
  },
  small: {
    fontSize: 14,
    color: colors.textSecondary,
  },

  // Special
  status: {
    fontSize: 20,
    fontWeight: 'bold' as const,
    color: colors.text,
  },
};

// Spacing - compact for small screen
export const spacing = {
  xs: 4,
  sm: 8,
  md: 12,
  lg: 16,
};

// Focus styles for D-pad navigation
export const focusStyle: ViewStyle = {
  borderWidth: 3,
  borderColor: colors.focus,
};

// Common component styles
export const components = StyleSheet.create({
  // Screen container
  screen: {
    flex: 1,
    backgroundColor: colors.bg,
  },

  // Focusable list item
  listItem: {
    backgroundColor: colors.bgSecondary,
    paddingVertical: spacing.md,
    paddingHorizontal: spacing.md,
    borderWidth: 2,
    borderColor: 'transparent',
    marginBottom: spacing.xs,
  },
  listItemFocused: {
    borderColor: colors.focus,
    backgroundColor: colors.bgHighlight,
  },
  listItemText: {
    ...typography.large,
  },

  // Button
  button: {
    backgroundColor: colors.primary,
    paddingVertical: spacing.md,
    paddingHorizontal: spacing.lg,
    alignItems: 'center' as const,
    justifyContent: 'center' as const,
    borderWidth: 2,
    borderColor: 'transparent',
  },
  buttonFocused: {
    borderColor: colors.focus,
    backgroundColor: colors.primaryDark,
  },
  buttonText: {
    ...typography.large,
    fontWeight: '600' as const,
  },

  // Input field
  input: {
    backgroundColor: colors.bgSecondary,
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.md,
    fontSize: 18,
    color: colors.text,
    borderWidth: 2,
    borderColor: 'transparent',
    marginBottom: spacing.sm,
  },
  inputFocused: {
    borderColor: colors.focus,
  },

  // Header bar
  header: {
    flexDirection: 'row' as const,
    alignItems: 'center' as const,
    justifyContent: 'space-between' as const,
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.md,
    backgroundColor: colors.bgSecondary,
    borderBottomWidth: 1,
    borderBottomColor: colors.bgHighlight,
  },
  headerTitle: {
    ...typography.header,
    flex: 1,
    textAlign: 'center' as const,
  },

  // Status indicator
  statusBar: {
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.md,
    alignItems: 'center' as const,
  },
  statusText: {
    ...typography.status,
  },

  // Empty state
  emptyState: {
    flex: 1,
    justifyContent: 'center' as const,
    alignItems: 'center' as const,
    padding: spacing.lg,
  },
  emptyText: {
    ...typography.body,
    color: colors.textMuted,
    textAlign: 'center' as const,
  },

  // Loading
  loading: {
    flex: 1,
    justifyContent: 'center' as const,
    alignItems: 'center' as const,
    backgroundColor: colors.bg,
  },
  loadingText: {
    ...typography.body,
    marginTop: spacing.md,
  },
});
