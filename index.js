/**
 * @format
 */

// Polyfills needed for matrix-js-sdk
// IMPORTANT: crypto polyfill must come first
import 'react-native-get-random-values';

import { Buffer } from 'buffer';
global.Buffer = Buffer;

import { AppRegistry } from 'react-native';

import App from './App';
import { name as appName } from './app.json';

AppRegistry.registerComponent(appName, () => App);
