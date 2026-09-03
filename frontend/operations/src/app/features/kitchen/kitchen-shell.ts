import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { TPipe } from '../../core/i18n/t.pipe';

/**
 * The Kitchen section's frame: a sub-nav strip over a routed child.
 *
 * IA §2's P-tier screens — 2.1 Kitchen queue (KDS) and 2.5 Stop list — and
 * wave 38's tier-2 additions — 2.2 Buffer, 2.3 Expo/handover, 2.4 Display
 * board (VDU) — share the same "siblings an author moves between" shape
 * `catalog-shell.ts` already established for Catalog's screens. 2.6
 * Capacity & buffer settings stays tier 3 and is not one of these tabs.
 *
 * **On the "device shell" in IA §2's own heading.** The spec calls for a
 * third template — a fullscreen, no-sidebar, touch-first shell for the KDS —
 * beside the operator console and the wallboard (IA Part 4, "Template-level
 * gaps"). No such shell exists in this design system yet, and building one is
 * a template-level investment, not a screen-level one. This wave renders
 * Kitchen inside the existing operator console shell instead, which is the
 * same trade-off `products-page.ts`'s own doc names for missing backend: real
 * function over an unbuilt affordance. See the wave's final report.
 */
@Component({
  selector: 'q-kitchen-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TPipe],
  templateUrl: './kitchen-shell.html',
  styleUrl: './kitchen-shell.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class KitchenShell {}
