import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';

/**
 * The docked order detail.
 *
 * Empty on purpose. `docs/operations-spec/orders.md` §4 specifies this pane at
 * length — the money panel that must refuse to render a total disagreeing with
 * `total_minor`, the three parallel timeline lanes, the masked phone with its
 * separately-audited reveal, and an actions row that renders exactly what the
 * server says is available and *nothing* for what it does not. None of that is
 * here.
 *
 * What is here is the route binding, because it is the part that has to be right
 * from the start: the order id comes from the URL, so the pane is deep-linkable
 * and the browser's back button steps between orders. A supervisor sending
 * somebody a link to one order is the reason.
 */
@Component({
  selector: 'q-order-detail-pane',
  imports: [TPipe],
  template: `
    <div class="pane">
      <p class="q-body-sm empty">{{ 'orders.detail.empty' | t }}</p>
      <p class="q-caption spec">
        docs/operations-spec/orders.md §4 · order
        <span class="q-mono">{{ orderId() }}</span>
      </p>
    </div>
  `,
  styles: `
    .pane {
      padding: 16px 20px;
    }
    .empty {
      margin: 0;
      color: var(--q-ink-muted);
    }
    .spec {
      margin: 8px 0 0;
      color: var(--q-ink-subtle);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderDetailPane {
  /** Bound from the route parameter by `withComponentInputBinding()`. */
  readonly orderId = input.required<string>();
}
