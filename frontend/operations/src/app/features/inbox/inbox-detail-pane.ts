import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { TimeZone, formatDateTime } from '../../core/format/datetime';
import { CurrentLocation } from '../../core/auth/current-location';
import { LocationScope } from '../../core/api/operations-paths';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { describeApiError } from '../orders/order-errors';
import { InboxApi } from './inbox-api';
import { ConversationMessageResponse, ConversationResponse } from './inbox-conversation';
import { channelLabel, stateLabel } from './inbox-labels';

/** Same cadence as `InboxList` and the order board: a live poll while the tab is visible. */
const POLL_INTERVAL_MS = 10_000;

/** See `order-queue.ts`'s identical constant for why this is a fixed zone, not the browser's. */
const PLACEHOLDER_TIME_ZONE: TimeZone = 'Asia/Tashkent';

/**
 * The inbox detail — a sibling of `OrderDetailPane`, docked beside `InboxList`
 * for the same reason: an operator reading one conversation's history must
 * still see the next one arrive in the queue behind it.
 *
 * **Actions, by state.** `FLOW_ACTIVE` shows only "take over". `HANDED_TO_OPERATOR`
 * shows the reply box, "return to flow", and "close". Every other state
 * (`IDLE`, `CLOSED`) shows only "close" when not already closed — there is
 * nothing else an operator can do to a conversation the engine or nobody is
 * currently driving.
 */
