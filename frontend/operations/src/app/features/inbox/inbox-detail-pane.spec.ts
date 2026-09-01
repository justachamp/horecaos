import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CurrentLocation } from '../../core/auth/current-location';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { InboxApi } from './inbox-api';
import { InboxDetailPane } from './inbox-detail-pane';
import {
  ConversationDetailResponse,
  ConversationMessageResponse,
  ConversationResponse,
} from './inbox-conversation';

const FAKE_SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function conversation(overrides: Partial<ConversationResponse> = {}): ConversationResponse {
  return {
    conversationId: 'conv-1',
    brandId: 'b1',
    channel: 'TELEGRAM',
    customerAccountId: null,
    state: 'HANDED_TO_OPERATOR',
    assignedTo: 'operator-1',
    updatedAt: '2026-09-01T09:00:00Z',
    version: 3,
    ...overrides,
  };
}

function message(
  overrides: Partial<ConversationMessageResponse> = {},
): ConversationMessageResponse {
  return {
    messageId: 'msg-1',
    direction: 'INBOUND',
    blockId: null,
    actorPrincipalId: null,
    body: 'Hello, anyone there?',
    occurredAt: '2026-09-01T09:00:00Z',
    ...overrides,
  };
}

function detailOf(
  conv: ConversationResponse,
  messages: readonly ConversationMessageResponse[] = [],
): ConversationDetailResponse {
  return { conversation: conv, messages };
}

