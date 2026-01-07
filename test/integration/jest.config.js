/** @type {import('jest').Config} */
module.exports = {
  preset: 'ts-jest/presets/default-esm',
  testEnvironment: 'node',
  rootDir: '../../',
  testMatch: ['<rootDir>/test/integration/**/*.test.ts'],
  moduleFileExtensions: ['ts', 'tsx', 'js', 'jsx', 'json', 'node'],
  extensionsToTreatAsEsm: ['.ts'],
  transform: {
    '^.+\\.tsx?$': [
      'ts-jest',
      {
        useESM: true,
        tsconfig: {
          module: 'ESNext',
          esModuleInterop: true,
          allowSyntheticDefaultImports: true,
          target: 'ES2020',
          moduleResolution: 'node',
          strict: false,
        },
      },
    ],
  },
  // Longer timeout for integration tests
  testTimeout: 30000,
  // Don't transform node_modules (they're already ESM)
  transformIgnorePatterns: [],
  // Mock React Native specific modules
  moduleNameMapper: {
    '^react-native-keychain$': '<rootDir>/test/integration/__mocks__/react-native-keychain.ts',
    '^react-native-fs$': '<rootDir>/test/integration/__mocks__/react-native-fs.ts',
    '^react-native-audio-recorder-player$': '<rootDir>/test/integration/__mocks__/react-native-audio-recorder-player.ts',
  },
};
