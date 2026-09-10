import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { SessionContextService } from '../../core/auth/session-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import { ArrearView, ArrearsBoardView, CommerceApi } from './commerce-api';

/** A pending stage move on one tenant. */
interface Moving {
  readonly subscriptionId: string;
  readonly to: string;
}

/**
 * IA 5.6 Dunning -- every tenant past due or suspended, how long it has been
 * there, and what each stage restricts.
 *
 * Nothing moves a tenant between stages by itself: lateness is a
 * conversation, so the platform raises an incident when a tenant has been
 * past due too long and a person decides. What each stage does is read from
 * the server, which applies it, so this screen cannot drift from it.
 */
@Component({
  selector: 'app-dunning',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './dunning.html',
  styleUrls: ['./plan-catalog.css', './dunning.css'],
})
export class Dunning {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  protected readonly session = inject(SessionContextService);
  private readonly api = inject(CommerceApi);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly board = signal<ArrearsBoardView | null>(null);

  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly actionMessage = signal<string | null>(null);
  protected readonly moving = signal<Moving | null>(null);
  protected readonly reason = signal('');
  protected readonly suspensionReason = signal('');

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      this.board.set(await this.api.arrears());
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected statusKey(status: string): MessageKey {
    return `commerce.subscription.${status}` as MessageKey;
  }

  /** The moves worth offering from arrears: back to active, onward to suspended, or to the end. */
  protected moves(arrear: ArrearView): readonly string[] {
    return arrear.allowedNext.filter((status) => ['ACTIVE', 'SUSPENDED', 'TERMINATED'].includes(status));
  }

  protected open(arrear: ArrearView, to: string): void {
    const current = this.moving();
    this.moving.set(
      current?.subscriptionId === arrear.subscriptionId && current.to === to
        ? null
        : { subscriptionId: arrear.subscriptionId, to },
    );
    this.reason.set('');
    this.suspensionReason.set('');
    this.actionError.set(null);
  }

  protected canMove(): boolean {
    const move = this.moving();
    if (move === null || this.busy() || this.reason().trim().length === 0) {
      return false;
    }
    return move.to !== 'SUSPENDED' || this.suspensionReason().trim().length > 0;
  }

  protected async confirm(arrear: ArrearView): Promise<void> {
    const move = this.moving();
    if (move === null || !this.canMove()) {
      return;
    }
    this.busy.set(true);
    this.actionError.set(null);
    this.actionMessage.set(null);
    try {
      await this.api.transitionSubscription(arrear.tenantId, {
        status: move.to,
        expectedVersion: arrear.version,
        suspensionReason: move.to === 'SUSPENDED' ? this.suspensionReason().trim() : undefined,
        reason: this.reason().trim(),
      });
      this.moving.set(null);
      this.actionMessage.set(
        this.i18n.t('dunning.moved', { tenant: arrear.tenantName, status: this.i18n.t(this.statusKey(move.to)) }),
      );
      await this.load();
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.busy.set(false);
    }
  }
}
