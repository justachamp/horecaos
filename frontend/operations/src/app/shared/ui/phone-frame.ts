import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * A phone screen, framed — `q-phone-frame` (row `X.28`), the base of the
 * frame family (`frontend-information-architecture.md` §X's "Button, Icon,
 * DataTable, EmptyState, StatusPill, Input, Select, Tabs, Card, PhoneFrame,
 * IconChip" list — the one component in it operations never built).
 *
 * **Content-only, never data.** This component knows nothing about an order,
 * a template or a channel — it projects whatever the caller renders inside
 * it. `T22`'s own consumer for the whole frame family is deliberately just a
 * sample storefront product card: `AggregatorCardFrame` and `KioskFrame`
 * have nothing real to preview until ADR 0040's marketplace projection and a
 * kiosk configuration exist (both deferred), so shipping these against
 * invented aggregator- or kiosk-shaped data would be a preview of a fiction.
 * `TemplateEditor` (`P36`) is this component's first *data*-driven consumer,
 * previewing an order-status message.
 *
 * **The one exception to "flat-square."** `--q-radius: 0` is the console's
 * own chrome — the operator's UI. This frame renders a *different* surface's
 * chrome, and a phone silhouette with square corners reads as a mistake, not
 * as restraint; the bezel radius below is the one deliberate departure.
 */
@Component({
  selector: 'q-phone-frame',
  templateUrl: './phone-frame.html',
  styleUrl: './phone-frame.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PhoneFrame {
  /** Shown under the frame — "Как увидит клиент", already translated. Optional. */
  readonly caption = input<string | null>(null);
}
