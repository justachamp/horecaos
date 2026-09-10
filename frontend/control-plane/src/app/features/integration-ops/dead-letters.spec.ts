import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { TenantsApi } from '../tenants/tenants-api';
import { DeadLetters } from './dead-letters';
import { FailureSummary, InboxFailureSummary, IntegrationOpsApi } from './integration-ops-api';

const CONFIG: AppConfig = { apiBaseUrl: 'https://api.test.horecaos.uz', displayTimeZone: 'Asia/Tashkent' };

/** Exactly the fields FailureOperationsService.FailureSummary serialises to -- the ones the old type got wrong. */
const OUTBOX: FailureSummary = {
  id: 'ev-out-1', tenantId: 'tenant-1', status: 'DEAD_LETTER', eventType: 'OrderPlaced',
  attemptCount: 10, errorCode: 'TRANSIENT_PROVIDER', lastError: 'timeout',
};
const INBOX: InboxFailureSummary = { ...OUTBOX, id: 'ev-in-1', eventType: 'TenantCreated', consumerName: 'reporting.tenant-summary' };

class FakeOpsApi {
  readonly outboxFailures = vi.fn().mockResolvedValue({ items: [OUTBOX], nextCursor: null });
  readonly inboxFailuresAcrossConsumers = vi.fn().mockResolvedValue({ items: [INBOX], nextCursor: null });
  readonly outboxFailure = vi.fn();
  readonly inboxFailure = vi.fn();
  readonly retryOutbox = vi.fn();
  readonly retryInbox = vi.fn();
  readonly resolveOutbox = vi.fn();
  readonly resolveInbox = vi.fn();
}

describe('DeadLetters', () => {
  let fixture: ComponentFixture<DeadLetters>;
  let api: FakeOpsApi;

  async function create(): Promise<void> {
    api = new FakeOpsApi();
    localStorage.clear();
    sessionStorage.clear();
    await TestBed.configureTestingModule({
      imports: [DeadLetters],
      providers: [
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: IntegrationOpsApi, useValue: api },
        {
          provide: TenantsApi,
          useValue: { listTenants: vi.fn().mockResolvedValue({ items: [{ id: 'tenant-1', displayName: 'Oshxona', slug: 'oshxona' }], nextCursor: null }) },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(DeadLetters);
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  function queue(name: 'outbox' | 'inbox'): HTMLElement {
    return fixture.nativeElement.querySelector(`[data-queue="${name}"]`) as HTMLElement;
  }

  function button(within: HTMLElement, label: string): HTMLButtonElement {
    return (Array.from(within.querySelectorAll('button')) as HTMLButtonElement[]).find((b) => b.textContent?.trim() === label)!;
  }

  async function type(name: string, value: string, event = 'input'): Promise<void> {
    const el = fixture.nativeElement.querySelector(`.actForm [name="${name}"]`) as HTMLInputElement;
    el.value = value;
    el.dispatchEvent(new Event(event));
    await settle();
  }

  it('lists both queues with each failure’s real id, its consumer, and its tenant by name', async () => {
    await create();

    expect(queue('outbox').textContent).toContain('ev-out-1');
    expect(queue('outbox').textContent).toContain('TRANSIENT_PROVIDER');
    expect(queue('inbox').textContent).toContain('reporting.tenant-summary');
    expect(queue('inbox').textContent).toContain('ev-in-1');
    expect(queue('inbox').textContent).toContain('Oshxona');
  });

  it('retries one consumer’s copy with a reason, and drops it once the server says it moved', async () => {
    await create();
    api.retryInbox.mockResolvedValue({ changed: true, outcome: 'retried' });

    button(queue('inbox'), ru['deadLetters.retry.action']).click();
    await settle();
    await type('reason', 'handler fixed in release a1b2c3d');
    (fixture.nativeElement.querySelector('.actForm button[type="submit"]') as HTMLButtonElement).click();
    await settle();

    expect(api.retryInbox).toHaveBeenCalledWith('reporting.tenant-summary', 'ev-in-1', 'handler fixed in release a1b2c3d');
    expect(queue('inbox').textContent).toContain(ru['deadLetters.inbox.empty']);
    expect(fixture.nativeElement.textContent).toContain(ru['deadLetters.retry.succeeded']);
  });

  it('will not resolve an uncertain provider outcome without evidence', async () => {
    await create();
    api.resolveOutbox.mockResolvedValue({ changed: true, outcome: 'resolved' });

    button(queue('outbox'), ru['deadLetters.resolve.action']).click();
    await settle();
    await type('category', 'UNCERTAIN_EXTERNAL_OUTCOME', 'change');
    await type('reason', 'provider confirmed by phone');
    const submit = fixture.nativeElement.querySelector('.actForm button[type="submit"]') as HTMLButtonElement;
    expect(submit.disabled).toBe(true);

    await type('evidence', 'ticket PAY-1234');
    (fixture.nativeElement.querySelector('.actForm button[type="submit"]') as HTMLButtonElement).click();
    await settle();

    expect(api.resolveOutbox).toHaveBeenCalledWith('ev-out-1', 'UNCERTAIN_EXTERNAL_OUTCOME', 'provider confirmed by phone', 'ticket PAY-1234');
  });

  it('shows a failure’s routing facts, never its payload', async () => {
    await create();
    api.outboxFailure.mockResolvedValue({ topic: 'ordering.events', aggregateType: 'Order', aggregateId: 'order-9', correlationId: 'corr-1', lastError: 'Read timed out' });

    button(queue('outbox'), ru['deadLetters.details']).click();
    await settle();

    const open = fixture.nativeElement.querySelector('.openRow') as HTMLElement;
    expect(api.outboxFailure).toHaveBeenCalledWith('ev-out-1');
    expect(open.textContent).toContain('ordering.events');
    expect(open.textContent).toContain('Read timed out');
  });
});
