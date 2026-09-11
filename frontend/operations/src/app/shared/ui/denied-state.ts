import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';

/**
 * A wall: the operator's grant does not reach this screen (ADR 0101, row `X.16`).
 *
 * Sixty-five templates carry a hand-written `denied` branch today and the gap
 * map's complaint about them is exact — "a denied operator gets a different
 * sentence on every screen and no route to whoever could grant the capability".
 * Most of them render `'settings.scope.denied'`, one line, with no statement of
 * *what* was refused and no idea who could fix it, which leaves the operator
 * with nothing to do but ask someone and hope they guess right.
 *
 * **The capability name is rendered, in mono, as data.** `ORDER_CANCEL` is not
 * a translated string and must not become one: it is the exact token a manager
 * types into the role editor, and a screen that shows «Отмена заказа» instead
 * sends the operator to ask for something nobody can look up. It is also not
 * PII and not a secret — a capability constant is code, and ADR 0025's whole
 * model is that the set is public and the grant is not.
 *
 * **This component never resolves a capability.** It takes a *name*, as a
 * string, and renders it. Deciding whether the operator holds it happened
 * before this component was rendered, in the screen that caught the 403.
 */
@Component({
  selector: 'q-denied-state',
  imports: [TPipe],
  templateUrl: './denied-state.html',
  styleUrls: ['./state.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DeniedState {
  /**
   * The capability constant that was refused — `ORDER_CANCEL`, `STAFF_READ`.
   * `null` when the screen genuinely does not know which one the server checked,
   * which is most of today's call sites until each owning wave plumbs it
   * through.
   */
  readonly capability = input<string | null>(null);

  /**
   * Who can grant it. Defaults to the sentence that is true for every tenant:
   * a manager with the staff-roles capability. A screen that knows better —
   * a platform-only capability, say — overrides it.
   */
  readonly askKey = input<MessageKey>('ui.denied.ask');
}
