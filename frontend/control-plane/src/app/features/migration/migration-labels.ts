import { MessageKey } from '../../core/i18n/messages.en';

/** Catalogue keys for the migration vocabulary, so every screen names a state the same way. */
export const capabilityKey = (capability: string): MessageKey => `migration.capability.${capability}` as MessageKey;
export const stateKey = (state: string): MessageKey => `migration.state.${state}` as MessageKey;
export const runTypeKey = (runType: string): MessageKey => `migration.runType.${runType}` as MessageKey;
export const runStatusKey = (status: string): MessageKey => `migration.runStatus.${status}` as MessageKey;
export const programStatusKey = (status: string): MessageKey => `migration.programStatus.${status}` as MessageKey;
export const resolutionKey = (code: string): MessageKey => `migration.resolution.${code}` as MessageKey;
