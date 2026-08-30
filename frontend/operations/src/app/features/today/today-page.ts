import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { formatMoney } from '../../core/format/money';

/**
 * The landing route.
 *
 * The shift summary that belongs here is not built. What this page does carry is
 * a live demonstration that the token sheet and the money rule are actually
 * wired up rather than merely present in the repository — the type scale, the
 * hairline card, the tabular figures, and an amount in whole som.
 *
 * Delete this component's body when the real Today screen lands. It is a proof,
 * not a design.
 */
@Component({
  selector: 'q-today-page',
  imports: [TPipe],
  template: `
    <div class="today">
      <h1 class="q-title">{{ 'shell.nav.today' | t }}</h1>

      <div class="card">
        <div class="card__label q-caption">UZS</div>
        <!-- 125 000 minor units is 125 000 som, not 1 250. See core/format/money.ts. -->
        <div class="card__value q-data-lg">{{ sample }}</div>
      </div>

      <p class="q-body-sm note">
        The shift summary is not built. docs/operations-spec/orders.md and the prototype at
        frontend/prototypes/operations own what belongs here.
      </p>
    </div>
  `,
  styles: `
    .today {
      padding: 24px;
    }
    h1 {
      margin: 0 0 16px;
    }
    /* Hairline elevation, 0px corners. No shadow, no gradient. */
    .card {
      display: inline-block;
      padding: 16px 20px;
      background: var(--q-canvas);
      border: 1px solid var(--q-hairline);
      border-radius: var(--q-radius);
    }
    .card__label {
      color: var(--q-ink-subtle);
      text-transform: uppercase;
      letter-spacing: 0.32px;
    }
    .card__value {
      margin-top: 4px;
      color: var(--q-ink);
    }
    .note {
      margin: 16px 0 0;
      color: var(--q-ink-muted);
      max-width: 60ch;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TodayPage {
  private readonly i18n = inject(I18n);

  protected get sample(): string {
    return formatMoney({ amountMinor: 125_000, currency: 'UZS' }, this.i18n.locale(), {
      withUnit: true,
    });
  }
}
