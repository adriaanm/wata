const path = require('path');

const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');

/**
 * Metro configuration for monorepo structure
 * https://reactnative.dev/docs/metro
 *
 * @type {import('@react-native/metro-config').MetroConfig}
 */
const projectRootDir = path.resolve(__dirname, '../..');

// Path alias mapping: @alias/* -> src/workspace/*
const workspaces = {
  '@shared': 'src/shared',
  '@rn': 'src/rn',
  '@tui': 'src/tui',
  '@web': 'src/web',
};

// Node.js built-ins to shim (used by Node-only packages like @evan/opus)
const nodeBuiltins = ['fs', 'path', 'crypto', 'stream', 'os', 'util', 'events'];

const config = {
  // Watch the monorepo root, pnpm store, and workspaces
  watchFolders: [
    projectRootDir,
    path.join(projectRootDir, 'node_modules', '.pnpm'),
    ...Object.values(workspaces).map((ws) => path.join(projectRootDir, ws)),
  ],

  resolver: {
    unstable_enableSymlinks: true,

    resolveRequest: (context, moduleName, platform) => {
      // Handle workspace path aliases: @shared/foo -> src/shared/foo
      for (const [alias, workspace] of Object.entries(workspaces)) {
        if (moduleName.startsWith(alias + '/')) {
          const relativePath = moduleName.slice(alias.length + 1);
          return context.resolveRequest(
            context,
            path.join(projectRootDir, workspace, relativePath),
            platform,
          );
        }
      }

      // Shim Node.js built-ins for RN compatibility
      if (nodeBuiltins.includes(moduleName)) {
        return { type: 'empty' };
      }

      // Handle .js -> .ts for TypeScript ESM imports
      if (moduleName.endsWith('.js')) {
        try {
          return context.resolveRequest(context, moduleName.slice(0, -3) + '.ts', platform);
        } catch {
          // Fall through to default resolution
        }
      }

      return context.resolveRequest(context, moduleName, platform);
    },
  },
};

module.exports = mergeConfig(getDefaultConfig(__dirname), config);
