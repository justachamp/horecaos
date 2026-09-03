import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { I18nService } from '../../core/i18n/i18n.service';

/**
 * IA 3.5 Sandbox & contract tests -- not built.
 *
 * ADR 0007 ("Camel route foundation and provider contract testing") stays
 * `Partial`, and the "contract testing" half it names is nowhere in the
 * codebase: no recorded-fixture replay harness, no sandbox run, no
 * pass/fail report an adapter is checked against before rollout. `POS sync
 * runs` (IA 3.5's nearest neighbour) compare a real provider's live catalogue
 * against HorecaOS, not a recorded fixture against an adapter in isolation --
 * a different question with a different answer. Running an adapter against
 * recorded provider fixtures before rollout is a real testing subsystem of
 * its own, not a small addition, so it stays unbuilt this wave.
 */
@Component({
  selector: 'app-sandbox-contract-tests',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1 class="q-title">{{ i18n.t('nav.sandboxContractTests') }}</h1>
    <section class="notice">
      <h2 class="q-subhead">{{ i18n.t('state.notBuilt.title') }}</h2>
      <p class="q-body-sm body">{{ i18n.t('sandboxContractTests.notBuilt.body') }}</p>
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
export class SandboxContractTests {
  protected readonly i18n = inject(I18nService);
}
