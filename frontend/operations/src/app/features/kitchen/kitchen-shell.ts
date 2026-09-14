import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { TPipe } from '../../core/i18n/t.pipe';

/**
 * The Kitchen section's frame: a sub-nav strip over a routed child.
 *
 * IA §2's P-tier screens — 2.1 Kitchen queue (KDS) and 2.5 Stop list — and
 * wave 38's tier-2 additions — 2.2 Buffer, 2.3 Expo/handover, 2.4 Display
 * board (VDU) — share the same "siblings an author moves between" shape
 * `catalog-shell.ts` already established for Catalog's screens. 2.6 Capacity
 * & buffer settings joined this wave (43): tier 3, but a real one-card build
 * over ADR 0041's `kitchen.station_capacity` — see `CapacityPage`'s own doc
 * for the honest split between what is built and what a cook-count output
 * would still need.
 *
 * **On the "device shell" in IA §2's own heading.** The spec calls for a
 * third template — a fullscreen, no-sidebar, touch-first shell for the KDS —
 * beside the operator console and the wallboard (IA Part 4, "Template-level
 * gaps"). Built by wave P17 as `../../device/device-shell.ts`, a top-level
 * route sibling of this console's own `Shell` (`/device`, not `/kitchen/*`)
 * — a kitchen tablet never enters through this shell at all, since it
 * authenticates with its own ADR 0079 device credential, never a staff
 * Keycloak session. What *is* new here, same wave: `devices` (IA row
 * `2/X.2`) — the manager's own side of ADR 0079's pairing handshake, listing
 * enrolled devices and approving the typed user code a new screen shows.
 */
@Component({
  selector: 'q-kitchen-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TPipe],
  templateUrl: './kitchen-shell.html',
  styleUrl: './kitchen-shell.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class KitchenShell {}
