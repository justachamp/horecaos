import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { CurrentLocation } from '../../core/auth/current-location';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { InboxApi } from './inbox-api';
import { InboxList } from './inbox-list';
import { ConversationSummaryResponse } from './inbox-conversation';

const FAKE_SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

/** See `order-queue.spec.ts`'s identical constant for why 0ms alone does not flush zoneless CD. */
const FRAME_MS = 20;

function configure(
  list: ReturnType<typeof vi.fn>,
  scope: typeof FAKE_SCOPE | null = FAKE_SCOPE,
): void {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([
        { path: 'inbox', component: InboxList },
        { path: 'inbox/:conversationId', component: InboxList },
      ]),
      {
        provide: CurrentLocation,
        useValue: {
          scope: () => scope,
          denied: () => scope === null,
          ensureLoaded: () => Promise.resolve(),
        },
      },
      { provide: InboxApi, useValue: { list } },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
}

function listResponse(rows: readonly ConversationSummaryResponse[]): ReturnType<typeof vi.fn> {
  return vi.fn().mockReturnValue(of({ value: rows, version: null }));
}

function row(overrides: Partial<ConversationSummaryResponse> = {}): ConversationSummaryResponse {
  return {
    conversationId: 'conv-1',
    channel: 'TELEGRAM',
    customerAccountId: null,
    state: 'FLOW_ACTIVE',
    needsReply: false,
    lastActivityAt: '2026-09-01T09:00:00Z',
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function setVisibility(state: 'visible' | 'hidden'): void {
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true });
  document.dispatchEvent(new Event('visibilitychange'));
}

function resetVisibility(): void {
  Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
}

afterEach(() => {
  resetVisibility();
});

describe('InboxList: rendering', () => {
  it('renders one row per conversation, with its state label', async () => {
    configure(listResponse([row({ conversationId: 'conv-1', state: 'HANDED_TO_OPERATOR' })]));
    const harness = await RouterTestingHarness.create('/inbox');
    await flushMicrotasks();

    const rows = harness.routeNativeElement!.querySelectorAll('[data-testid="conversation-row"]');
    expect(rows.length).toBe(1);
    expect(rows[0].textContent).toContain('With operator');
  });

  it('marks a needs-reply row for attention and shows the caption', async () => {
    configure(listResponse([row({ needsReply: true })]));
    const harness = await RouterTestingHarness.create('/inbox');
    await flushMicrotasks();

    const conversationRow = harness.routeNativeElement!.querySelector(
      '[data-testid="conversation-row"]',
    );
    expect(conversationRow?.className).toContain('conversation-row--attention');
    expect(conversationRow?.textContent).toContain('needs reply');
  });

  it('shows a linked-customer indicator without ever showing PII', async () => {
    configure(listResponse([row({ customerAccountId: 'cust-1' })]));
    const harness = await RouterTestingHarness.create('/inbox');
    await flushMicrotasks();

    expect(harness.routeNativeElement!.textContent).toContain('Linked customer');
    // The id itself never renders — only the indicator.
    expect(harness.routeNativeElement!.textContent).not.toContain('cust-1');
  });

  it('shows the empty message when there are no conversations', async () => {
    configure(listResponse([]));
    const harness = await RouterTestingHarness.create('/inbox');
    await flushMicrotasks();

    expect(
      harness.routeNativeElement!.querySelector('[data-testid="inbox-empty"]')?.textContent,
    ).toContain('No conversations yet');
  });

  it('navigates to the conversation on row click', async () => {
    configure(listResponse([row({ conversationId: 'conv-42' })]));
    const harness = await RouterTestingHarness.create('/inbox');
    await flushMicrotasks();

    const conversationRow: HTMLElement = harness.routeNativeElement!.querySelector(
      '[data-testid="conversation-row"]',
    )!;
    conversationRow.click();
    await flushMicrotasks();

    const router = TestBed.inject(Router);
    expect(router.url).toBe('/inbox/conv-42');
  });
});

describe('InboxList: denied and error states', () => {
  it('shows the denied state on a 403', async () => {
    const list = vi
      .fn()
      .mockReturnValue(
        throwError(() => new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null)),
      );
    configure(list);
    const harness = await RouterTestingHarness.create('/inbox');
    await flushMicrotasks();

    expect(
      harness.routeNativeElement!.querySelector('[data-testid="inbox-denied"]'),
    ).not.toBeNull();
  });

  it('shows nothing when the operator holds no location scope at all', async () => {
    configure(vi.fn(), null);
    const harness = await RouterTestingHarness.create('/inbox');
    await flushMicrotasks();

    expect(
      harness.routeNativeElement!.querySelector('[data-testid="inbox-denied"]'),
    ).not.toBeNull();
    // No request was ever made, so no capability was ever checked — naming
    // CONVERSATION_INBOX_MANAGE here would send the operator to ask a manager
    // to grant a capability that does nothing for them; the real fix is
    // assigning them a location.
    expect(
      harness.routeNativeElement!.querySelector('[data-testid="q-denied-state-capability"]'),
    ).toBeNull();
    expect(harness.routeNativeElement!.textContent).toContain(
      'Ask a manager to assign you a location.',
    );
  });

  it('shows the denied state with no capability named on a 403 that was not a capability refusal', async () => {
    // TENANT_ACCESS_DENIED is also HTTP 403, but it is not
    // INSUFFICIENT_CAPABILITY — naming CONVERSATION_INBOX_MANAGE here would
    // be a guess, not an observed fact.
    const list = vi
      .fn()
      .mockReturnValue(
        throwError(() => new ApiError(ApiErrorCode.TENANT_ACCESS_DENIED, 403, null, null)),
      );
    configure(list);
    const harness = await RouterTestingHarness.create('/inbox');
    await flushMicrotasks();

    expect(
      harness.routeNativeElement!.querySelector('[data-testid="inbox-denied"]'),
    ).not.toBeNull();
    expect(
      harness.routeNativeElement!.querySelector('[data-testid="q-denied-state-capability"]'),
    ).toBeNull();
  });

  it('shows a retryable error band and keeps the frame on a failed fetch', async () => {
    const list = vi
      .fn()
      .mockReturnValue(
        throwError(() => new ApiError(ApiErrorCode.INTERNAL_ERROR, 500, null, 'corr-1')),
      );
    configure(list);
    const harness = await RouterTestingHarness.create('/inbox');
    await flushMicrotasks();

    expect(harness.routeNativeElement!.textContent).toContain('corr-1');
  });
});

