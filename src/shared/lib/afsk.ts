/**
 * AFSK Modem Codec - DEPRECATED
 *
 * This module has been replaced by MFSK for better robustness.
 * Re-exports from mfsk.ts for backwards compatibility.
 *
 * @deprecated Use mfsk.ts directly for new code
 */

export {
  encodeMfsk as encodeAfsk,
  decodeMfsk as decodeAfsk,
  DEFAULT_CONFIG,
  clearMfskDebugLog as clearAfskDebugLog,
  getMfskDebugLog as getAfskDebugLog,
  type MfskConfig as AfskConfig,
} from './mfsk.js';
