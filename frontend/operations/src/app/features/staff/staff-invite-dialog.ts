import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';

import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { CAPABILITY_SENTENCES, sentenceLocale } from './capability-sentences';
import { RoleDescriptor, ScopeDirectory, ScopeType, StaffInvitationRequest } from './staff-api';
import { Scope, canGrantAt, tenantScope, brandScope, locationScope } from './scope-coverage';
import { ScopeGrant } from '../../core/auth/session-context';
import { roleDescription, roleLabel } from './staff-role-labels';
import { ScopeOption } from './staff-job-dialog';

/** Digits the mask accepts after the fixed `+998` prefix — nine, matching a UZ mobile number. */
const PHONE_DIGIT_COUNT = 9;

/**
 * Пригласить — invite a new colleague with a job (staff-and-access.md §4,
 * gap map row 9.1a, ADR 0116). A 560px create-modal, not a page and not a
 * wizard — the spec's own words: "A wizard for four fields is an insult
 * delivered in three steps."
 *
 * Job, scope and the Сможет/Не сможет preview are computed exactly the way
 * {@link StaffJobDialog} already computes them for the People screen's own
 * Add-job — §0's corollary (a granter only ever offers what it may itself
 * confer) is a picker filter here too, never a disabled option.
 *
 * This component never calls the API itself. `staff-page.ts` does, and hands
 * back `busy`, `serverError`, `duplicatePhoneSubject` (§4's inline "уже есть
 * доступ" case, a link rather than a name — ADR 0029) and, on success,
 * `createdLink` — the one-time invite link, shown here with a copy button
 * because `Toasts` carries only a translated sentence, never a value like
 * this (see that service's own doc).
 */
