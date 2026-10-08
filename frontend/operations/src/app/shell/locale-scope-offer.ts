import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';

import { ApiError } from '../core/api/problem-details';
import { CurrentTenant } from '../core/auth/current-tenant';
import { OwnProfile } from '../core/auth/own-profile';
import { I18n, Locale } from '../core/i18n/i18n';
import { TPipe } from '../core/i18n/t.pipe';
import { draftOf, toSelfRequest } from '../features/staff/staff-profile-draft';
import { StaffMembersApi } from '../features/staff/staff-members-api';

/**
 * Where a language choice lives (ADR 0160, decision 4): the line the rail's language switcher shows right
 * after a change.
 *
 * The switcher decides for **this device** -- a kiosk or an office PC is a device decision, and a shared PC
 * must not carry one person's language to the next -- so the choice is already in force and already
 * remembered here when this appears. It offers one thing: to also make it the language of the person's
 * account, «Для всех моих устройств», which writes `ui_locale` through the existing `PUT .../staff/me`
 * under the record's version (an audited profile edit under ADR 0139, like any other) and then gives up the
 * device's own choice, because the account now says the same thing. Leaving the line alone is the default
 * and keeps the choice on this device only.
 *
 * Shown once per change and only to a person who has a record to write to (a support session and a device
 * have none, so for them the language is a device choice and nothing else), and not when the account
 * already carries the language just picked.
 */
@Component({
  selector: 'q-locale-scope-offer',
  imports: [TPipe],
  template: `
    <div class="locale-offer q-caption" role="status" data-testid="locale-scope-offer">
      <span>{{ 'shell.localeScope.thisDevice' | t }}</span>
      <button
        type="button"
        class="locale-offer__all q-caption"
        [disabled]="busy()"
        (click)="saveForAllDevices()"
        data-testid="locale-scope-all"
      >
        {{ 'shell.localeScope.allDevices' | t }}
      </button>
      <button
        type="button"
        class="locale-offer__dismiss q-caption"
        (click)="done.emit()"
        data-testid="locale-scope-dismiss"
      >
        {{ 'shell.localeScope.keep' | t }}
      </button>
      @if (failed()) {
        <span class="locale-offer__failed" role="alert" data-testid="locale-scope-failed">{{
          'shell.localeScope.failed' | t
        }}</span>
      }
    </div>
  `,
  styles: `
    .locale-offer {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      padding: 2px 8px;
      border: 1px solid var(--q-hairline);
      border-radius: var(--q-radius);
      background: var(--q-canvas);
      color: var(--q-ink-muted);
    }
    .locale-offer__all,
    .locale-offer__dismiss {
      padding: 0;
      border: none;
      background: none;
      color: var(--q-ink);
      text-decoration: underline;
      cursor: pointer;
    }
    .locale-offer__failed {
      color: var(--q-error-text);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LocaleScopeOffer {
  /** The language just chosen on this device. */
  readonly locale = input.required<Locale>();
  readonly done = output<void>();

  private readonly ownProfile = inject(OwnProfile);
  private readonly tenant = inject(CurrentTenant);
  private readonly members = inject(StaffMembersApi);
  private readonly i18n = inject(I18n);

  protected readonly busy = signal(false);
  protected readonly failed = signal(false);

  protected async saveForAllDevices(): Promise<void> {
    const member = this.ownProfile.member();
    const tenantId = this.tenant.tenantId();
    if (member === null || tenantId === null || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.failed.set(false);
    try {
      const updated = await this.members.updateMe(
        tenantId,
        toSelfRequest({ ...draftOf(member), uiLocale: this.locale() }, false),
        member.version,
      );
      this.ownProfile.apply(updated);
      // The account now says what the device said: remember it for the next first paint, then give up the
      // device's own choice so the two cannot drift apart again behind the person's back.
      this.ownProfile.adoptStoredLanguage(updated);
      this.i18n.clearDeviceLocale();
      this.done.emit();
    } catch (error) {
      if (!(error instanceof ApiError)) {
        throw error;
      }
      // A stale record or a refusal: the choice stays on this device, which is where it was.
      this.failed.set(true);
    } finally {
      this.busy.set(false);
    }
  }
}
