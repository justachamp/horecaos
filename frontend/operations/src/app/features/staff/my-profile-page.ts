import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { Auth } from '../../core/auth/auth';
import { CurrentLocation } from '../../core/auth/current-location';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { OwnProfile } from '../../core/auth/own-profile';
import { ScopeGrant } from '../../core/auth/session-context';
import { ApiError } from '../../core/api/problem-details';
import { StaffMember, hasName } from '../../core/api/staff-member';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { localeDisplayName } from '../../core/i18n/locale-labels';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { CAPABILITY_SENTENCES, capabilityAreaName, sentenceLocale } from './capability-sentences';
import { StaffApi, TelegramLinkCodeResponse } from './staff-api';
import { StaffMembersApi } from './staff-members-api';
import { ProfileDraft, draftOf, toSelfRequest } from './staff-profile-draft';
import { StaffProfileForm } from './staff-profile-form';
import { roleLabel, scopeLevelLabel } from './staff-role-labels';

/** What the platform accepts as a photo, and the size it stops at (`StaffSelfController.MAX_PHOTO_BYTES`). */
const PHOTO_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'image/avif'];
const MAX_PHOTO_BYTES = 1024 * 1024;

interface CapabilityGroup {
  readonly area: string;
  readonly sentences: readonly string[];
}

/**
 * Мой профиль — staff self-service (staff-and-access.md §10, operations
 * IA §9/X.5). Reached from the account chip at the bottom of the rail
 * (`shell.html`), not from the Staff section — this is about the signed-in
 * person, not staff administration.
 *
 * **«Личные данные» is real (ADR 0139, rows `0.2c` and `X.5`).** The person
 * edits their own name, contact phone, photo, interface language and spoken
 * languages, and nothing else: employment, the employee number, the sign-in
 * phone, the reset email and the password are not on this surface -- the first
 * two are a manager's, the rest are Keycloak's own. The record is read from
 * `GET .../staff/me`, which names no member id (the platform resolves the row
 * from the token), so there is no request this page could be made to send about
 * anybody else. A save carries the record's version in `If-Match`; the result
 * is handed to {@link OwnProfile} so the shell chip shows the new name at once,
 * where the token's `name` claim would be stale until the next sign-in.
 *
 * An account the tenant keeps no record for -- a HorecaOS support session, a
 * device -- gets a named note in place of the form, not a form that can only
 * fail.
 *
 * **«Мои должности» is thin wiring:** `scopes` is `GET /api/v1/session/context`'s
 * own field, already fetched by {@link CurrentTenant} for every screen in this
 * app, so this component adds no network call of its own for it — spec's own
 * words, "the same assignment cards as §3, without any action", read literally:
 * no «Убрать», no «Добавить должность», no `validFrom`/`reason`/`grantedBy`
 * (`CapabilityView`'s own `scopes` carries none of those) and, on purpose, no
 * brand/location name resolution: that would mean an extra
 * `OperationsBrandController` call gated on `BRAND_READ`, a capability the
 * front-line jobs this screen exists for do not reliably hold. The Telegram card
 * is `9/X.1`'s self-service half: mint a code, show the `/link <code>` command.
 * Whether *this* account is already linked has no self-read endpoint (`GET
 * .../staff/telegram/links` is `IAM_GRANT_MANAGE`-gated administration) —
 * deliberately not built here.
 *
 * The rest of «Безопасность» (sign-in history, active sessions, «Выйти везде»,
 * PIN, MFA) needs the Keycloak session projection and the MFA decision
 * (staff-and-access.md §11.6, §11.7, §11.9) and still renders as a named
 * absence, the same "omit, do not disable" rule `not-built-page.ts` follows.
 */
