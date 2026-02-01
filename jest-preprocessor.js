/**
 * Custom Jest transformer that strips .js extensions from imports
 *
 * This allows ESM-style imports like:
 *   import { foo } from './module.js';
 *
 * To work with Jest/TypeScript which expects:
 *   import { foo } from './module';
 */

module.exports = {
  process(sourceText, sourcePath, options) {
    // Strip .js extensions from relative imports
    // This regex matches:
    // - from './module.js'
    // - from '../module.js'
    // - import './module.js'
    const processed = sourceText.replace(/(['"])(\.{1,2}\/[^'"]+\.js)\1/g, (match, quote, path) => {
      return `${quote}${path.slice(0, -3)}${quote}`; // Remove .js
    });

    // Delegate to ts-jest for actual TypeScript processing
    const tsjest = require('ts-jest').default;
    return tsjest.createTransformer().process(processed, sourcePath, options);
  },
};
