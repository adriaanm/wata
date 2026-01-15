import React, { useMemo } from 'react';
import { Box, Text, useInput, useStdout } from 'ink';
import { PROFILES, type ProfileKey } from '../types/profile.js';
import { colors } from '../theme.js';

const PROFILE_KEYS = Object.keys(PROFILES) as ProfileKey[];

interface Props {
  currentProfile: ProfileKey;
  onSelectProfile: (profileKey: ProfileKey) => void;
  onBack: () => void;
}

export function ProfileSelectorView({ currentProfile, onSelectProfile, onBack }: Props) {
  const [selectedIndex, setSelectedIndex] = React.useState(
    PROFILE_KEYS.indexOf(currentProfile)
  );
  const { stdout } = useStdout();

  const handleSelect = () => {
    onSelectProfile(PROFILE_KEYS[selectedIndex]);
  };

  // Keyboard navigation
  useInput((input, key) => {
    if (key.escape || input === 'p') {
      onBack();
      return;
    }

    if (key.return || input === '\r' || input === '\n') {
      handleSelect();
      return;
    }

    if (key.upArrow || input === 'k') {
      setSelectedIndex((prev) => Math.max(0, prev - 1));
    }

    if (key.downArrow || input === 'j') {
      setSelectedIndex((prev) => Math.min(PROFILE_KEYS.length - 1, prev + 1));
    }

    // Number shortcuts
    const num = parseInt(input, 10);
    if (!isNaN(num) && num >= 1 && num <= PROFILE_KEYS.length) {
      onSelectProfile(PROFILE_KEYS[num - 1]);
    }
  });

  return (
    <Box flexDirection="column" padding={1}>
      {/* Header */}
      <Box marginBottom={1}>
        <Text bold color={colors.accent}>
          Select Profile
        </Text>
      </Box>

      {/* Profile list */}
      {PROFILE_KEYS.map((key, index) => {
        const profile = PROFILES[key];
        const isCurrent = key === currentProfile;
        const isSelected = index === selectedIndex;

        return (
          <Box
            key={key}
            backgroundColor={isSelected ? 'gray' : undefined}
            paddingX={isSelected ? 1 : 0}
          >
            <Text color={colors.textMuted}>{index + 1}.</Text>
            <Text> </Text>
            <Text bold color={profile.color}>
              {profile.displayName}
            </Text>
            <Text> </Text>
            <Text dimColor>({profile.username})</Text>
            {isCurrent && (
              <>
                <Text> </Text>
                <Text color="green">[Current]</Text>
              </>
            )}
          </Box>
        );
      })}

      {/* Help text */}
      <Box marginTop={1}>
        <Text dimColor>
          ↑↓/jk Navigate  Enter/1-2 Select  p/Esc Back
        </Text>
      </Box>
    </Box>
  );
}
