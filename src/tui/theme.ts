/**
 * Theme for TUI - Terminal colors mapped from mobile theme
 */

export const colors = {
  background: 'black', // #000000
  backgroundLight: 'gray', // #1a1a1a
  text: 'white', // #FFFFFF
  textMuted: 'gray', // #AAAAAA
  accent: 'cyan', // #00AAFF
  recording: 'red', // #FF3333
  playing: 'green', // #33FF33
  focus: 'yellow', // #FFAA00 (selection highlight)
  error: 'red', // #FF6666
} as const;

export type Color = (typeof colors)[keyof typeof colors];
