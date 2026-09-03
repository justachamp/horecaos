import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { I18nService } from '../../core/i18n/i18n.service';

/**
 * IA 4.4 Error taxonomy -- not built.
 *
 * No single registry maps a raw provider failure to the operator-legible
 * causes and fixes this row names: unmapped product, unmapped payment type,
 * inactive product in POS, expired credential, venue mismatch. What exists
 * are three narrower, unrelated vocabularies for different purposes --
 * `FailureCategory` (ADR 0006 retry semantics: transient vs. terminal),
 * `ProviderExceptionClassifier`'s transport-level codes (`PROVIDER_TIMEOUT`,
 * `PROVIDER_AUTHENTICATION`, ...), and `RejectionCode` (ADR 0040 aggregator
 * order intake: `UNKNOWN_VENUE`, `CURRENCY_MISMATCH`, ...) -- and none of them
 * is the operator-facing cause-and-fix mapping this row asks for. Building
 * that registry is a genuine new subsystem, not a small addition on top of
 * an existing one, so it stays unbuilt this wave.
 */
@Component({
  selector: 'app-error-taxonomy',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1 class="q-title">{{ i18n.t('nav.errorTaxonomy') }}</h1>
    <section class="notice">
      <h2 class="q-subhead">{{ i18n.t('state.notBuilt.title') }}</h2>
      <p class="q-body-sm body">{{ i18n.t('errorTaxonomy.notBuilt.body') }}</p>
    </section>
  `,
  styles: `
    .notice {
      margin-top: 24px;
      background: var(--q-canvas);
      border: 1px solid var(--q-hairline);
      padding: 24px;
      max-width: 640px;
    }

    .body {
      color: var(--q-ink-muted);
      margin-top: 8px;
    }
  `,
})
export class ErrorTaxonomy {
  protected readonly i18n = inject(I18nService);
}
