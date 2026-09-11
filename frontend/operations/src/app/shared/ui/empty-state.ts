import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';

/**
 * "There is nothing here yet" — and nothing is wrong (ADR 0101, row `X.16`).
 *
 * The first of the three states the IA insists are different, and the only one
 * that is *not* a refusal. An empty list is the normal state of a new tenant's
 * every screen; it wants a sentence and, where one exists, the control that
 * makes it stop being empty.
 *
 * **Distinct from `q-denied-state` and `q-locked-state` by design, and no
 * shared base class.** The IA's whole point about these three is that they are
 * different — "one is an upsell, one is a wall" — and folding them into one
 * component with a `kind` input is how that difference gets lost in a template
 * that reads `kind === 'x'`. They share a stylesheet shape and nothing else.
 */
@Component({
  selector: 'q-empty-state',
  imports: [TPipe],
  templateUrl: './empty-state.html',
  styleUrls: ['./state.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EmptyState {
  /** The headline — "no orders in this tab". */
  readonly titleKey = input.required<MessageKey>();
  /** One optional line under it, saying what would put something here. */
  readonly bodyKey = input<MessageKey | null>(null);
}
