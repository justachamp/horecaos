import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { TPipe } from '../../core/i18n/t.pipe';

/**
 * The Customers section's own shell (IA §5): a small top-level switch between
 * **Клиенты** (5.1/5.2, the default — `CustomersPage` keeps its own nested
 * `:accountId` dock unchanged) and the two tier-2 screens this wave adds,
 * **Сегменты** (5.3) and **Отзывы** (5.4) — the same "wrap otherwise-unrelated
 * screens in a small tab strip" shape `staff-shell.ts` and `finance-shell.ts`
 * already establish, chosen here for the same reason `staff-shell.ts` gives:
 * Segments must not render docked beside the customer list the way a
 * customer's own detail pane does, because a segment builder needs its own
 * width, not the ~480px the docked layout leaves it.
 */
@Component({
  selector: 'q-customers-shell',
  imports: [TPipe, RouterLink, RouterLinkActive, RouterOutlet],
  templateUrl: './customers-shell.html',
  styleUrl: './customers-shell.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CustomersShell {}
