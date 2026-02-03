import path from 'node:path';

import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
      '@shared': path.resolve(__dirname, '../shared'),
      // Buffer polyfill for matrix-js-sdk
      buffer: 'buffer',
    },
  },
  define: {
    // Process polyfill for matrix-js-sdk
    global: 'globalThis',
    // Environment variables for browser
    'process.env.WATA_MATRIX_IMPL': JSON.stringify(process.env.WATA_MATRIX_IMPL || 'wata-client'),
  },
  server: {
    port: 3000,
    open: true,
  },
  optimizeDeps: {
    include: ['buffer'],
  },
});