@Component({
  selector: 'q-staff-invite-dialog',
  imports: [TPipe, RouterLink],
  templateUrl: './staff-invite-dialog.html',
  styleUrl: './staff-invite-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StaffInviteDialog {
  readonly tenantId = input.required<string>();
  readonly roles = input.required<readonly RoleDescriptor[]>();
  readonly directory = input.required<ScopeDirectory>();
  readonly myScopes = input.required<readonly ScopeGrant[]>();
  readonly busy = input(false);
  readonly serverError = input<string | null>(null);
  readonly duplicatePhoneSubject = input<string | null>(null);
  readonly createdLink = input<string | null>(null);
  /** Whether `createdLink` is a freshly-resent link (staff-page's «Отправить повторно») rather than a just-created invite's. */
  readonly resent = input(false);

  readonly submitted = output<StaffInvitationRequest>();
  readonly dismiss = output<void>();

  protected readonly i18n = inject(I18n);

  protected readonly fullName = signal('');
  protected readonly phoneDigits = signal('');
  protected readonly email = signal('');
  protected readonly selectedRoleCode = signal<string | null>(null);
  protected readonly selectedBrandId = signal<string | null>(null);
  protected readonly selectedLocationId = signal<string | null>(null);
  protected readonly validUntil = signal('');
  protected readonly reason = signal('');
  protected readonly touched = signal(false);
  protected readonly copied = signal(false);

  /** Only jobs the operator could confer somewhere — §0's corollary, enforced as absence. */
  protected readonly grantableRoles = computed(() =>
    this.roles().filter((role) => this.scopeOptionsForRole(role).length > 0),
  );

  protected readonly selectedRole = computed<RoleDescriptor | null>(
    () => this.grantableRoles().find((role) => role.code === this.selectedRoleCode()) ?? null,
  );

  protected readonly scopeOptions = computed<readonly ScopeOption[]>(() => {
    const role = this.selectedRole();
    return role ? this.scopeOptionsForRole(role) : [];
  });

  /** Pre-selected and read-only when only one option exists (§4's «Где»). */
  protected readonly scopeIsFixed = computed(() => this.scopeOptions().length === 1);

  protected readonly phoneDisplay = computed(() => formatUzPhone(this.phoneDigits()));

  protected readonly phoneComplete = computed(
    () => this.phoneDigits().length === PHONE_DIGIT_COUNT,
  );

  protected readonly nameMissing = computed(() => this.touched() && this.fullName().trim() === '');

  protected readonly phoneMissing = computed(() => this.touched() && !this.phoneComplete());

  protected readonly reasonMissing = computed(() => this.touched() && this.reason().trim() === '');

  protected readonly canSubmit = computed(() => {
    const role = this.selectedRole();
    if (
      !role ||
      this.fullName().trim() === '' ||
      !this.phoneComplete() ||
      this.reason().trim() === ''
    ) {
      return false;
    }
    if (role.scopeType === 'BRAND' && this.selectedBrandId() === null) {
      return false;
    }
    if (role.scopeType === 'LOCATION' && this.selectedLocationId() === null) {
      return false;
    }
    return true;
  });

  /** "Сможет" — the selected job's own capabilities, as sentences. */
  protected readonly grantedSentences = computed(() => {
    const role = this.selectedRole();
    if (!role) {
      return [];
    }
    const locale = sentenceLocale(this.i18n.locale());
    return [...role.capabilities]
      .map((code) => CAPABILITY_SENTENCES[code]?.[locale] ?? code)
      .sort();
  });

  /** "Не сможет" — the complement against every other tenant-visible job, capped at five (§4). */
  protected readonly cannotSentences = computed(() => {
    const role = this.selectedRole();
    if (!role) {
      return [];
    }
    const mine = new Set(role.capabilities);
    const others = new Set<string>();
    for (const candidate of this.roles()) {
      if (candidate.code === role.code) {
        continue;
      }
      for (const capability of candidate.capabilities) {
        if (!mine.has(capability)) {
          others.add(capability);
        }
      }
    }
    const locale = sentenceLocale(this.i18n.locale());
    return Array.from(others)
      .sort()
      .slice(0, 5)
      .map((code) => CAPABILITY_SENTENCES[code]?.[locale] ?? code);
  });

  protected setFullName(value: string): void {
    this.fullName.set(value);
  }

  /** Keeps only digits typed after the fixed `+998`, capped at nine — a paste of the full number still works. */
  protected setPhone(value: string): void {
    const digits = value.replace(/\D/g, '').replace(/^998/, '');
    this.phoneDigits.set(digits.slice(0, PHONE_DIGIT_COUNT));
  }

  protected setEmail(value: string): void {
    this.email.set(value);
  }

  protected selectRole(code: string): void {
    this.selectedRoleCode.set(code || null);
    this.selectedBrandId.set(null);
    this.selectedLocationId.set(null);
    const options = this.scopeOptions();
    if (options.length === 1) {
      this.selectedBrandId.set(options[0].brandId);
      this.selectedLocationId.set(options[0].locationId);
    }
  }

  protected selectScope(index: string): void {
    const option = this.scopeOptions()[Number(index)];
    if (option) {
      this.selectedBrandId.set(option.brandId);
      this.selectedLocationId.set(option.locationId);
    }
  }

  protected setValidUntil(value: string): void {
    this.validUntil.set(value);
  }

  protected setReason(value: string): void {
    this.reason.set(value);
  }

  protected roleLabel(code: string): string {
    return roleLabel(code, (key) => this.i18n.t(key));
  }

  protected roleDescription(code: string): string | null {
    return roleDescription(code, (key) => this.i18n.t(key));
  }

  protected submit(): void {
    this.touched.set(true);
    const role = this.selectedRole();
    const name = this.fullName().trim();
    const reason = this.reason().trim();
    if (!role || !this.canSubmit() || !name || !reason) {
      return;
    }
    const [firstName, ...rest] = name.split(/\s+/);
    const lastName = rest.length > 0 ? rest.join(' ') : firstName;
    const email = this.email().trim();

    this.submitted.emit({
      firstName,
      lastName,
      phone: `+998${this.phoneDigits()}`,
      email: email === '' ? undefined : email,
      roleCode: role.code,
      brandId: this.selectedBrandId() ?? undefined,
      locationId: this.selectedLocationId() ?? undefined,
      reason,
      validUntil: this.validUntil() ? new Date(this.validUntil()).toISOString() : undefined,
      locale: localeCodeOf(this.i18n.locale()),
    });
  }

  /** `Esc` and click-outside close with a confirm once anything was typed (§4's own states). */
  protected close(): void {
    if (
      this.createdLink() === null &&
      this.hasUnsavedInput() &&
      !confirm(this.i18n.t('staff.inviteDialog.confirmClose'))
    ) {
      return;
    }
    this.dismiss.emit();
  }

  protected async copyLink(): Promise<void> {
    const link = this.createdLink();
    if (!link) {
      return;
    }
    try {
      await navigator.clipboard.writeText(link);
      this.copied.set(true);
    } catch {
      // Clipboard access can be refused by the browser; the link stays
      // selectable in the field either way, so nothing is lost.
    }
  }

  private hasUnsavedInput(): boolean {
    return (
      this.fullName().trim() !== '' ||
      this.phoneDigits() !== '' ||
      this.email().trim() !== '' ||
      this.reason().trim() !== ''
    );
  }

  private scopeOptionsForRole(role: RoleDescriptor): readonly ScopeOption[] {
    const tenantId = this.tenantId();
    const mine = this.myScopes();
    const scopeType: ScopeType = role.scopeType;

    if (scopeType === 'PLATFORM') {
      return [];
    }
    if (scopeType === 'TENANT') {
      const scope: Scope = tenantScope(tenantId);
      return canGrantAt(mine, scope, role.capabilities)
        ? [{ brandId: null, locationId: null, label: this.i18n.t('staff.scope.company') }]
        : [];
    }
    if (scopeType === 'BRAND') {
      return this.directory()
        .brands.filter((brand) =>
          canGrantAt(mine, brandScope(tenantId, brand.id), role.capabilities),
        )
        .map((brand) => ({ brandId: brand.id, locationId: null, label: brand.displayName }));
    }
    return this.directory()
      .locations.filter((location) =>
        canGrantAt(mine, locationScope(tenantId, location.brandId, location.id), role.capabilities),
      )
      .map((location) => ({
        brandId: location.brandId,
        locationId: location.id,
        label: location.displayName,
      }));
  }
}

/** `9012345` → `90 123 45 67`, built up as the operator types — never more than the mask allows. */
function formatUzPhone(digits: string): string {
  const groups = [digits.slice(0, 2), digits.slice(2, 5), digits.slice(5, 7), digits.slice(7, 9)];
  return groups.filter((group) => group !== '').join(' ');
}

function localeCodeOf(locale: string): 'uz' | 'ru' | 'en' {
  if (locale === 'uz-Latn') {
    return 'uz';
  }
  return locale === 'en' ? 'en' : 'ru';
}