describe('InboxList: polling liveness', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('polls again after 10 seconds while the tab stays visible', async () => {
    setVisibility('visible');
    const list = listResponse([]);
    configure(list);

    await RouterTestingHarness.create('/inbox');
    await vi.advanceTimersByTimeAsync(FRAME_MS);
    expect(list).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(10_000);
    expect(list).toHaveBeenCalledTimes(2);
  });

  it('pauses polling while the tab is hidden, and resumes when it becomes visible again', async () => {
    setVisibility('visible');
    const list = listResponse([]);
    configure(list);

    await RouterTestingHarness.create('/inbox');
    await vi.advanceTimersByTimeAsync(FRAME_MS);
    expect(list).toHaveBeenCalledTimes(1);

    setVisibility('hidden');
    await vi.advanceTimersByTimeAsync(10_000);
    expect(list).toHaveBeenCalledTimes(1);

    setVisibility('visible');
    await vi.advanceTimersByTimeAsync(FRAME_MS);
    expect(list).toHaveBeenCalledTimes(2);
  });
});

/**
 * The inbox is the second call site of three shared primitives (ADR 0101):
 * `q-status-pill` (rows `X.15`), `q-empty-state` and `q-denied-state` (row
 * `X.16`). The order board and the customers list are the first of each, and
 * their own specs carry the matching assertions.
 *
 * The interesting one here is the pill. «Needs reply» is to a conversation
 * exactly what lateness is to an order — an overlay on a state, not a state —
 * and this list used to render it as a class on the row plus a caption
 * somewhere else, which is the same two-visual-systems problem the order board
 * had.
 */
describe('InboxList: migrated to shared/ui', () => {
  it('renders «needs reply» as an overlay on an unchanged state word', async () => {
    configure(listResponse([row({ state: 'FLOW_ACTIVE', needsReply: true })]));
    const harness = await RouterTestingHarness.create('/inbox');
    await flushMicrotasks();

    const host: HTMLElement = harness.routeNativeElement!;
    expect(host.querySelector('[data-testid="q-status-pill-status"]')?.textContent?.trim()).toBe(
      'Flow active',
    );
    expect(host.querySelector('[data-testid="q-status-pill-overlay"]')?.textContent?.trim()).toBe(
      'needs reply',
    );
    // One sentence for a screen reader, not three fragments to correlate.
    expect(host.querySelector('[data-testid="q-status-pill"]')?.getAttribute('aria-label')).toBe(
      'Flow active · needs reply',
    );
  });

  it('carries no overlay on a conversation that needs nothing', async () => {
    configure(listResponse([row({ state: 'FLOW_ACTIVE', needsReply: false })]));
    const harness = await RouterTestingHarness.create('/inbox');
    await flushMicrotasks();

    const host: HTMLElement = harness.routeNativeElement!;
    expect(host.querySelector('[data-testid="q-status-pill-status"]')?.textContent?.trim()).toBe(
      'Flow active',
    );
    expect(host.querySelector('[data-testid="q-status-pill-overlay"]')).toBeNull();
  });

  it('uses the shared empty and denied states rather than this screen’s own sentences', async () => {
    configure(listResponse([]));
    const emptyHarness = await RouterTestingHarness.create('/inbox');
    await flushMicrotasks();
    expect(
      emptyHarness.routeNativeElement!.querySelector('[data-testid="q-empty-state"]')?.textContent,
    ).toContain('No conversations yet');

    TestBed.resetTestingModule();
    configure(
      vi
        .fn()
        .mockReturnValue(
          throwError(() => new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null)),
        ),
    );
    const deniedHarness = await RouterTestingHarness.create('/inbox');
    await flushMicrotasks();

    const denied: HTMLElement = deniedHarness.routeNativeElement!;
    // The capability is named as data, and so is who can grant it — the whole
    // difference between a wall and a dead end.
    expect(
      denied.querySelector('[data-testid="q-denied-state-capability"]')?.textContent?.trim(),
    ).toBe('CONVERSATION_INBOX_MANAGE');
    expect(denied.textContent).toContain('A manager who can edit staff roles can grant it.');
  });
});
