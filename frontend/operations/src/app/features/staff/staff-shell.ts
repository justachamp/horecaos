import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { TPipe } from '../../core/i18n/t.pipe';

/**
 * The Staff section's own shell (operations IA §9.1): a small top-level
 * switch between **Люди** (staff-and-access.md §2, the default) and
 * **Должности** (§5), the same "a shell wrapping otherwise-unrelated
 * screens" shape `settings-shell.ts` already establishes for this app —
 * needed here because Должности must not render docked beside the Люди
 * list the way a person's own Карточка does (`staff-page.ts`'s own
 * `<router-outlet>` is for exactly one thing: `:subjectId`).
 */
@Component({
  selector: 'q-staff-shell',
  imports: [TPipe, RouterLink, RouterLinkActive, RouterOutlet],
  templateUrl: './staff-shell.html',
  styleUrl: './staff-shell.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StaffShell {}
