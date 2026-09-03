import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { TPipe } from '../../core/i18n/t.pipe';

/**
 * The Catalog section's frame: a sub-nav strip over a routed child.
 *
 * IA §4's P-tier screens (Products, Product editor, Categories, Menus,
 * Catalog sync & import) and wave 38's tier-2 additions — 4.6 Publication &
 * channel readiness, 4.7 Reference data (honest not-built), 4.8 Price list,
 * 4.9 Auto-add rules (honest not-built, zero backend) — share this shell.
 * There is no existing precedent in this app for a multi-screen section, so
 * this is a plain sub-nav rather than a reuse of the order board's docking
 * pattern: these screens are siblings an author moves between, not a list
 * and its own detail.
 *
 * The Product editor (`/catalog/products/:productId`) is deliberately **not**
 * a child of this shell's `products` route — catalog.md §4.2 specifies it as
 * a full page, not a drawer docked beside the list, so it replaces this
 * shell entirely rather than rendering inside it. See `app.routes.ts`.
 */
@Component({
  selector: 'q-catalog-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TPipe],
  templateUrl: './catalog-shell.html',
  styleUrl: './catalog-shell.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CatalogShell {}