@Component({
  selector: 'q-my-profile-page',
  imports: [TPipe, StaffProfileForm],
  templateUrl: './my-profile-page.html',
  styleUrl: './my-profile-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MyProfilePage {
  private readonly tenant = inject(CurrentTenant);
  private readonly location = inject(CurrentLocation);
  private readonly api = inject(StaffApi);
  private readonly membersApi = inject(StaffMembersApi);
  private readonly ownProfile = inject(OwnProfile);
  protected readonly auth = inject(Auth);
  protected readonly i18n = inject(I18n);

  protected readonly scopes = computed<readonly ScopeGrant[]>(() => this.tenant.scopes());
  protected readonly expandedIndex = signal<number | null>(null);

  protected readonly telegramCode = signal<TelegramLinkCodeResponse | null>(null);
  protected readonly telegramBusy = signal(false);
  protected readonly telegramError = signal<string | null>(null);

  /** My own record, read fresh when the page opens so a save never goes out with a stale version. */
  protected readonly member = signal<StaffMember | null>(null);
  protected readonly profileState = signal<'loading' | 'ready' | 'absent' | 'error'>('loading');
  protected readonly editing = signal(false);
  protected readonly busy = signal(false);
  protected readonly profileError = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  /** The name to show: the one typed, else the token claim the shell already reads. */
  protected readonly shownName = computed(() => {
    const member = this.member();
    return member !== null && hasName(member) ? member.displayName : this.auth.displayName();
  });

  protected readonly initials = computed(() => {
    const member = this.member();
    if (member === null) {
      return '';
    }
    return [member.firstName, member.lastName]
      .map((part) => (part ?? '').trim())
      .filter((part) => part.length > 0)
      .map((part) => Array.from(part)[0].toUpperCase())
      .join('');
  });

  constructor() {
    void this.loadProfile();
  }

  private async loadProfile(): Promise<void> {
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      this.profileState.set('absent');
      return;
    }
    try {
      const member = await this.membersApi.me(tenantId);
      if (member === null) {
        this.profileState.set('absent');
        return;
      }
      this.member.set(member);
      this.ownProfile.apply(member);
      this.profileState.set('ready');
    } catch {
      this.profileState.set('error');
    }
  }

  protected startEditing(): void {
    this.profileError.set(null);
    this.notice.set(null);
    this.editing.set(true);
  }

  protected cancelEditing(): void {
    this.editing.set(false);
  }

  /** Saves the five things a person may change. The record's own version goes in `If-Match`. */
  protected async save(draft: ProfileDraft): Promise<void> {
    const current = this.member();
    const tenantId = this.tenant.tenantId();
    if (current === null || !tenantId) {
      return;
    }
    this.busy.set(true);
    this.profileError.set(null);
    try {
      const updated = await this.membersApi.updateMe(
        tenantId,
        toSelfRequest(draft, false),
        current.version,
      );
      this.took(updated);
      this.editing.set(false);
      this.notice.set(this.i18n.t('staff.profile.saved'));
      if (updated.uiLocale !== current.uiLocale) {
        this.applyInterfaceLanguage(updated.uiLocale);
      }
    } catch (error) {
      this.profileError.set(this.describe(error));
    } finally {
      this.busy.set(false);
    }
  }

  /** A chosen file goes to the platform as the image itself; it reads the real type and size from the bytes. */
  protected async choosePhoto(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.item(0) ?? null;
    input.value = '';
    const current = this.member();
    const tenantId = this.tenant.tenantId();
    if (file === null || current === null || !tenantId) {
      return;
    }
    this.notice.set(null);
    if (!PHOTO_TYPES.includes(file.type)) {
      this.profileError.set(this.i18n.t('staff.myProfile.photo.type'));
      return;
    }
    if (file.size > MAX_PHOTO_BYTES) {
      this.profileError.set(this.i18n.t('staff.myProfile.photo.tooLarge'));
      return;
    }
    this.busy.set(true);
    this.profileError.set(null);
    try {
      this.took(await this.membersApi.setMyPhoto(tenantId, file, current.version));
    } catch (error) {
      this.profileError.set(this.describe(error));
    } finally {
      this.busy.set(false);
    }
  }

  protected async removePhoto(): Promise<void> {
    const current = this.member();
    const tenantId = this.tenant.tenantId();
    if (current === null || !tenantId) {
      return;
    }
    this.busy.set(true);
    this.profileError.set(null);
    this.notice.set(null);
    try {
      this.took(
        await this.membersApi.updateMe(
          tenantId,
          toSelfRequest(draftOf(current), true),
          current.version,
        ),
      );
    } catch (error) {
      this.profileError.set(this.describe(error));
    } finally {
      this.busy.set(false);
    }
  }

  protected statusKey(member: StaffMember): MessageKey {
    switch (member.employmentStatus) {
      case 'PENDING':
        return 'staff.status.invited';
      case 'ACTIVE':
        return 'staff.profile.status.ACTIVE';
      case 'ON_LEAVE':
        return 'staff.status.onLeave';
      case 'ENDED':
        return 'staff.status.ended';
    }
  }

  protected languageName(code: string): string {
    return localeDisplayName(this.i18n, code === 'uz' ? 'uz-Latn' : code);
  }

  /** What a write returned is the record now: here, and in the shell chip. */
  private took(member: StaffMember): void {
    this.member.set(member);
    this.ownProfile.apply(member);
  }

  /**
   * A person who just chose an interface language gets it at once, here, on
   * this device. (Signing in somewhere with no language chosen yet picks it up
   * from the record -- see `OwnProfile`.)
   */
  private applyInterfaceLanguage(code: string | null): void {
    if (code === 'ru' || code === 'en') {
      this.i18n.setLocale(code);
    } else if (code === 'uz') {
      this.i18n.setLocale('uz-Latn');
    }
  }

  protected toggleCapabilities(index: number): void {
    this.expandedIndex.set(this.expandedIndex() === index ? null : index);
  }

  protected roleLabel(code: string): string {
    return roleLabel(code, (key) => this.i18n.t(key));
  }

  protected scopeLevelLabel(scopeType: ScopeGrant['scope']['type']): string {
    return scopeLevelLabel(scopeType, (key) => this.i18n.t(key));
  }

  /**
   * The short, honest id shown beside a BRAND/LOCATION card — see this
   * class's own doc for why no display name is resolved.
   */
  protected scopeDetail(grant: ScopeGrant): string | null {
    const id = grant.scope.locationId ?? grant.scope.brandId;
    return id ? id.slice(0, 8) : null;
  }

  /** «Что можно делать» — grouped by area, plain sentences (§3's own rule, reused verbatim). */
  protected capabilityGroups(
    capabilities: readonly string[] | undefined,
  ): readonly CapabilityGroup[] {
    if (!capabilities || capabilities.length === 0) {
      return [];
    }
    const locale = sentenceLocale(this.i18n.locale());
    const byArea = new Map<string, string[]>();
    for (const code of capabilities) {
      const area = capabilityAreaName(code, locale);
      const sentence = CAPABILITY_SENTENCES[code]?.[locale] ?? code;
      const bucket = byArea.get(area);
      if (bucket) {
        bucket.push(sentence);
      } else {
        byArea.set(area, [sentence]);
      }
    }
    return Array.from(byArea, ([area, sentences]) => ({ area, sentences: sentences.sort() })).sort(
      (a, b) => a.area.localeCompare(b.area),
    );
  }

  protected async issueTelegramCode(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      return;
    }
    this.telegramBusy.set(true);
    this.telegramError.set(null);
    try {
      // The branch the operator is working at, when there is one: a brand or
      // branch member holds the capability at their own scope, and only the
      // branch-scoped route covers it (see `staffPaths.telegramStaffLinkCodesAtLocation`).
      // A tenant-wide holder with no branch at all falls back to the tenant route.
      await this.location.ensureLoaded();
      this.telegramCode.set(await this.api.issueTelegramLinkCode(tenantId, this.location.scope()));
    } catch (error) {
      this.telegramError.set(this.describe(error));
    } finally {
      this.telegramBusy.set(false);
    }
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
