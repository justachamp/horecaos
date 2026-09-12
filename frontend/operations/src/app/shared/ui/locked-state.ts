import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';

/**
 * An upsell: the feature exists, the tenant's plan does not include it
 * (ADR 0101, row `X.16`).
 *
 * The IA's line is the specification — "one is an upsell, one is a wall" — and
 * today the console cannot tell them apart: `ENTITLEMENT_REQUIRED` and
 * `INSUFFICIENT_CAPABILITY` are distinct server error codes that both fall
 * through `describeApiError` into the same flat sentence, so a plan-locked
 * feature looks exactly like a broken one.
 *
 * **It ships with no buy button, and that is a finding rather than an
 * omission.** There is no tenant-scoped purchase endpoint to wire one to — all
 * of `CommercialModuleController` is platform-scoped — so a CTA here would be a
 * button that cannot do anything. The slot exists for the wave that lands that
 * endpoint; until then the lock is honest about being a lock and says who to
 * ask.
 */
@Component({
  selector: 'q-locked-state',
  imports: [TPipe],
  templateUrl: './locked-state.html',
  styleUrls: ['./state.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LockedState {
  /**
   * The commercial module's own code — `LOYALTY`, `CALL_CENTRE`. Rendered as
   * data, for the same reason `q-denied-state` renders a capability name: it is
   * the string whoever sells the plan will search for.
   */
  readonly module = input<string | null>(null);

  /** Who to ask. Defaults to "the tenant's owner, through the platform". */
  readonly askKey = input<MessageKey>('ui.locked.ask');
}
