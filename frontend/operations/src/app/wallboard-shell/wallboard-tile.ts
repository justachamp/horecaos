import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** `muted` is the tone `wallboard-shell.ts` gives the "cancelled" counter — present, but not the number a supervisor's eye should land on first. */
export type WallboardTileTone = 'default' | 'muted';

/**
 * One oversized counter for the wallboard (IA `X/X.3`), set at the TV-distance
 * type step — `.q-display-tv` in `frontend/design-tokens/tokens.css` — rather
 * than the desk-distance `.q-display` `today-page.ts`'s `counter-card` uses
 * for the identical number. Same data, a rung further up the scale, because
 * this one is read from across the pass instead of from a desk an arm's
 * length away.
 *
 * A pure presentational wrapper: no polling, no formatting beyond what the
 * caller passes in, no state of its own. `wallboard-shell.ts` is the only
 * caller today.
 */
@Component({
  selector: 'q-wallboard-tile',
  templateUrl: './wallboard-tile.html',
  styleUrl: './wallboard-tile.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WallboardTile {
  readonly value = input.required<number>();
  readonly label = input.required<string>();
  readonly tone = input<WallboardTileTone>('default');
}
