import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';

import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { TimeZone, formatDateTime } from '../../core/format/datetime';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { Modal } from '../../shared/ui/modal';
import { describeApiError } from '../orders/order-errors';
import { ChannelView, SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import {
  AvailabilityExplanation,
  ExplainedStop,
  StopScope,
  StopSourceName,
  StopsApi,
} from './stop-scope-api';

/** See `stop-list-page.ts`'s identical constant — no location carries a timezone on this response yet. */
const PLACEHOLDER_TIME_ZONE: TimeZone = 'Asia/Tashkent';

/**
 * "Why can't I sell this?" for one dish at this branch (ADR 0141 Decision 1, `catalog.md` §4.6).
 *
 * Over `GET .../inventory/variants/{id}/availability-explanation`, which the platform answers from
 * the one resolver every reader of availability goes through — the storefront menu, the cart,
 * checkout, the marketplace reconciler — so the answer here cannot differ from what a customer or
 * an aggregator is told. It says whether the dish sells, the reasons (a stop covers it, it is sold
 * out, the channel's cut-off is reached, it is not stocked here) and **every** stop that covers it,
 * not the first: scope, source, reason code and end, so a manager can see that a brand-wide recall
 * is what a branch cannot override and who put it there.
 *
 * The channel is the question's other half. With none chosen only a stop that covers every channel
 * can apply, and the dialog says so in words (the `any channel` option's own label) instead of
 * implying the dish sells on a channel nobody asked about.
 *
 * Read-only on purpose: lifting a stop stays on the row's chip, where the version it quotes
 * (`If-Match`) is the one the list showed.
 */
@Component({
  selector: 'q-stop-explainer-dialog',
  imports: [TPipe, Modal],
  templateUrl: './stop-explainer-dialog.html',
  styleUrl: './stop-explainer-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StopExplainerDialog implements OnInit {
  private readonly stops = inject(StopsApi);
  private readonly channelsApi = inject(SalesChannelsApi);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  /** The dish asked about. */
  readonly variantId = input.required<string>();
  /** Its name as the list shows it, already in the operator's language. */
  readonly productName = input.required<string>();

  readonly closed = output<void>();

  protected readonly channels = signal<readonly ChannelView[]>([]);
  /** The channel's code, or `''` for none. */
  protected readonly channelCode = signal('');
  protected readonly loading = signal(true);
  protected readonly explanation = signal<AvailabilityExplanation | null>(null);
  protected readonly errorText = signal<string | null>(null);
  protected readonly denied = signal(false);

  /** Guards against an older answer landing after a newer question: the last one asked wins. */
  private sequence = 0;

  constructor() {
    effect(() => {
      const variantId = this.variantId();
      const channel = this.channelCode();
      untracked(() => void this.load(variantId, channel));
    });
  }

  async ngOnInit(): Promise<void> {
    await this.location.ensureLoaded();
    const where = this.location.scope();
    if (!where) {
      return;
    }
    try {
      const all = await this.channelsApi.list(where);
      this.channels.set(all.filter((channel) => channel.status === 'ACTIVE'));
    } catch {
      // The question can still be asked of no channel in particular.
      this.channels.set([]);
    }
  }

  private async load(variantId: string, channel: string): Promise<void> {
    await this.location.ensureLoaded();
    const where = this.location.scope();
    const mine = ++this.sequence;
    if (!where) {
      this.loading.set(false);
      return;
    }
    this.loading.set(true);
    this.errorText.set(null);
    this.denied.set(false);
    try {
      const answer = await this.stops.explain(
        where,
        variantId,
        channel === '' ? undefined : channel,
      );
      if (mine === this.sequence) {
        this.explanation.set(answer);
      }
    } catch (error) {
      if (mine !== this.sequence) {
        return;
      }
      this.explanation.set(null);
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.errorText.set(
          error instanceof ApiError
            ? describeApiError(error, (key, values) => this.i18n.t(key, values))
            : this.i18n.t('error.unknown.noReference'),
        );
      }
    } finally {
      if (mine === this.sequence) {
        this.loading.set(false);
      }
    }
  }

  protected pickChannel(code: string): void {
    this.channelCode.set(code);
  }

  protected close(): void {
    this.closed.emit();
  }

  /** One literal `t` key per code, so a typo is a build error; a code this build does not know is shown as itself. */
  protected reasonText(code: string): string {
    switch (code) {
      case 'ON_STOP':
        return this.i18n.t('kitchen.stopList.explainer.reason.onStop');
      case 'SOLD_OUT':
        return this.i18n.t('kitchen.stopList.explainer.reason.soldOut');
      case 'CHANNEL_STOPPED':
        return this.i18n.t('kitchen.stopList.explainer.reason.channelStopped');
      case 'NOT_STOCKED_AT_LOCATION':
        return this.i18n.t('kitchen.stopList.explainer.reason.notStocked');
      default:
        return this.i18n.t('kitchen.stopList.explainer.reason.unknown', { code });
    }
  }

  protected scopeText(scope: StopScope): string {
    switch (scope) {
      case 'LOCATION':
        return this.i18n.t('kitchen.stopList.scope.location');
      case 'BRAND':
        return this.i18n.t('kitchen.stopList.scope.brand');
      case 'MENU':
        return this.i18n.t('kitchen.stopList.scope.menu');
      case 'CHANNEL':
        return this.i18n.t('kitchen.stopList.scope.channel');
    }
  }

  protected sourceText(source: StopSourceName): string {
    switch (source) {
      case 'OPERATOR':
        return this.i18n.t('kitchen.stopList.source.manual');
      case 'BOT':
        return this.i18n.t('kitchen.stopList.source.bot');
      case 'POS':
        return this.i18n.t('kitchen.stopList.source.pos');
      default:
        return this.i18n.t('kitchen.stopList.source.unknown');
    }
  }

  /** Until when, in words: a stop with no end reads "until lifted". */
  protected untilText(stop: ExplainedStop): string {
    return stop.endsAt
      ? this.i18n.t('kitchen.stopList.stop.until', {
          when: formatDateTime(new Date(stop.endsAt), PLACEHOLDER_TIME_ZONE),
        })
      : this.i18n.t('kitchen.stopList.stop.indefinite');
  }
}
