import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { BrandScope } from '../../../core/api/catalog-paths';
import { command } from '../../../core/api/idempotency';
import { feedbackSettingsPaths } from '../../../core/api/reviews-paths';

export type TagCategory = 'FOOD' | 'SERVICE' | 'COURIER' | 'DELIVERY_TIME' | 'OTHER';
export type TagSentiment = 'POSITIVE' | 'NEGATIVE';
export type TagStatus = 'ACTIVE' | 'RETIRED';

export const TAG_CATEGORIES: readonly TagCategory[] = [
  'FOOD',
  'SERVICE',
  'COURIER',
  'DELIVERY_TIME',
  'OTHER',
];

/** Mirrors `OperationsFeedbackSettingsController.TagResponse`. */
export interface FeedbackTag {
  readonly id: string;
  readonly code: string;
  readonly category: TagCategory;
  readonly sentiment: TagSentiment;
  readonly iconAssetId: string | null;
  readonly iconUrl: string | null;
  readonly sortOrder: number;
  readonly status: TagStatus;
  /** Locale to wording, in every locale it was written in. */
  readonly labels: Readonly<Record<string, string>>;
  /** Whether any review carries the tag: its code, category and sentiment are then frozen. */
  readonly used: boolean;
  readonly version: number;
}

/** Mirrors `OperationsFeedbackSettingsController.TagLibraryResponse`. */
export interface TagLibrary {
  readonly tags: readonly FeedbackTag[];
  /** The set the editor offers: the union of the tenant's brands' languages. */
  readonly locales: readonly string[];
  readonly defaultLocale: string;
  readonly maximumActiveTags: number;
  readonly iconAspectRatio: string;
  readonly iconMaximumBytes: number;
}

export interface NewTagInput {
  readonly code: string;
  readonly category: TagCategory;
  readonly sentiment: TagSentiment;
  readonly iconAssetId: string | null;
  readonly sortOrder: number | null;
  readonly labels: Readonly<Record<string, string>>;
}

export interface TagEditInput {
  readonly category: TagCategory;
  readonly sentiment: TagSentiment;
  readonly iconAssetId: string | null;
  readonly sortOrder: number;
  readonly status: TagStatus;
  /** Only the locales the editor showed: every other locale keeps its wording. */
  readonly labels: Readonly<Record<string, string>> | null;
}

export type FulfilmentMode = 'DELIVERY' | 'PICKUP' | 'DINE_IN' | '*';
export type PromptChannel = 'TELEGRAM' | 'SMS' | 'EMAIL' | 'PUSH' | '*';

export const PROMPT_MODES: readonly FulfilmentMode[] = ['*', 'DELIVERY', 'PICKUP', 'DINE_IN'];
export const PROMPT_CHANNELS: readonly PromptChannel[] = ['*', 'TELEGRAM', 'SMS', 'EMAIL', 'PUSH'];

export interface PromptRow {
  readonly fulfillmentMode: FulfilmentMode;
  readonly channel: PromptChannel;
  readonly enabled: boolean;
  readonly delayMinutes: number;
  readonly giveUpAfterHours: number;
}

export interface SendWindow {
  readonly start: string;
  readonly end: string;
}

/** One level of the inheritance chain a policy resolves through. */
export interface PolicyLevel {
  readonly scopeType: 'PLATFORM' | 'TENANT' | 'BRAND' | 'LOCATION';
  readonly authored: boolean;
}

interface PolicyView {
  readonly isDefault: boolean;
  readonly winningScope: 'TENANT' | 'BRAND' | 'LOCATION' | null;
  readonly policyId: string | null;
  readonly policyVersion: number;
  /** The version authored at exactly this scope: what a save is checked against (`If-Match`). */
  readonly versionAtScope: number;
  readonly levels: readonly PolicyLevel[];
}

/** Mirrors `OperationsFeedbackSettingsController.PromptPolicyResponse`. */
export interface PromptPolicy extends PolicyView {
  readonly sendWindow: SendWindow | null;
  readonly inlineWindowDays: number;
  readonly rows: readonly PromptRow[];
}

export interface PromptPolicyInput {
  readonly sendWindow: SendWindow | null;
  readonly inlineWindowDays: number;
  readonly rows: readonly PromptRow[];
  readonly reason: string;
}

