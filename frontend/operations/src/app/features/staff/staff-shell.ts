import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { TPipe } from '../../core/i18n/t.pipe';
import { AttendanceStatus } from './shifts/attendance-status';

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
 * **Смены** (IA 9.6, ADR 0156) joins the switch once a tenant records attendance.
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
export class StaffShell {
  protected readonly capabilities = inject(SessionCapabilities);
  private readonly attendance = inject(AttendanceStatus);

  /**
   * **Смены** (ADR 0156) is a tab once the tenant records attendance and the viewer may read shifts --
   * and, while it is still off, for the person who can switch it on, who would otherwise have nowhere
   * to find the switch. Everyone else on a tenant that has not enabled it sees no tab, and is told
   * nothing is missing.
   */
  protected readonly showsShifts = computed(() => {
    if (this.attendance.enabled() === true) {
      return (
        this.capabilities.has('STAFF_SHIFT_READ') || this.capabilities.has('STAFF_SHIFT_MANAGE')
      );
    }
    return this.capabilities.has('TENANT_CONFIGURATION_WRITE');
  });

  constructor() {
    if (
      this.capabilities.has('STAFF_SHIFT_READ') ||
      this.capabilities.has('STAFF_SHIFT_MANAGE') ||
      this.capabilities.has('TENANT_CONFIGURATION_WRITE')
    ) {
      void this.attendance.ensureLoaded();
    }
  }
}
