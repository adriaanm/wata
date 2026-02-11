/**
 * Matrix Configuration
 *
 * This file contains hardcoded credentials for the Matrix homeserver.
 * These values should be modified before building the app for production.
 *
 * For device testing with local Conduit server:
 * 1. Start the server: npm run dev:server
 * 2. Set up port forwarding: npm run dev:forward
 * 3. Run the app: npm run android
 *
 * The default config uses localhost:8008, which works for:
 * - Android emulator (built-in)
 * - Physical devices (via adb reverse proxy)
 * - Integration tests
 *
 * Future: This can be configured via:
 * - Build-time environment variables (for Android native Kotlin: edit MatrixConfig.kt)
 * - QR code scanning from a companion configuration app/website
 * - Device management portal
 */

export const MATRIX_CONFIG = {
  /**
   * Homeserver URL
   *
   * Default: http://localhost:8008
   * Works for emulator and physical devices (with adb reverse proxy)
   *
   * Alternative options:
   * - https://matrix.org - Production Matrix.org server
   * - http://YOUR_IP:8008 - Manual IP configuration (if adb reverse fails)
   */
  homeserverUrl: 'http://localhost:8008',

  /**
   * Username for auto-login
   * Note: This is just the username, not the full Matrix ID
   * The full ID will be @username:homeserver
   */
  username: 'alice',

  /**
   * Password for auto-login
   */
  password: 'testpass123',
};
