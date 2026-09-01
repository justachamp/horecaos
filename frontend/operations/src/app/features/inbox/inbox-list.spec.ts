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
