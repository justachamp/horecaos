import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { TPipe } from '../../core/i18n/t.pipe';

/**
 * The Delivery section's frame: a sub-nav strip over a routed child.
 *
 * IA §3 assigns five P-tier screens to Delivery: 3.1 Dispatch board, 3.6
 * Delivery zones, 3.7 Delivery tariffs, 3.8 Dispatch rules — plus 3.3
 * Couriers, which this application's own `navigation.ts` (pre-dating this
 * wave) already placed as its own top-level rail entry under "People" rather
 * than nested here, so it is not one of this shell's tabs. Same
 * multi-screen-siblings shape `catalog-shell.ts`/`kitchen-shell.ts` already
 * use.
 *
 * `dispatch-rules` routes to the shared `NotBuiltPage`: ADR 0014's own
 * automated sourcing is real, but a provider-agnostic, operator-authored
 * rule engine (conditions → provider/service-tier/fallback, cascade search,
 * loser cancellation, merge radius, unpaid-order timeout) has no schema, no
 * service and no ADR anywhere in this build — see the wave's final report.
 */
@Component({
  selector: 'q-delivery-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TPipe],
  templateUrl: './delivery-shell.html',
  styleUrl: './delivery-shell.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DeliveryShell {}
