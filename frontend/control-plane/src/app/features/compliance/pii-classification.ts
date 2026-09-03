import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { I18nService } from '../../core/i18n/i18n.service';

/**
 * IA 6.4 PII & data classification -- not built.
 *
 * `iam.api.protection` is real -- `DataClass`, `Classified`,
 * `ClassificationScanner`, `EnvelopeFieldProtection` -- but it is
 * ADR 0029's internal correctness machinery: it decides which fields the
 * platform encrypts, and it runs at compile/annotation time against a Java
 * type, not against a queryable registry an operator browses. There is no
 * retention-schedule table, no export-egress audit log, and no DSAR/erasure
 * workflow anywhere in the schema -- the classification a field carries and
 * the operator-facing registry, retention schedule, and erasure workflow
 * this row asks for are different things, and only the first exists. A
 * queryable classification registry with retention and DSAR handling is a
 * genuine new subsystem, not a small addition, so it stays unbuilt this wave.
 */
@Component({
  selector: 'app-pii-classification',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1 class="q-title">{{ i18n.t('nav.piiClassification') }}</h1>
    <section class="notice">
      <h2 class="q-subhead">{{ i18n.t('state.notBuilt.title') }}</h2>
      <p class="q-body-sm body">{{ i18n.t('piiClassification.notBuilt.body') }}</p>
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
export class PiiClassification {
  protected readonly i18n = inject(I18nService);
}
