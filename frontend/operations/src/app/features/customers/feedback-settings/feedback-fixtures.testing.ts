import { FeedbackTag, PromptPolicy, RecoveryPolicy, TagLibrary } from './feedback-settings-api';

export function tag(overrides: Partial<FeedbackTag> = {}): FeedbackTag {
  return {
    id: 'tag-1',
    code: 'food_cold',
    category: 'FOOD',
    sentiment: 'NEGATIVE',
    iconAssetId: null,
    iconUrl: null,
    sortOrder: 20,
    status: 'ACTIVE',
    labels: { ru: 'Остыла еда', 'uz-Latn': 'Taom sovigan', en: 'Food arrived cold' },
    used: false,
    version: 1,
    ...overrides,
  };
}

export function library(tags: readonly FeedbackTag[] = [tag()]): TagLibrary {
  return {
    tags,
    locales: ['ru', 'uz-Latn', 'en'],
    defaultLocale: 'ru',
    maximumActiveTags: 40,
    iconAspectRatio: '3:2',
    iconMaximumBytes: 1_048_576,
  };
}

export function promptPolicy(overrides: Partial<PromptPolicy> = {}): PromptPolicy {
  return {
    sendWindow: { start: '09:00', end: '21:00' },
    inlineWindowDays: 7,
    rows: [
      {
        fulfillmentMode: '*',
        channel: 'TELEGRAM',
        enabled: true,
        delayMinutes: 30,
        giveUpAfterHours: 6,
      },
      {
        fulfillmentMode: '*',
        channel: 'SMS',
        enabled: false,
        delayMinutes: 30,
        giveUpAfterHours: 6,
      },
    ],
    isDefault: true,
    winningScope: null,
    policyId: null,
    policyVersion: 0,
    versionAtScope: 0,
    levels: [],
    ...overrides,
  };
}

export function recoveryPolicy(overrides: Partial<RecoveryPolicy> = {}): RecoveryPolicy {
  return {
    autoOpenMaxRating: 2,
    firstResponseMinutes: 240,
    resolutionMinutes: 4320,
    alertOnOpen: false,
    alertOnBreach: false,
    isDefault: true,
    winningScope: null,
    policyId: null,
    policyVersion: 0,
    versionAtScope: 0,
    levels: [],
    ...overrides,
  };
}