function configure(options: {
  detail?: ReturnType<typeof vi.fn>;
  reply?: ReturnType<typeof vi.fn>;
  takeover?: ReturnType<typeof vi.fn>;
  returnToFlow?: ReturnType<typeof vi.fn>;
  close?: ReturnType<typeof vi.fn>;
  scope?: typeof FAKE_SCOPE | null;
}): void {
  TestBed.configureTestingModule({
    providers: [
      {
        provide: CurrentLocation,
        useValue: {
          scope: () => (options.scope === undefined ? FAKE_SCOPE : options.scope),
          denied: () => options.scope === null,
          ensureLoaded: () => Promise.resolve(),
        },
      },
      {
        provide: InboxApi,
        useValue: {
          detail:
            options.detail ??
            vi.fn().mockReturnValue(of({ value: detailOf(conversation()), version: 3 })),
          reply: options.reply ?? vi.fn(),
          takeover: options.takeover ?? vi.fn(),
          returnToFlow: options.returnToFlow ?? vi.fn(),
          close: options.close ?? vi.fn(),
        },
      },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
}

async function render(conversationId = 'conv-1') {
  const fixture = TestBed.createComponent(InboxDetailPane);
  fixture.componentRef.setInput('conversationId', conversationId);
  fixture.detectChanges();
  await flushMicrotasks();
  fixture.detectChanges();
  return fixture;
}

describe('InboxDetailPane: rendering the loaded conversation', () => {
  it('shows the channel, state, and assignment', async () => {
    configure({
      detail: vi.fn().mockReturnValue(of({ value: detailOf(conversation()), version: 3 })),
    });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    expect(host.textContent).toContain('Telegram');
    expect(host.textContent).toContain('With operator');
    expect(host.textContent).toContain('operator-1');
  });

  it('renders each message with its authorship label', async () => {
    const messages = [
      message({ messageId: 'm1', direction: 'INBOUND', body: 'Hi there' }),
      message({ messageId: 'm2', direction: 'OUTBOUND', body: 'Welcome!' }),
      message({
        messageId: 'm3',
        direction: 'OPERATOR',
        body: 'How can I help?',
        actorPrincipalId: 'operator-1',
      }),
    ];
    configure({
      detail: vi
        .fn()
        .mockReturnValue(of({ value: detailOf(conversation(), messages), version: 3 })),
    });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    const rows = host.querySelectorAll('.pane__message');
    expect(rows.length).toBe(3);
    expect(rows[0].textContent).toContain('Customer');
    expect(rows[0].textContent).toContain('Hi there');
    expect(rows[1].textContent).toContain('Flow');
    expect(rows[2].textContent).toContain('Operator');
  });

  it('shows the empty-history message when there is no history yet', async () => {
    configure({
      detail: vi.fn().mockReturnValue(of({ value: detailOf(conversation()), version: 3 })),
    });
    const fixture = await render();

    expect(fixture.nativeElement.textContent).toContain('No messages yet');
  });
});

describe('InboxDetailPane: actions gated by state', () => {
  it('shows only take-over for a FLOW_ACTIVE conversation', async () => {
    configure({
      detail: vi
        .fn()
        .mockReturnValue(
          of({ value: detailOf(conversation({ state: 'FLOW_ACTIVE' })), version: 3 }),
        ),
    });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    expect(host.querySelector('[data-testid="inbox-detail-takeover"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="inbox-reply-input"]')).toBeNull();
    expect(host.querySelector('[data-testid="inbox-detail-return"]')).toBeNull();
  });

  it('shows reply, return-to-flow, and close for a HANDED_TO_OPERATOR conversation', async () => {
    configure({
      detail: vi
        .fn()
        .mockReturnValue(
          of({ value: detailOf(conversation({ state: 'HANDED_TO_OPERATOR' })), version: 3 }),
        ),
    });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    expect(host.querySelector('[data-testid="inbox-reply-input"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="inbox-detail-return"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="inbox-detail-close"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="inbox-detail-takeover"]')).toBeNull();
  });

  it('shows only close for a CLOSED conversation, and hides it once already closed', async () => {
    configure({
      detail: vi
        .fn()
        .mockReturnValue(of({ value: detailOf(conversation({ state: 'CLOSED' })), version: 3 })),
    });
    const fixture = await render();
    const host: HTMLElement = fixture.nativeElement;

    expect(host.querySelector('[data-testid="inbox-detail-close"]')).toBeNull();
    expect(host.querySelector('[data-testid="inbox-detail-takeover"]')).toBeNull();
    expect(host.querySelector('[data-testid="inbox-reply-input"]')).toBeNull();
  });
});

describe('InboxDetailPane: reply', () => {
  it('sends the typed reply and appends it to history without a full reload', async () => {
    const detailFn = vi
      .fn()
      .mockReturnValue(
        of({ value: detailOf(conversation({ state: 'HANDED_TO_OPERATOR' })), version: 3 }),
      );
    const reply = vi
      .fn()
      .mockReturnValue(of(message({ messageId: 'm-new', direction: 'OPERATOR', body: 'On it!' })));
    configure({ detail: detailFn, reply });
    const fixture = await render();

    const textarea: HTMLTextAreaElement = fixture.nativeElement.querySelector(
      '[data-testid="inbox-reply-input"]',
    );
    textarea.value = 'On it!';
    textarea.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const sendButton: HTMLButtonElement = fixture.nativeElement.querySelector(
      '[data-testid="inbox-reply-send"]',
    );
    sendButton.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(reply).toHaveBeenCalledWith(FAKE_SCOPE, 'conv-1', 'On it!');
    expect(fixture.nativeElement.textContent).toContain('On it!');
    // No second detail fetch: the sent message is already in hand.
    expect(detailFn).toHaveBeenCalledTimes(1);
  });

  it('disables sending a blank reply', async () => {
    configure({
      detail: vi
        .fn()
        .mockReturnValue(
          of({ value: detailOf(conversation({ state: 'HANDED_TO_OPERATOR' })), version: 3 }),
        ),
    });
    const fixture = await render();

    const sendButton: HTMLButtonElement = fixture.nativeElement.querySelector(
      '[data-testid="inbox-reply-send"]',
    );
    expect(sendButton.disabled).toBe(true);
  });
});

describe('InboxDetailPane: takeover, return-to-flow, close', () => {
  it('takes the conversation over and reflects the new state without a reload', async () => {
    const detailFn = vi
      .fn()
      .mockReturnValue(
        of({ value: detailOf(conversation({ state: 'FLOW_ACTIVE', version: 5 })), version: 5 }),
      );
    const takeover = vi
      .fn()
      .mockReturnValue(
        of(conversation({ state: 'HANDED_TO_OPERATOR', version: 6, assignedTo: 'operator-9' })),
      );
    configure({ detail: detailFn, takeover });
    const fixture = await render();

    fixture.nativeElement.querySelector('[data-testid="inbox-detail-takeover"]').click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(takeover).toHaveBeenCalledWith(FAKE_SCOPE, 'conv-1', 5);
    expect(fixture.nativeElement.textContent).toContain('operator-9');
    expect(detailFn).toHaveBeenCalledTimes(1);
  });

  it('returns the conversation to the flow and reloads the full detail (the engine may have answered again)', async () => {
    const first = of({
      value: detailOf(conversation({ state: 'HANDED_TO_OPERATOR', version: 3 })),
      version: 3,
    });
    const second = of({
      value: detailOf(conversation({ state: 'IDLE', version: 4 }), [
        message({ messageId: 'm-welcome', direction: 'OUTBOUND', body: 'Welcome back!' }),
      ]),
      version: 4,
    });
    const detailFn = vi.fn().mockReturnValueOnce(first).mockReturnValueOnce(second);
    const returnToFlow = vi.fn().mockReturnValue(of(conversation({ state: 'IDLE', version: 4 })));
    configure({ detail: detailFn, returnToFlow });
    const fixture = await render();

    fixture.nativeElement.querySelector('[data-testid="inbox-detail-return"]').click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(returnToFlow).toHaveBeenCalledWith(FAKE_SCOPE, 'conv-1', 3);
    expect(detailFn).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.textContent).toContain('Welcome back!');
  });

  it('closes the conversation and hides further actions', async () => {
    const detailFn = vi.fn().mockReturnValue(
      of({
        value: detailOf(conversation({ state: 'HANDED_TO_OPERATOR', version: 3 })),
        version: 3,
      }),
    );
    const close = vi.fn().mockReturnValue(of(conversation({ state: 'CLOSED', version: 4 })));
    configure({ detail: detailFn, close });
    const fixture = await render();

    fixture.nativeElement.querySelector('[data-testid="inbox-detail-close"]').click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(close).toHaveBeenCalledWith(FAKE_SCOPE, 'conv-1', 3);
    expect(fixture.nativeElement.querySelector('[data-testid="inbox-detail-close"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="inbox-reply-input"]')).toBeNull();
  });

  it('on a stale version, re-reads the conversation and shows a notice rather than retrying', async () => {
    const detailFn = vi
      .fn()
      .mockReturnValue(
        of({ value: detailOf(conversation({ state: 'FLOW_ACTIVE', version: 5 })), version: 5 }),
      );
    const takeover = vi
      .fn()
      .mockReturnValue(throwError(() => new ApiError(ApiErrorCode.STALE_VERSION, 409, null, null)));
    configure({ detail: detailFn, takeover });
    const fixture = await render();

    fixture.nativeElement.querySelector('[data-testid="inbox-detail-takeover"]').click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(detailFn).toHaveBeenCalledTimes(2);
    expect(
      fixture.nativeElement.querySelector('[data-testid="inbox-detail-notice"]'),
    ).not.toBeNull();
  });
});

describe('InboxDetailPane: not-found and denied', () => {
  it('shows the not-found state for a conversation outside the brand', async () => {
    configure({
      detail: vi
        .fn()
        .mockReturnValue(
          throwError(() => new ApiError(ApiErrorCode.RESOURCE_NOT_FOUND, 404, null, null)),
        ),
    });
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="inbox-detail-not-found"]'),
    ).not.toBeNull();
  });

  it('shows the denied state when the operator holds no location scope', async () => {
    configure({ scope: null });
    const fixture = await render();

    expect(
      fixture.nativeElement.querySelector('[data-testid="inbox-detail-denied"]'),
    ).not.toBeNull();
  });
});
