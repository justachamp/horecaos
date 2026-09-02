import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { TPipe } from '../../core/i18n/t.pipe';

/**
 * The Catalog section's frame: a sub-nav strip over a routed child.
 *
 * IA §4 assigns five P-tier screens to Catalog (Products, Product editor,
 * Categories, Menus, Catalog sync & import) — more than one screen, unlike
 * Orders and Inbox, which are a single board with a docked detail. There is
 * no existing precedent in this app for a multi-screen section, so this is a
 * plain sub-nav rather than a reuse of the order board's docking pattern:
 * these four screens are siblings an author moves between, not a list and
 * its own detail.
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
