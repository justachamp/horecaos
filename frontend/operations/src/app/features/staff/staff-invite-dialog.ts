import { ChangeDetectionStrategy, Component, output } from '@angular/core';

import { TPipe } from '../../core/i18n/t.pipe';

/**
 * Пригласить (staff-and-access.md §4) — honest not-built, not a fake form.
 *
 * The screen the spec describes needs a name-and-phone-first invitation that
 * creates a Keycloak account and hands back a one-time link. Checked before
 * assuming either way: `KeycloakOrganizationProvisioner.ensureMembership`
 * (ADR 0009's invite half) exists, but only as the tenant-*owner* onboarding
 * step (`TenantOwnerAuthorityGrantor`, `OnboardingStep.TENANT_OWNER_LINK_OR_INVITE`)
 * — it takes an email, never a phone, and is called from exactly one
 * onboarding workflow, not from an HTTP endpoint a general staff-invite
 * screen could call. `GrantController.grant` only ever grants a role to an
 * *existing* `principalSubject`, which staff-and-access.md §2 itself is
 * explicit nobody types ("nobody types a UUID").
 *
 * So there is no way to honestly wire up name+phone → new account. This
 * dialog says so, in the same voice `NotBuiltPage` already uses elsewhere in
 * this application, rather than shipping a form with no endpoint behind it.
 */
@Component({
  selector: 'q-staff-invite-dialog',
  imports: [TPipe],
  templateUrl: './staff-invite-dialog.html',
  styleUrl: './staff-invite-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StaffInviteDialog {
  readonly dismiss = output<void>();

  protected close(): void {
    this.dismiss.emit();
  }
}
