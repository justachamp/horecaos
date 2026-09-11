import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { MessageKey } from '../core/i18n/messages.en';
import { TPipe } from '../core/i18n/t.pipe';
import { DeniedState } from '../shared/ui/denied-state';

/**
 * Where `capability.guard.ts` sends a direct URL into a rail section the
 * operator holds no capability for (operations IA §9.1c).
 *
 * A routed child of the shell rather than a `UrlTree` with no destination:
 * the rail and top bar stay visible, so the refusal reads as "this one
 * section" rather than as the whole console breaking. `capabilityGuard`
 * names the missing capability — and, where it could resolve one, the
 * section's own rail label — in the query string; a query string, not route
 * `data`, because the value is different on every redirect and `data` is
 * fixed per route declaration.
 *
 * Read from the snapshot alone, not a live subscription: a fresh navigation
 * here always carries a fresh `Router.createUrlTree` from `capabilityGuard`,
 * never a same-instance query-param-only change this page would need to
 * react to after it has already rendered.
 *
 * Renders `q-denied-state` for exactly the reason `staff-roles-page.ts`
 * does on a live 403: "make refusal legible" (9.1d) applies the same way to
 * a refusal this client predicted as to one the server actually returned —
 * neither is a flat, capability-less sentence any more.
 */
@Component({
  selector: 'q-access-denied-page',
  imports: [TPipe, RouterLink, DeniedState],
  templateUrl: './access-denied-page.html',
  styleUrl: './access-denied-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AccessDeniedPage {
  private readonly route = inject(ActivatedRoute);

  protected readonly capability: string | null =
    this.route.snapshot.queryParamMap.get('capability');

  /**
   * The denied section's own rail label, if the guard could name one —
   * `navigation.ts`'s `NavItem.label` is already a `MessageKey`, so the
   * guard passes it straight through rather than a second, translated copy.
   */
  protected readonly sectionLabel: MessageKey | null = this.route.snapshot.queryParamMap.get(
    'section',
  ) as MessageKey | null;
}