@Component({
  selector: 'q-inbox-detail-pane',
  imports: [TPipe],
  templateUrl: './inbox-detail-pane.html',
  styleUrl: './inbox-detail-pane.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InboxDetailPane {
  private readonly api = inject(InboxApi);
  private readonly location = inject(CurrentLocation);
  private readonly i18n = inject(I18n);
  private readonly destroyRef = inject(DestroyRef);

  /** Bound from the route parameter by `withComponentInputBinding()`. */
  readonly conversationId = input.required<string>();

  protected readonly loading = signal(true);
  protected readonly conversation = signal<ConversationResponse | null>(null);
  protected readonly messages = signal<readonly ConversationMessageResponse[]>([]);
  protected readonly notFound = signal(false);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);

  protected readonly notice = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly replyDraft = signal('');

  private pollHandle: ReturnType<typeof setInterval> | null = null;

  constructor() {
    // `conversationId` is a signal input: navigating from one conversation to
    // another under the same `:conversationId` route config reuses this
    // component (Angular's default `RouteReuseStrategy`), so a plain
    // `ngOnInit` would only ever see the first one. This effect is what
    // notices the second — the same reason `OrderDetailPane` uses one.
    effect(() => {
      const id = this.conversationId();
      this.replyDraft.set('');
      void this.load(id);
    });

    this.pollHandle = setInterval(() => {
      if (document.visibilityState === 'visible') {
        void this.load(this.conversationId(), { silent: true });
      }
    }, POLL_INTERVAL_MS);
    this.destroyRef.onDestroy(() => {
      if (this.pollHandle !== null) {
        clearInterval(this.pollHandle);
      }
    });
  }

  private async load(
    conversationId: string,
    options: { readonly silent?: boolean } = {},
  ): Promise<void> {
    if (!options.silent) {
      this.loading.set(true);
      this.lastError.set(null);
      this.notFound.set(false);
    }

    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.loading.set(false);
      return;
    }
    this.denied.set(false);

    try {
      const result = await firstValueFrom(this.api.detail(scope, conversationId));
      this.conversation.set(result.value.conversation);
      this.messages.set(result.value.messages);
    } catch (error) {
      if (error instanceof ApiError) {
        if (error.code === ApiErrorCode.RESOURCE_NOT_FOUND) {
          this.notFound.set(true);
        } else if (!options.silent) {
          this.lastError.set(error);
        }
      } else {
        throw error;
      }
    } finally {
      this.loading.set(false);
    }
  }

  protected manualRetry(): void {
    void this.load(this.conversationId());
  }

  protected dismissNotice(): void {
    this.notice.set(null);
  }

  protected formatOccurredAt(occurredAt: string): string {
    return formatDateTime(new Date(occurredAt), PLACEHOLDER_TIME_ZONE);
  }

  protected stateLabel(state: string): string {
    return stateLabel(state, (key) => this.i18n.t(key));
  }

  protected channelLabel(channel: string): string {
    return channelLabel(channel, (key) => this.i18n.t(key));
  }

  protected errorMessage(error: ApiError): string {
    return describeApiError(error, (key, values) => this.i18n.t(key, values));
  }

  protected messageAuthorLabel(message: ConversationMessageResponse): MessageKey {
    switch (message.direction) {
      case 'INBOUND':
        return 'inbox.message.author.customer';
      case 'OPERATOR':
        return 'inbox.message.author.operator';
      default:
        return 'inbox.message.author.flow';
    }
  }

  // ------------------------------------------------------------ actions

  protected canTakeOver(): boolean {
    return this.conversation()?.state === 'FLOW_ACTIVE';
  }

  protected canReply(): boolean {
    return this.conversation()?.state === 'HANDED_TO_OPERATOR';
  }

  protected canReturnToFlow(): boolean {
    return this.conversation()?.state === 'HANDED_TO_OPERATOR';
  }

  protected canClose(): boolean {
    return this.conversation()?.state !== 'CLOSED';
  }

  protected updateReplyDraft(value: string): void {
    this.replyDraft.set(value);
  }

  protected async sendReply(): Promise<void> {
    const detail = this.conversation();
    const scope = this.location.scope();
    const body = this.replyDraft().trim();
    if (!detail || !scope || body.length === 0) {
      return;
    }
    this.busy.set(true);
    try {
      const sent = await firstValueFrom(this.api.reply(scope, detail.conversationId, body));
      this.messages.update((current) => [...current, sent]);
      this.replyDraft.set('');
    } catch (error) {
      this.handleActionError(error);
    } finally {
      this.busy.set(false);
    }
  }

  protected async takeover(): Promise<void> {
    await this.runAction((scope, detail) =>
      this.api.takeover(scope, detail.conversationId, detail.version),
    );
  }

  protected async close(): Promise<void> {
    await this.runAction((scope, detail) =>
      this.api.close(scope, detail.conversationId, detail.version),
    );
  }

  protected async returnToFlow(): Promise<void> {
    const detail = this.conversation();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    this.busy.set(true);
    try {
      await firstValueFrom(this.api.returnToFlow(scope, detail.conversationId, detail.version));
      // Unlike takeover/close, returning to the flow can make the engine send
      // something new (a "next" state's own message) — a full reload picks
      // that up, where the local-signal update the other two actions use
      // would not.
      await this.load(detail.conversationId);
    } catch (error) {
      this.handleActionError(error);
    } finally {
      this.busy.set(false);
    }
  }

  private async runAction(
    call: (scope: LocationScope, detail: ConversationResponse) => ReturnType<InboxApi['takeover']>,
  ): Promise<void> {
    const detail = this.conversation();
    const scope = this.location.scope();
    if (!detail || !scope) {
      return;
    }
    this.busy.set(true);
    try {
      const updated = await firstValueFrom(call(scope, detail));
      this.conversation.set(updated);
    } catch (error) {
      this.handleActionError(error);
    } finally {
      this.busy.set(false);
    }
  }

  private handleActionError(error: unknown): void {
    if (!(error instanceof ApiError)) {
      throw error;
    }
    if (
      error.code === ApiErrorCode.STALE_VERSION ||
      error.code === ApiErrorCode.RESOURCE_CONFLICT
    ) {
      // Somebody else changed this conversation — re-read rather than retry.
      void this.load(this.conversationId());
    }
    this.notice.set(this.errorMessage(error));
  }
}
