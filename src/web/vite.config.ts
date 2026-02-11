import path from 'node:path';

import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';
import { nodePolyfills } from 'vite-plugin-node-polyfills';

export default defineConfig({
  plugins: [
    react(),
    nodePolyfills({
      // Whether to polyfill specific globals.
      globals: {
        Buffer: true,
        global: true,
      },
    }),
  ],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
      '@shared': path.resolve(__dirname, '../shared'),
    },
  },
  define: {
    // Environment variables for browser
    'process.env.WATA_MATRIX_IMPL': JSON.stringify(process.env.WATA_MATRIX_IMPL || 'wata-client'),
  },
  server: {
    port: 3000,
    open: true,
  },
});
