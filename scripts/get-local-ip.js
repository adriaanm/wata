#!/usr/bin/env node

/**
 * Get local IP address for device testing
 */

/* eslint-disable @typescript-eslint/no-require-imports, no-console */

const os = require('os');

function getLocalIP() {
  const interfaces = os.networkInterfaces();

  for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name]) {
      // Skip internal and IPv6 addresses
      if (iface.family === 'IPv4' && !iface.internal) {
        return iface.address;
      }
    }
  }

  return null;
}

const ip = getLocalIP();

if (ip) {
  console.log('\n🌐 Your local IP address for device testing:\n');
  console.log(`   http://${ip}:8008`);
  console.log('\n📝 Update src/shared/config/matrix.ts:');
  console.log(`   homeserverUrl: 'http://${ip}:8008'\n`);
  console.log('💡 For Android emulator, use: http://10.0.2.2:8008\n');
} else {
  console.log('❌ Could not determine local IP address');
  console.log('💡 For Android emulator, use: http://10.0.2.2:8008');
}
