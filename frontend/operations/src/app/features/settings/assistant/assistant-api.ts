import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { settingsPaths } from '../../../core/api/settings-paths';

/**
 * Mirrors `AssistantUsageController.UsageResponse` (ADR 0069): what the assistant has done and cost
 * this calendar month (UTC), and what stands between it and answering at all.
 */
export interface AssistantUsageResponse {
  /** `2026-10`. */
  readonly month: string;
  readonly turns: number;
  readonly answered: number;
  readonly refused: number;
  readonly escalated: number;
  readonly declined: number;
  readonly servedFromCache: number;
  /** Millionths of a US dollar: one cent is 10 000. Integer arithmetic only. */
  readonly costUsdMicros: number;
  /** What HorecaOS will pay the AI provider for this tenant this month, in US cents. */
  readonly ceilingUsdCents: number;
  /** True exactly when the assistant is refusing for that reason. */
  readonly ceilingReached: boolean;
  /** The plan includes the assistant and the chat it speaks in (under meter-only this is true for an unbilled tenant). */
  readonly entitled: boolean;
  /** `assistant.enabled` at company scope; a brand may differ. */
  readonly switchedOn: boolean;
  /** HorecaOS has an AI provider connected at all. */
  readonly providerConfigured: boolean;
  readonly publishedKnowledgeEntries: number;
  /** The platform's own first-answer wording by reply language (`en`, `ru`, `uz`), used while a tenant has written none. */
  readonly defaultDisclosure: Readonly<Record<string, string>>;
}

/**
 * The Assistant settings screen's own read (ADR 0069). The switch and the disclosure wording are
 * ordinary ADR 0030 configuration and go through `ConfigurationApi`; only the usage report is the
 * assistant's own.
 */
@Injectable({ providedIn: 'root' })
export class AssistantApi {
  private readonly api = inject(ApiClient);

  /** Month to date, read from the same ledger the spend ceiling is decided from. */
  async usage(tenantId: string): Promise<AssistantUsageResponse> {
    const result = await firstValueFrom(
      this.api.get<AssistantUsageResponse>(settingsPaths.assistantUsage(tenantId)),
    );
    return result.value;
  }
}

/**
 * US cents as a dollar amount for the screen. Integer division and remainder, never a float: a
 * ceiling of 2 500 cents is `$25.00`, and 1 cent short of it is `$24.99`, not `$24.990000000000002`.
 */
export function formatUsdCents(cents: number): string {
  const whole = Math.trunc(cents / 100);
  const remainder = Math.abs(cents % 100);
  return `$${whole}.${String(remainder).padStart(2, '0')}`;
}

/**
 * The month's spend, from millionths of a dollar to cents, rounded UP: once a fraction of a cent has
 * been spent, a screen that rounded down would show the room left under the ceiling as larger than
 * it is, and the one figure it must never overstate is that room.
 */
export function spendCents(costUsdMicros: number): number {
  return Math.ceil(costUsdMicros / 10_000);
}
