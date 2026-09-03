import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { TPipe } from '../../core/i18n/t.pipe';

/**
 * The Delivery section's frame: a sub-nav strip over a routed child.
 *
 * IA §3's P-tier screens — 3.1 Dispatch board, 3.6 Delivery zones, 3.7
 * Delivery tariffs, 3.8 Dispatch rules — plus wave 38's tier-2 additions —
 * 3.2 Live map, 3.4 Courier types & rates, 3.5 Shifts & attendance, 3.9
 * Courier policy — share this shell. 3.3 Couriers stays its own top-level
 * rail entry under "People" (`navigation.ts`, pre-dating this wave), so it
 * is not one of these tabs. Same multi-screen-siblings shape
 * `catalog-shell.ts`/`kitchen-shell.ts` already use.
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
