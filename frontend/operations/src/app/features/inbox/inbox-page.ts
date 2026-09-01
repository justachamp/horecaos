import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';

import { TPipe } from '../../core/i18n/t.pipe';
import { InboxList } from './inbox-list';

/**
 * The inbox's frame — a sibling of `OrdersPage`, and for the same reason: a
 * live queue with a detail docked beside it rather than replacing it, so an
 * operator reading one conversation still sees the next one needing
 * attention arrive in the list behind it. See `OrdersPage`'s own doc for why
 * that rules out a modal or a full-page navigation.
 */
@Component({
  selector: 'q-inbox-page',
  imports: [RouterOutlet, TPipe, InboxList],
  templateUrl: './inbox-page.html',
  styleUrl: './inbox-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InboxPage {
  /** Whether a detail route is active — see `OrdersPage.docked`'s own doc for why this is a signal, not a binding. */
  protected readonly docked = signal(false);
}
