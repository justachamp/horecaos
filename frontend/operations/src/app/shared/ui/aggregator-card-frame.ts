import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * How one product looks as an aggregator's own catalogue card —
 * `q-aggregator-card-frame` (row `X.28`), a `q-phone-frame` sibling.
 *
 * Card-shaped, not phone-shaped: 4.6's own preview is a single tile inside
 * Glovo, Wolt, Yandex Eats or Bolt Food's own list, never their whole app
 * chrome. `catalog.md`'s own line on this is the scope: "the preview button
 * renders the storefront projection only, and says so, rather than implying
 * an aggregator's rendering it cannot know" — ADR 0040's real per-aggregator
 * layout is not built, so this frame gives the projected content a plausible
 * card boundary and nothing an aggregator would dispute.
 */
@Component({
  selector: 'q-aggregator-card-frame',
  templateUrl: './aggregator-card-frame.html',
  styleUrl: './aggregator-card-frame.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AggregatorCardFrame {
  /** Shown under the card — "Как увидит клиент на Glovo", already translated. Optional. */
  readonly caption = input<string | null>(null);
}