/** Mirrors `OperationsFeedbackSettingsController.RecoveryPolicyResponse`. */
export interface RecoveryPolicy extends PolicyView {
  readonly autoOpenMaxRating: number;
  readonly firstResponseMinutes: number;
  readonly resolutionMinutes: number;
  readonly alertOnOpen: boolean;
  readonly alertOnBreach: boolean;
}

export interface RecoveryPolicyInput {
  readonly autoOpenMaxRating: number;
  readonly firstResponseMinutes: number;
  readonly resolutionMinutes: number;
  readonly alertOnOpen: boolean;
  readonly alertOnBreach: boolean;
  readonly reason: string;
}

/** Which level a policy is read or written at: the tenant, or one of its brands. */
export type PolicyTarget =
  | { readonly kind: 'TENANT'; readonly tenantId: string }
  | { readonly kind: 'BRAND'; readonly scope: BrandScope };

function promptPath(target: PolicyTarget): string {
  return target.kind === 'TENANT'
    ? feedbackSettingsPaths.promptPolicy(target.tenantId)
    : feedbackSettingsPaths.brandPromptPolicy(target.scope);
}

function recoveryPath(target: PolicyTarget): string {
  return target.kind === 'TENANT'
    ? feedbackSettingsPaths.recoveryPolicy(target.tenantId)
    : feedbackSettingsPaths.brandRecoveryPolicy(target.scope);
}

/**
 * Feedback settings (ADR 0159, row 5.5): the tag library, when a customer is asked to rate an
 * order, and which reviews open a service-recovery case. The visibility settings are ordinary
 * ADR 0030 configuration values and go through `ConfigurationApi`.
 */
@Injectable({ providedIn: 'root' })
export class FeedbackSettingsApi {
  private readonly api = inject(ApiClient);

  async library(tenantId: string): Promise<TagLibrary> {
    const result = await firstValueFrom(
      this.api.get<TagLibrary>(feedbackSettingsPaths.tags(tenantId)),
    );
    return result.value;
  }

  createTag(tenantId: string, input: NewTagInput): Promise<FeedbackTag> {
    return firstValueFrom(
      this.api.post<NewTagInput, FeedbackTag>(feedbackSettingsPaths.tags(tenantId), command(input)),
    );
  }

  importStarterPack(tenantId: string): Promise<readonly FeedbackTag[]> {
    return firstValueFrom(
      this.api.post<undefined, readonly FeedbackTag[]>(
        feedbackSettingsPaths.starterPack(tenantId),
        command(undefined),
      ),
    );
  }

  updateTag(
    tenantId: string,
    tagId: string,
    input: TagEditInput,
    expectedVersion: number,
  ): Promise<FeedbackTag> {
    return firstValueFrom(
      this.api.put<TagEditInput, FeedbackTag>(
        feedbackSettingsPaths.tag(tenantId, tagId),
        command(input),
        { expectedVersion },
      ),
    );
  }

  async promptPolicy(target: PolicyTarget): Promise<PromptPolicy> {
    const result = await firstValueFrom(this.api.get<PromptPolicy>(promptPath(target)));
    return result.value;
  }

  savePromptPolicy(
    target: PolicyTarget,
    input: PromptPolicyInput,
    versionAtScope: number,
  ): Promise<PromptPolicy> {
    return firstValueFrom(
      this.api.put<PromptPolicyInput, PromptPolicy>(promptPath(target), command(input), {
        expectedVersion: versionAtScope,
      }),
    );
  }

  async recoveryPolicy(target: PolicyTarget): Promise<RecoveryPolicy> {
    const result = await firstValueFrom(this.api.get<RecoveryPolicy>(recoveryPath(target)));
    return result.value;
  }

  saveRecoveryPolicy(
    target: PolicyTarget,
    input: RecoveryPolicyInput,
    versionAtScope: number,
  ): Promise<RecoveryPolicy> {
    return firstValueFrom(
      this.api.put<RecoveryPolicyInput, RecoveryPolicy>(recoveryPath(target), command(input), {
        expectedVersion: versionAtScope,
      }),
    );
  }
}
