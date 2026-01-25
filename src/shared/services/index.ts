/**
 * Services barrel export
 *
 * This file re-exports all service-related exports for convenient importing.
 */

export { createMatrixService, type CreateMatrixServiceOptions } from './createMatrixService';
export { MatrixService } from './MatrixService';
export { MatrixServiceAdapter } from './MatrixServiceAdapter';
export type { CredentialStorage } from './CredentialStorage';
export type { Logger, MatrixRoom, FamilyMember, VoiceMessage } from './MatrixService';
