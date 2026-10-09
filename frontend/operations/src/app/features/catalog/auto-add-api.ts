import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { BrandScope, catalogPaths } from '../../core/api/catalog-paths';
import { command } from '../../core/api/idempotency';

/**
 * ADR 0158's cart-injection rules (gap-map row 4.9): packaging, cutlery and gift lines a rule adds to a cart,
 * and the simulator that previews them on a hypothetical basket.
 *
 * Control-plane paths on the brand, like every catalog authoring endpoint (see `catalog-paths.ts`). A rule is
 * created as a draft and activated with a replace that says so; only an active rule reaches customers, and only
 * with the next publication. An archived rule is never edited and never deleted.
 */
export type AutoAddKind = 'PLAIN' | 'PRODUCT_TRIGGERED' | 'PORTION_BAND';
export type AutoAddMode = 'INJECT' | 'OFFER';
export type AutoAddStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED';

/** The fulfilment modes a rule may name (ADR 0036). */
export const AUTO_ADD_FULFILLMENT_MODES = ['DELIVERY', 'PICKUP', 'DINE_IN'] as const;

/**
 * The channel system types a rule may name: the cart-built channels only. An aggregator or POS order is never
 * built from a cart, so a rule cannot condition on one (`AUTO_ADD_CHANNEL_NOT_ALLOWED`).
 */
export const AUTO_ADD_CHANNEL_TYPES = [
  'WEB',
  'IOS',
  'ANDROID',
  'TELEGRAM',
  'KIOSK',
  'QR_TABLE',
  'CALL_CENTRE',
] as const;

/** One trigger node: exactly one of the three ids. */
export interface AutoAddTrigger {
  readonly variantId?: string | null;
  readonly productId?: string | null;
  readonly categoryId?: string | null;
}

/** `portionsFrom <= portions < portionsTo`, no upper bound when `portionsTo` is null. */
export interface AutoAddBand {
  readonly portionsFrom: number;
  readonly portionsTo?: number | null;
  readonly addVariantId: string;
  readonly quantity: number;
}

/** Mirrors `AutoAddRuleController.RuleResponse`. */
export interface AutoAddRule {
  readonly ruleId: string;
  readonly code: string;
  readonly kind: AutoAddKind;
  readonly mode: AutoAddMode;
  readonly status: AutoAddStatus;
  readonly addVariantId: string | null;
  readonly quantity: number | null;
  readonly triggerThreshold: number | null;
  readonly repeatPerMultiple: boolean;
  readonly minimumQuantity: number;
  readonly maximumQuantity: number;
  readonly mandatory: boolean;
  readonly fulfillmentModes: readonly string[];
  readonly channelSystemTypes: readonly string[];
  readonly sortOrder: number;
  readonly version: number;
  readonly triggers: readonly AutoAddTrigger[];
  readonly bands: readonly AutoAddBand[];
  readonly locationIds: readonly string[];
  readonly customerText: Readonly<Record<string, string>>;
}

/** What the author states about a rule; every optional field is omitted when the author left it alone. */
export interface AutoAddRuleBody {
  readonly mode?: AutoAddMode;
  readonly addVariantId?: string | null;
  readonly quantity?: number | null;
  readonly triggerThreshold?: number | null;
  readonly repeatPerMultiple?: boolean;
  readonly minimumQuantity?: number;
  readonly maximumQuantity?: number;
  readonly mandatory?: boolean;
  readonly fulfillmentModes: readonly string[];
  readonly channelSystemTypes: readonly string[];
  readonly sortOrder?: number;
  readonly triggers?: readonly AutoAddTrigger[];
  readonly bands?: readonly AutoAddBand[];
  readonly locationIds?: readonly string[];
  readonly customerText?: Readonly<Record<string, string>>;
}

export interface NewAutoAddRule extends AutoAddRuleBody {
  readonly code: string;
  readonly kind: AutoAddKind;
}

export interface ReplaceAutoAddRule extends AutoAddRuleBody {
  /** `DRAFT` or `ACTIVE`; an archived rule is not replaced. */
  readonly status?: 'DRAFT' | 'ACTIVE';
}

export interface SimulationRequest {
  readonly fulfillmentMode: string;
  readonly channelSystemType: string;
  readonly locationId?: string | null;
  readonly lines: readonly { readonly variantId: string; readonly quantity: number }[];
  readonly declinedRuleIds?: readonly string[];
}

/** `OFFER` and `INJECT` write or suggest a line; `SKIPPED` says why not. */
export interface SimulationOutcome {
  readonly ruleId: string;
  readonly ruleCode: string;
  readonly ruleStatus: string;
  readonly outcome: 'INJECT' | 'OFFER' | 'SKIPPED';
  readonly variantId: string | null;
  readonly quantity: number | null;
  readonly reason: string | null;
}

export interface SimulationResult {
  readonly units: number;
  readonly portions: number;
  readonly outcomes: readonly SimulationOutcome[];
}

@Injectable({ providedIn: 'root' })
export class AutoAddApi {
  private readonly api = inject(ApiClient);

  private base(scope: BrandScope): string {
    return `${catalogPaths.base(scope)}/auto-add-rules`;
  }

  async list(scope: BrandScope): Promise<readonly AutoAddRule[]> {
    const result = await firstValueFrom(this.api.get<readonly AutoAddRule[]>(this.base(scope)));
    return result.value ?? [];
  }

  /** Refused 409 when the code is taken; 422 with a `findingCode` for a rule that cannot be stored. */
  create(scope: BrandScope, body: NewAutoAddRule): Promise<AutoAddRule> {
    return firstValueFrom(
      this.api.post<NewAutoAddRule, AutoAddRule>(this.base(scope), command(body)),
    );
  }

  /** `If-Match` carries the version the caller read; a stale one is refused `STALE_VERSION`. */
  replace(scope: BrandScope, rule: AutoAddRule, body: ReplaceAutoAddRule): Promise<AutoAddRule> {
    return firstValueFrom(
      this.api.put<ReplaceAutoAddRule, AutoAddRule>(
        `${this.base(scope)}/${encodeURIComponent(rule.ruleId)}`,
        command(body),
        { expectedVersion: rule.version },
      ),
    );
  }

  archive(scope: BrandScope, rule: AutoAddRule): Promise<AutoAddRule> {
    return firstValueFrom(
      this.api.post<Record<string, never>, AutoAddRule>(
        `${this.base(scope)}/${encodeURIComponent(rule.ruleId)}/archive`,
        command({}),
        { expectedVersion: rule.version },
      ),
    );
  }

  /** Non-mutating: a hypothetical basket through the draft and active rules. Writes nothing. */
  simulate(scope: BrandScope, request: SimulationRequest): Promise<SimulationResult> {
    return firstValueFrom(
      this.api.post<SimulationRequest, SimulationResult>(
        `${this.base(scope)}/simulations`,
        command(request),
      ),
    );
  }
}
