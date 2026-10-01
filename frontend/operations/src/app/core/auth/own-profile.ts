import { Injectable, Signal, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../api/api-client';
import { ApiError } from '../api/problem-details';
import { StaffMember, hasName } from '../api/staff-member';
import { staffPaths } from '../api/staff-paths';
import { I18n, hasStoredLocale } from '../i18n/i18n';
import { Auth } from './auth';
import { CurrentTenant } from './current-tenant';

/**
 * The signed-in person's own staff record (ADR 0139) -- what the shell chip
 * reads, in place of the token's `name` claim.
 *
 * **Why the chip does not read the token.** The access token's name is written
 * once, when the Keycloak account is created or the invitation is accepted, and
 * never again (ADR 0139: later edits are not mirrored into Keycloak, because
 * one account can serve two tenants and the last tenant to edit a name would
 * rename the person in the other's session). After «Личные данные» saves a new
 * name the claim is stale until the next sign-in; this record is not.
 *
 * **Keyed by the signed-in subject.** A record loaded for one account must
 * never label the next account that signs in on the same tab, so the held value
 * carries the subject it was read for and {@link member} answers `null` for
 * anyone else. {@link ensureLoaded} reads again whenever the subject changes.
 *
 * **Absent is not an error.** A HorecaOS support session and a device principal
 * have no record in the tenant (404), and the chip then falls back to the token
 * claim exactly as it did before this record existed.
 *
 * **The interface language follows the person to a new device, once.** The
 * record carries the language a person chose (`uiLocale`); on a browser where
 * nobody has chosen one yet, the first load applies it. A browser that already
 * has a chosen language keeps it -- the language switcher in the rail is a
 * per-device decision and a stored default must not fight it on every sign-in.
 */
@Injectable({ providedIn: 'root' })
export class OwnProfile {
  private readonly api = inject(ApiClient);
  private readonly tenant = inject(CurrentTenant);
  private readonly auth = inject(Auth);
  private readonly i18n = inject(I18n);

  private readonly held = signal<{
    readonly subject: string;
    readonly member: StaffMember | null;
  } | null>(null);

  /** The record of whoever is signed in now, or `null` when there is none (or it has not loaded). */
  readonly member: Signal<StaffMember | null> = computed(() => {
    const held = this.held();
    return held !== null && held.subject === this.auth.subject() ? held.member : null;
  });

  /** The name the tenant keeps for the signed-in person, or `null` to fall back to the token claim. */
  readonly displayName: Signal<string | null> = computed(() => {
    const member = this.member();
    return member !== null && hasName(member) ? member.displayName : null;
  });

  private loading: Promise<void> | null = null;
  private loadingFor: string | null = null;

  /** Reads the record once per signed-in subject; later calls for the same subject replay the same promise. */
  ensureLoaded(): Promise<void> {
    const subject = this.auth.subject();
    if (subject === null) {
      return Promise.resolve();
    }
    if (this.loading === null || this.loadingFor !== subject) {
      this.loadingFor = subject;
      this.loading = this.load(subject);
    }
    return this.loading;
  }

  /** Takes a record a screen just wrote, so the chip shows the new name at once and not at the next load. */
  apply(member: StaffMember): void {
    const subject = this.auth.subject();
    if (subject !== null) {
      this.held.set({ subject, member });
    }
  }

  private adoptStoredLanguage(member: StaffMember): void {
    if (hasStoredLocale()) {
      return;
    }
    if (member.uiLocale === 'ru' || member.uiLocale === 'en') {
      this.i18n.setLocale(member.uiLocale);
    } else if (member.uiLocale === 'uz') {
      this.i18n.setLocale('uz-Latn');
    }
  }

  private async load(subject: string): Promise<void> {
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (tenantId === null) {
      this.held.set({ subject, member: null });
      return;
    }
    try {
      const result = await firstValueFrom(this.api.get<StaffMember>(staffPaths.me(tenantId)));
      this.held.set({ subject, member: result.value });
      this.adoptStoredLanguage(result.value);
    } catch (error) {
      if (!(error instanceof ApiError)) {
        throw error;
      }
      // 404: this tenant keeps no record for the account. Anything else: the
      // chip is cosmetic, so it stays on the token claim and the page works.
      this.held.set({ subject, member: null });
    }
  }
}
