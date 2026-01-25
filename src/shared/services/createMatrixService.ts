/**
 * Factory for creating Matrix service instances
 *
 * This factory provides a unified interface for creating either the MatrixService
 * or MatrixServiceAdapter based on configuration, allowing easy switching between
 * implementations.
 */

import { MATRIX_CONFIG } from '@shared/config/matrix';

import type { CredentialStorage } from './CredentialStorage';
import type { Logger } from './MatrixService';
import { MatrixService } from './MatrixService';
import { MatrixServiceAdapter } from './MatrixServiceAdapter';

export interface CreateMatrixServiceOptions {
  credentialStorage: CredentialStorage;
  logger?: Logger;
}

/**
 * Create a Matrix service instance based on configuration
 *
 * @param options - Options for creating the service
 * @returns A MatrixService or MatrixServiceAdapter instance
 *
 * @example
 * ```typescript
 * const service = createMatrixService({
 *   credentialStorage: new AsyncCredentialStorage(),
 *   logger: console,
 * });
 * ```
 */
export function createMatrixService(options: CreateMatrixServiceOptions) {
  const { credentialStorage, logger } = options;

  if (MATRIX_CONFIG.implementation === 'wata-client') {
    return new MatrixServiceAdapter(credentialStorage, logger);
  }

  // Default to matrix-js-sdk
  // Note: MatrixService expects MatrixLogger but platforms provide Logger
  // MatrixService internally handles undefined logger, so we pass undefined
  // and let it use the SDK's default logger
  return new MatrixService(credentialStorage, undefined);
}
