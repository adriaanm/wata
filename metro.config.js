const path = require('path');

const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');

/**
 * Metro configuration for monorepo structure
 * https://reactnative.dev/docs/metro
 *
 * @type {import('@react-native/metro-config').MetroConfig}
 */
const config = {
  // Watch all workspace packages
  watchFolders: [path.resolve(__dirname, 'src')],

  resolver: {
    // Ensure Metro can resolve workspace packages
    nodeModulesPaths: [path.resolve(__dirname, 'node_modules')],
  },

  // Set the project root and entry file
  projectRoot: __dirname,
};

module.exports = mergeConfig(getDefaultConfig(__dirname), config);
