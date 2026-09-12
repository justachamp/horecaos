import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { SPLIT_PANE_STORAGE_PREFIX } from '../../shared/ui/split-pane';
import { InboxApi } from './inbox-api';
import { InboxPage } from './inbox-page';

/** A conversation detail this file has no business rendering — see `orders-page.spec.ts`. */
@Component({ selector: 'q-test-conversation', template: 'conversation' })
class StubConversation {}

/**
 * The inbox is `q-split-pane`'s second call site (ADR 0101, row `X.30`), and
 * this file is the migration test for it: the frame that used to be a second
 * copy of the order board's grid is now literally the same component, under
 * its own `inbox` section key.
 *
 * Deliberately about the frame and nothing else — `inbox-list.spec.ts` owns
 * what the queue inside it fetches and renders.
 */
describe('InboxPage: migrated to q-split-pane', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          {
            path: 'inbox',
            component: InboxPage,
            children: [{ path: ':conversationId', component: StubConversation }],
          },
        ]),
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal(null),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: InboxApi, useValue: { list: vi.fn(() => of({ value: [], version: null })) } },
      ],
    });
    TestBed.inject(I18n).setLocale('en');
  });

  afterEach(() => localStorage.clear());

  it('frames the inbox with the shared split pane rather than a second copy of the grid', async () => {
    const harness = await RouterTestingHarness.create('/inbox');
    const host: HTMLElement = harness.routeNativeElement!;

    expect(host.querySelector('[data-testid="q-split-pane"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="inbox-queue-body"]')).not.toBeNull();
    // Nothing docked: the dock is hidden from the reader, not merely zero-wide.
    expect(host.querySelector('.q-split--docked')).toBeNull();
    expect(host.querySelector('[data-testid="inbox-dock"]')?.getAttribute('aria-hidden')).toBe(
      'true',
    );
  });

  it('docks a conversation beside the list rather than replacing it', async () => {
    const harness = await RouterTestingHarness.create('/inbox/conv-7');
    const host: HTMLElement = harness.routeNativeElement!;

    expect(host.querySelector('[data-testid="inbox-queue-body"]')).not.toBeNull();
    expect(host.querySelector('q-test-conversation')).not.toBeNull();
    expect(host.querySelector('.q-split--docked')).not.toBeNull();
  });

  it('reads its width from the inbox section key, not from the order board’s', async () => {
    // The whole point of a per-section key: widening the order detail says
    // nothing about this pane.
    localStorage.setItem(`${SPLIT_PANE_STORAGE_PREFIX}orders`, '760');
    localStorage.setItem(`${SPLIT_PANE_STORAGE_PREFIX}inbox`, '540');

    const harness = await RouterTestingHarness.create('/inbox');
    const pane = harness.routeNativeElement!.querySelector(
      '[data-testid="q-split-pane"]',
    ) as HTMLElement;

    expect(pane.style.getPropertyValue('--q-split-secondary')).toBe('540px');
  });
});
