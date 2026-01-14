/**
 * @format
 */

// Polyfills needed for matrix-js-sdk
// IMPORTANT: crypto polyfill must come first
import 'react-native-get-random-values';

import { Buffer } from 'buffer';
global.Buffer = Buffer;

// Polyfill for Promise.withResolvers (not yet in Hermes)
if (!Promise.withResolvers) {
  Promise.withResolvers = function () {
    let resolve, reject;
    const promise = new Promise((res, rej) => {
      resolve = res;
      reject = rej;
    });
    return { promise, resolve, reject };
  };
}

// Note: URL fixing is now handled by FixedFetchHttpApi in the Matrix client config

import { AppRegistry } from 'react-native';

import App from './src/rn/App';
import { name as appName } from './app.json';

AppRegistry.registerComponent(appName, () => App);
