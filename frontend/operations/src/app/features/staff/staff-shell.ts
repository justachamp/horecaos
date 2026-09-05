import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { TPipe } from '../../core/i18n/t.pipe';

/**
 * The Staff section's own shell (IA §9): a small top-level switch between
 * **Люди** (IA 9.1 Users & roles, the default — staff-and-access.md §2),
 * **Должности** (§5), **Активность** (IA 9.3 Activity & audit, wave 39) and,
 * new this wave, **Approvals** (IA 9.4, the maker-checker worklist) — the
 * same "a shell wrapping otherwise-unrelated screens" shape `settings-shell.ts`
 * already establishes for this app, needed here because none of Должности,
 * Активность or Approvals should render docked beside the Люди list the way
 * a person's own Карточка does (`staff-page.ts`'s own `<router-outlet>` is
 * for exactly one thing: `:subjectId`).
 *
 * IA 9.2 People has no tab of its own: its content — the staff directory,
 * branch bindings — is what 9.1's grants-derived list already shows, and its
 * one genuinely distinguishing feature (operator ↔ POS operator id mapping,
 * contact persons) has zero backend and no owning ADR
 * (staff-and-access.md §11.1). A second tab duplicating 9.1 would be the
 * junk-drawer failure this section's own spec warns against.
 */
@Component({
  selector: 'q-staff-shell',
  imports: [TPipe, RouterLink, RouterLinkActive, RouterOutlet],
  templateUrl: './staff-shell.html',
  styleUrl: './staff-shell.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StaffShell {}
