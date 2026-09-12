import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * A kiosk's own portrait screen, framed — `q-kiosk-frame` (row `X.28`), a
 * `q-phone-frame` sibling, fixed at the 9:16 the IA names for 10.5's idle
 * media.
 *
 * A thicker bezel than a phone's and no notch: a kiosk is a mounted tablet,
 * not a handset. Nothing here reads a real kiosk configuration — none
 * exists yet (`T22`'s own scoping note) — so, like `AggregatorCardFrame`,
 * this frame ships against whatever storefront-shaped content a caller
 * projects, not an invented idle-media layout.
 */
@Component({
  selector: 'q-kiosk-frame',
  templateUrl: './kiosk-frame.html',
  styleUrl: './kiosk-frame.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class KioskFrame {
  /** Shown under the frame, already translated. Optional. */
  readonly caption = input<string | null>(null);
}
