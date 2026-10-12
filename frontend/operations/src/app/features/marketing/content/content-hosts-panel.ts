import { ChangeDetectionStrategy, Component, OnInit, inject, output, signal } from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ConfirmDialog } from '../../../shared/ui/confirm-dialog';
import { DeniedState } from '../../../shared/ui/denied-state';
import { describeApiError } from '../../orders/order-errors';
import { AllowedHost, ContentApi } from './content-api';

/** A bare, lower-case DNS name: labels of letters, digits and hyphens, at least one dot, no scheme, port or wildcard. */
const HOST = /^(?=.{3,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/;

/** The host a typed address comes to, or null when it is not a bare host name. Pure, so the rule is tested. */
export function normalizeHost(typed: string): string | null {
  const host = typed.trim().toLowerCase();
  return HOST.test(host) ? host : null;
}

/**
 * The hosts an external link may point at (ADR 0157, Decision 3; open input "which external hosts").
 *
 * A brand may link to its own verified channel hostnames, which are read and never copied, and to
 * nothing else until an author with `content.manage` adds a host -- one at a time, no wildcard,
 * `https` only. **Each addition and removal is an audited security fact**, because this list is the
 * open-redirect control: a link whose host later leaves the list is served as "nowhere" on the next
 * read while the item stays live. `t.me` and `instagram.com` are not preloaded.
 */
@Component({
  selector: 'q-content-hosts',
  imports: [ConfirmDialog, DeniedState, TPipe],
  templateUrl: './content-hosts-panel.html',
  styleUrl: './content-hosts-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ContentHostsPanel implements OnInit {
  private readonly api = inject(ContentApi);
  private readonly brand = inject(CurrentBrand);
  private readonly i18n = inject(I18n);

  /** The list changed: the link pickers' own copy of it is stale. */
  readonly changed = output<void>();

  protected readonly hosts = signal<readonly AllowedHost[]>([]);
  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly typed = signal('');
  protected readonly reason = signal('');
  protected readonly busy = signal(false);
  protected readonly removing = signal<AllowedHost | null>(null);

  async ngOnInit(): Promise<void> {
    await this.reload();
  }

  protected normalized(): string | null {
    return normalizeHost(this.typed());
  }

  protected async reload(): Promise<void> {
    const scope = this.brand.scope();
    if (scope === null) {
      this.loading.set(false);
      return;
    }
    try {
      this.hosts.set(await this.api.allowedHosts(scope));
    } catch (failure) {
      this.fail(failure, true);
    } finally {
      this.loading.set(false);
    }
  }

  protected async add(): Promise<void> {
    const scope = this.brand.scope();
    const host = this.normalized();
    if (scope === null || host === null || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.api.addAllowedHost(scope, host, this.reason().trim() || undefined);
      this.typed.set('');
      this.reason.set('');
      await this.reload();
      this.changed.emit();
    } catch (failure) {
      this.fail(failure, false);
    } finally {
      this.busy.set(false);
    }
  }

  protected async confirmRemove(): Promise<void> {
    const scope = this.brand.scope();
    const host = this.removing();
    if (scope === null || host === null || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.api.removeAllowedHost(scope, host.host);
      this.removing.set(null);
      await this.reload();
      this.changed.emit();
    } catch (failure) {
      this.removing.set(null);
      this.fail(failure, false);
    } finally {
      this.busy.set(false);
    }
  }

  private fail(failure: unknown, reading: boolean): void {
    if (failure instanceof ApiError) {
      if (failure.code === 'INSUFFICIENT_CAPABILITY' && reading) {
        this.denied.set(true);
        return;
      }
      this.error.set(describeApiError(failure, (key, values) => this.i18n.t(key, values)));
      return;
    }
    this.error.set(this.i18n.t('marketing.content.editor.failed'));
  }
}
