import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { RouterLink } from '@angular/router';

import { EditableScopeType } from '../../../core/api/configuration';
import { ApiError } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import {
  DispatchRule,
  DispatchRulesApi,
  DispatchScope,
  SourcingMode,
} from '../../delivery/dispatch-rules-api';
import { MODE_LABELS } from '../../delivery/dispatch-rules-model';
import { describeApiError } from '../../orders/order-errors';

/**
 * Card 3 of the order policy, "Automation" (settings.md §10.3): a read-only summary of the dispatch rules
 * in force at the scope the settings bar is on, and the way to the screen that edits them (ADR 0142).
 *
 * Auto-dispatch, the provider cascade, the merge radius and the unpaid-order timeout are not settings here
 * on purpose -- Delever duplicates near-identical configuration across five provider pages and so cannot
 * express a fallback at all -- they live in one rule document under Delivery. This card exists so someone
 * on the settings screen is told what is in force and sent to the right place, not left to wonder why the
 * fields are missing.
 *
 * Read-only by construction: it holds no draft and calls only the rules read.
 */
@Component({
  selector: 'q-dispatch-rules-summary-card',
  imports: [TPipe, RouterLink],
  templateUrl: './dispatch-rules-summary-card.html',
  styleUrl: './dispatch-rules-summary-card.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DispatchRulesSummaryCard {
  private readonly api = inject(DispatchRulesApi);
  private readonly i18n = inject(I18n);

  readonly tenantId = input.required<string | null>();
  readonly scopeType = input.required<EditableScopeType>();
  readonly brandId = input.required<string | null>();
  readonly locationId = input.required<string | null>();

  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly rules = signal<readonly DispatchRule[]>([]);
  protected readonly builtIn = signal(true);

  private generation = 0;

  constructor() {
    effect(() => {
      const tenantId = this.tenantId();
      const level = this.scopeType();
      const brandId = this.brandId();
      const locationId = this.locationId();
      untracked(() => void this.load(tenantId, level, brandId, locationId));
    });
  }

  protected modeLabel(mode: SourcingMode): MessageKey {
    return MODE_LABELS[mode];
  }

  private async load(
    tenantId: string | null,
    level: EditableScopeType,
    brandId: string | null,
    locationId: string | null,
  ): Promise<void> {
    if (tenantId === null) {
      return;
    }
    const generation = ++this.generation;
    this.loading.set(true);
    this.error.set(null);
    const scope: DispatchScope = {
      tenantId,
      ...(level !== 'TENANT' && brandId ? { brandId } : {}),
      ...(level === 'LOCATION' && locationId ? { locationId } : {}),
    };
    try {
      const { value } = await this.api.rules(scope);
      if (generation === this.generation) {
        this.rules.set(value.rules);
        this.builtIn.set(value.isBuiltIn);
      }
    } catch (failure) {
      if (generation === this.generation) {
        this.error.set(
          failure instanceof ApiError
            ? describeApiError(failure, (key, values) => this.i18n.t(key, values))
            : this.i18n.t('error.unknown.noReference'),
        );
      }
    } finally {
      if (generation === this.generation) {
        this.loading.set(false);
      }
    }
  }
}
