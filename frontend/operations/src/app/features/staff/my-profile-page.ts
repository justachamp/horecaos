import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { Auth } from '../../core/auth/auth';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { ScopeGrant } from '../../core/auth/session-context';
import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { CAPABILITY_SENTENCES, capabilityAreaName, sentenceLocale } from './capability-sentences';
import { StaffApi, TelegramLinkCodeResponse } from './staff-api';
import { roleLabel, scopeLevelLabel } from './staff-role-labels';

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
 * **Two sections are real; everything else names its own absence rather than
 * rendering nothing.** «Мои должности» is genuinely thin wiring: `scopes` is
 * `GET /api/v1/session/context`'s own field, already fetched by {@link
 * CurrentTenant} for every screen in this app, so this component adds no
 * network call of its own for it — spec's own words, "the same assignment
 * cards as §3, without any action", read literally: no «Убрать», no «Добавить
 * должность», no `validFrom`/`reason`/`grantedBy` (`CapabilityView`'s own
 * `scopes` carries none of those — a card here is honestly poorer than a
 * Карточка card, not a copy of it) and, on purpose, no brand/location name
 * resolution: that would mean an extra `OperationsBrandController` call
 * gated on `BRAND_READ`, a capability the front-line jobs this screen exists
 * for do not reliably hold (unlike `IAM_GRANT_MANAGE`-gated Карточка, which
 * already assumes an administrator). The Telegram card is `9/X.1`'s
 * self-service half: mint a code, show the `/link <code>` command. Whether
 * *this* account is already linked has no self-read endpoint (`GET
 * .../staff/telegram/links` is `IAM_GRANT_MANAGE`-gated administration, per
 * that controller's own doc) — deliberately not built here.
 *
 * «Личные данные» and the rest of «Безопасность» (name/phone/email, sign-in
 * history, active sessions, «Выйти везде», PIN, MFA) need the staff profile
 * store, the Keycloak session projection and the MFA decision — none of
 * which exist (staff-and-access.md §11.1, §11.6, §11.7, §11.9) — so they
 * render as a named absence, the same "omit, do not disable" rule
 * `not-built-page.ts` follows elsewhere, applied inline instead of routing
 * away since the rest of this page is real.
 */
@Component({
  selector: 'q-my-profile-page',
  imports: [TPipe],
  templateUrl: './my-profile-page.html',
  styleUrl: './my-profile-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MyProfilePage {
  private readonly tenant = inject(CurrentTenant);
  private readonly api = inject(StaffApi);
  protected readonly auth = inject(Auth);
  protected readonly i18n = inject(I18n);

  protected readonly scopes = computed<readonly ScopeGrant[]>(() => this.tenant.scopes());
  protected readonly expandedIndex = signal<number | null>(null);

  protected readonly telegramCode = signal<TelegramLinkCodeResponse | null>(null);
  protected readonly telegramBusy = signal(false);
  protected readonly telegramError = signal<string | null>(null);

  constructor() {
    void this.tenant.ensureLoaded();
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
      this.telegramCode.set(await this.api.issueTelegramLinkCode(tenantId));
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
