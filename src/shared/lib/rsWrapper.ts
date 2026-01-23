/**
 * Reed-Solomon wrapper module
 * Handles loading of reedsolomon.es library for both ESM and CommonJS
 */

import { createRequire } from 'module';

const require = createRequire(import.meta.url);

// Load the library using require() to avoid ESM import issues
// The reedsolomon.es package has ReedSolomonES as a named export
const rsModule = require('reedsolomon.es/ReedSolomon.js');

export const ReedSolomonES = rsModule.ReedSolomonES || rsModule;
