// Jest configuration for unit tests
// Unit tests are located in src/**/__tests__/
// This config is separate from integration tests which are in test/integration/

/** @type {import('jest').Config} */
module.exports = {
  testEnvironment: 'node',
  rootDir: './',
  testMatch: ['<rootDir>/src/**/__tests__/**/*.test.ts'],
  moduleFileExtensions: ['ts', 'tsx', 'js', 'jsx', 'json', 'node'],
  transform: {
    '^.+\\.tsx?$': ['<rootDir>/jest-preprocessor.js'],
  },
  // Standard timeout for unit tests
  testTimeout: 5000,
  // Don't transform node_modules
  transformIgnorePatterns: ['node_modules'],
  // Path aliases from tsconfig.json
  moduleNameMapper: {
    '^@shared/(.*)$': '<rootDir>/src/shared/$1',
    '^@rn/(.*)$': '<rootDir>/src/rn/$1',
    '^@tui/(.*)$': '<rootDir>/src/tui/$1',
  },
};
