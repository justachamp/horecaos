import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../core/api/api-client';
import { leaveSupportTenant, supportTenant } from '../core/auth/support-tenant';
import { formatDateTime } from '../core/format/datetime';
import { TPipe } from '../core/i18n/t.pipe';

interface SupportSessionView {
  readonly access: 'VIEW' | 'ASSIST';
  readonly reason: string;
  readonly expiresAt: string;
}

/**
 * The strip a HorecaOS support person sees inside a tenant (ADR 0081): which
 * access they hold, until when, and a way out. Shown only in a support tab.
 * When the session has ended or lapsed it says so, because the screens behind
 * it will start refusing and the person should know why.
 */
@Component({
  selector: 'q-support-banner',
  imports: [TPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (tenant) {
      <div class="support q-caption" role="status">
        @if (session(); as current) {
          <strong>{{ (current.access === 'ASSIST' ? 'support.banner.assist' : 'support.banner.view') | t }}</strong>
          <span>{{ 'support.banner.until' | t: { time: until(current.expiresAt) } }}</span>
          <span class="reason">{{ current.reason }}</span>
        } @else if (checked()) {
          <strong>{{ 'support.banner.none' | t }}</strong>
        }
        <button type="button" class="leave q-caption" (click)="leave()">{{ 'support.banner.leave' | t }}</button>
      </div>
    }
  `,
  styles: `
    .support {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 12px;
      padding: 6px 16px;
      background: var(--q-warning-tint, #fcf4d6);
      color: var(--q-warning-text, #8e6a00);
      border-bottom: 1px solid var(--q-hairline, #e0e0e0);
    }
    .reason {
      opacity: 0.85;
    }
    .leave {
      margin-left: auto;
      background: none;
      border: 1px solid currentColor;
      color: inherit;
      padding: 2px 10px;
      cursor: pointer;
    }
  `,
})
export class SupportBanner {
  private readonly api = inject(ApiClient);

  protected readonly tenant = supportTenant();
  protected readonly session = signal<SupportSessionView | null>(null);
  protected readonly checked = signal(false);

  constructor() {
    if (this.tenant !== null) {
      void this.load(this.tenant);
    }
  }

  private async load(tenant: string): Promise<void> {
    try {
      const result = await firstValueFrom(
        this.api.get<SupportSessionView>(`/api/v1/operations/tenants/${tenant}/support-sessions/current`),
      );
      this.session.set(result.value);
    } catch {
      this.session.set(null);
    } finally {
      this.checked.set(true);
    }
  }

  protected until(iso: string): string {
    return formatDateTime(new Date(iso), 'Asia/Tashkent');
  }

  protected leave(): void {
    leaveSupportTenant();
    globalThis.location?.assign('/');
  }
}
