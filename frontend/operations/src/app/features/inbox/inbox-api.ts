import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { Versioned } from '../../core/api/aggregate-version';
import { command } from '../../core/api/idempotency';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';
import {
  ConversationDetailResponse,
  ConversationMessageResponse,
  ConversationResponse,
  ConversationSummaryResponse,
  SendReplyRequest,
} from './inbox-conversation';

/**
 * `GET/POST .../conversations/**` — `ConversationInboxController` (ADR 0059
 * stage 2). Mirrors `OrderActionsApi`'s shape: every mutation takes the
 * `expectedVersion` the caller already read the conversation at, sent as
 * `If-Match`, and returns the fresh `ConversationResponse` so the caller
 * never needs a second read just to learn the version a mutation left the
 * conversation at (`reply` is the one exception, returning the message it
 * just sent instead — the conversation's own version does not change on a
 * reply, only its `updated_at` does, which no caller here needs to chase).
 */
@Injectable({ providedIn: 'root' })
export class InboxApi {
  private readonly api = inject(ApiClient);

  /** The brand's conversations, needs-attention first. No message bodies (ADR 0059 stage 2). */
  list(scope: LocationScope, limit: number): Observable<Versioned<ConversationSummaryResponse[]>> {
    return this.api.get<ConversationSummaryResponse[]>(operationsPaths.conversations(scope), {
      params: { limit },
    });
  }

  /**
   * One conversation's full decrypted history. The first time a given
   * operator opens a given conversation this way, the server writes a
   * `conversation.history.read` ADR 0027 audit fact; a later poll of an
   * already-open thread by the same operator does not repeat it — this
   * client does not need to do anything special to get that property, it
   * falls out of calling this the same way on every poll.
   */
  detail(
    scope: LocationScope,
    conversationId: string,
  ): Observable<Versioned<ConversationDetailResponse>> {
    return this.api.get<ConversationDetailResponse>(
      operationsPaths.conversation(scope, conversationId),
    );
  }

  /** `Отправить` — only a HANDED_TO_OPERATOR conversation accepts a reply; the server refuses otherwise. */
  reply(
    scope: LocationScope,
    conversationId: string,
    body: string,
  ): Observable<ConversationMessageResponse> {
    return this.api.post<SendReplyRequest, ConversationMessageResponse>(
      operationsPaths.conversationReplies(scope, conversationId),
      command({ body }),
    );
  }

  /** `Взять на себя` — FLOW_ACTIVE -> HANDED_TO_OPERATOR, assigned to the acting operator. */
  takeover(
    scope: LocationScope,
    conversationId: string,
    expectedVersion: number,
    reason?: string,
  ): Observable<ConversationResponse> {
    return this.api.post<undefined, ConversationResponse>(
      operationsPaths.conversationTakeover(scope, conversationId),
      command(undefined),
      { expectedVersion, params: reason ? { reason } : {} },
    );
  }

  /** `Вернуть в поток` — HANDED_TO_OPERATOR -> the flow resumes. */
  returnToFlow(
    scope: LocationScope,
    conversationId: string,
    expectedVersion: number,
  ): Observable<ConversationResponse> {
    return this.api.post<undefined, ConversationResponse>(
      operationsPaths.conversationReturnToFlow(scope, conversationId),
      command(undefined),
      { expectedVersion },
    );
  }

  /** `Закрыть` — any non-CLOSED state may close. A later inbound message reopens it. */
  close(
    scope: LocationScope,
    conversationId: string,
    expectedVersion: number,
    reason?: string,
  ): Observable<ConversationResponse> {
    return this.api.post<undefined, ConversationResponse>(
      operationsPaths.conversationClose(scope, conversationId),
      command(undefined),
      { expectedVersion, params: reason ? { reason } : {} },
    );
  }
}
