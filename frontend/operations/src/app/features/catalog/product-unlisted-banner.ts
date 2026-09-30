import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';

/** What a list-everywhere click left unresolved for one variant. */
export type ListingNotice =
  | { readonly kind: 'partial'; readonly listed: number; readonly candidate: number }
  | { readonly kind: 'recountFailed' };

/** One variant that is offered somewhere yet not listed at every branch that could list it. */
export interface UnlistedRow {
  readonly variantId: string;
  readonly count: number;
  /** The variant's name in the editing locale. */
  readonly label: string;
}

/**
 * The warning on the product editor's Availability tab: variants that some
 * branches do not list, with a "list everywhere" button per variant and, after a
 * click, what it left unresolved.
 *
 * Presentation only. The counts, the request and its outcome belong to the editor
 * (`ProductEditorPage`); a partial outcome or an unreadable recount is never
 * silent, which is why the notices are inputs rather than something this
 * component decides. It is its own component so the banner's rules do not count
 * against the editor's component-style budget.
 */
@Component({
  selector: 'q-product-unlisted-banner',
  imports: [TPipe],
  templateUrl: './product-unlisted-banner.html',
  styleUrl: './product-unlisted-banner.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductUnlistedBanner {
  readonly rows = input.required<readonly UnlistedRow[]>();
  /** The variants whose list-everywhere request is in flight; each button disables on its own. */
  readonly pendingVariantIds = input.required<ReadonlySet<string>>();
  readonly notices = input.required<Readonly<Record<string, ListingNotice>>>();

  readonly listRequested = output<string>();
}
