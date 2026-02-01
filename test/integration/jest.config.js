/** @type {import('jest').Config} */
module.exports = {
  preset: 'ts-jest/presets/default-esm',
  testEnvironment: 'node',
  rootDir: '../../',
  testMatch: ['<rootDir>/test/integration/**/*.test.ts', '<rootDir>/src/shared/**/*.test.ts'],
  moduleFileExtensions: ['ts', 'tsx', 'js', 'jsx', 'json', 'node', 'mjs'],
  extensionsToTreatAsEsm: ['.ts'],
  transform: {
    '^.+\\.tsx?$': [
      'ts-jest',
      {
        useESM: true,
        // Use separate tsconfig for tests that includes Node types
        tsconfig: '<rootDir>/tsconfig.test.json',
      },
    ],
  },
  // Longer timeout for integration tests
  testTimeout: 30000,
  // Don't transform node_modules (they're already ESM)
  transformIgnorePatterns: [],
  // Setup file to configure logging and other test globals
  setupFilesAfterEnv: ['<rootDir>/test/integration/setup.ts'],
  // Mock React Native specific modules and resolve path aliases
  moduleNameMapper: {
    '^react-native-keychain$': '<rootDir>/test/integration/__mocks__/react-native-keychain.ts',
    '^react-native-fs$': '<rootDir>/test/integration/__mocks__/react-native-fs.ts',
    '^react-native-audio-recorder-player$': '<rootDir>/test/integration/__mocks__/react-native-audio-recorder-player.ts',
    // Path aliases from tsconfig.json
    '^@shared/(.*)$': '<rootDir>/src/shared/$1',
    '^@rn/(.*)$': '<rootDir>/src/rn/$1',
    '^@tui/(.*)$': '<rootDir>/src/tui/$1',
  },
};
