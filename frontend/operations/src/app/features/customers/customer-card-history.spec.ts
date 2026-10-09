import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { CustomerCardHistory } from './customer-card-history';
import { lead } from './lead-fixtures.testing';
import { CustomerCard, HistoryEntry } from './leads-api';

function entry(overrides: Partial<HistoryEntry> = {}): HistoryEntry {
  return {
    kind: 'NOTIFICATION',
    occurredAt: '2026-10-01T10:00:00Z',
    channel: 'SMS',
    statusCode: 'DELIVERED',
    detailCode: 'order.confirmation',
    referenceId: 'n-1',
    orderId: 'order-1',
    rating: null,
    label: null,
    ...overrides,
  };
}

function card(overrides: Partial<CustomerCard> = {}): CustomerCard {
  return {
    customerAccountId: 'customer-1',
    status: 'ACTIVE',
    displayName: 'Dilnoza',
    preferredLocale: 'ru',
    version: 1,
    blacklisted: false,
    leads: [],
    history: [entry()],
    nextBefore: null,
    nextBeforeId: null,
    ...overrides,
  };
}

describe('CustomerCardHistory', () => {
  let fixture: ComponentFixture<CustomerCardHistory>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CustomerCardHistory],
      providers: [provideRouter([])],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(CustomerCardHistory);
  });

  function render(inputs: Record<string, unknown>): HTMLElement {
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('says the card is loading', () => {
    expect(render({ loading: true }).textContent).toContain('Loading the card');
  });

  it('shows an error, not an empty card, when the card could not be opened', () => {
    const host = render({ error: 'The card could not be opened.' });

    expect(host.querySelector('[data-testid="card-error"]')!.textContent).toContain(
      'could not be opened',
    );
    expect(host.querySelector('[data-testid="card-timeline"]')).toBeNull();
  });

  it('merges every kind into one timeline, by stable codes and never by a guest’s own words', () => {
    const host = render({
      card: card({
        history: [
          entry({
            kind: 'VOICE_CONTACT',
            channel: 'PHONE',
            statusCode: 'CONNECTED',
            detailCode: null,
            label: 'OUTBOUND',
            orderId: null,
          }),
          entry({
            kind: 'REVIEW',
            channel: null,
            statusCode: 'SUBMITTED',
            detailCode: null,
            rating: 4,
          }),
          entry({
            kind: 'CAMPAIGN_RECEIPT',
            statusCode: 'REFUSED',
            detailCode: 'CONSENT_WITHHELD',
            label: 'Autumn buffet',
            orderId: null,
          }),
          entry({
            kind: 'PROMO_REDEMPTION',
            channel: null,
            statusCode: 'REDEEMED',
            detailCode: 'AUTU***',
            label: 'Autumn 10%',
          }),
          entry(),
        ],
      }),
    });

    const rows = [...host.querySelectorAll('[data-testid="card-entry"]')].map(
      (row) => row.textContent ?? '',
    );
    expect(rows).toHaveLength(5);
    expect(rows[0]).toContain('Call');
    expect(rows[0]).toContain('Spoke to them');
    expect(rows[0]).toContain('Outgoing');
    expect(rows[1]).toContain('4/5');
    expect(rows[2]).toContain('Autumn buffet');
    expect(rows[2]).toContain('Not sent to this guest');
    expect(rows[3]).toContain('Used');
    expect(rows[4]).toContain('Delivered');
    expect(rows[4]).toContain('SMS');
    expect(rows[4]).toContain('order.confirmation');
  });

  it('shows a code it has no sentence for as the code it is, rather than hiding the entry', () => {
    const host = render({ card: card({ history: [entry({ statusCode: 'SOME_NEW_STATE' })] }) });

    expect(host.textContent).toContain('SOME_NEW_STATE');
  });

  it('warns before an operator rings a blacklisted guest', () => {
    const host = render({ card: card({ blacklisted: true }) });

    expect(host.querySelector('[data-testid="card-blacklisted"]')).not.toBeNull();
  });

  it('lists the guest’s own callbacks and enquiries above the history', () => {
    const host = render({
      card: card({
        leads: [lead({ status: 'CALLBACK_SCHEDULED', source: 'B2B_CATERING_ENQUIRY' })],
      }),
    });

    const leads = host.querySelector('[data-testid="card-leads"]')!.textContent ?? '';
    expect(leads).toContain('Callback scheduled');
    expect(leads).toContain('Catering enquiry');
  });

  it('asks for older entries only when the card handed out a way back', () => {
    const end = render({ card: card({ nextBefore: null }) });

    expect(end.querySelector('[data-testid="card-older"]')).toBeNull();
  });

  it('emits a request for older entries when there are some', () => {
    let requested = 0;
    fixture.componentInstance.olderRequested.subscribe(() => requested++);
    const host = render({
      card: card({ nextBefore: '2026-09-01T00:00:00Z', nextBeforeId: 'n-0' }),
    });

    (host.querySelector('[data-testid="card-older"]') as HTMLButtonElement).click();

    expect(requested).toBe(1);
  });

  it('offers the call form only to an operator who may record one', () => {
    const host = render({ card: card(), canRecord: false });

    expect(host.querySelector('[data-testid="card-record-call"]')).toBeNull();
    expect(host.textContent).toContain('not record calls');
  });

  it('records a call through the shared form, and says it cannot be changed afterwards', () => {
    const recorded: unknown[] = [];
    fixture.componentInstance.callRecorded.subscribe((request) => recorded.push(request));
    const host = render({ card: card(), canRecord: true });

    (host.querySelector('[data-testid="card-record-call"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(host.textContent).toContain('cannot be changed');
    (host.querySelector('[data-testid="recorder-submit"]') as HTMLButtonElement).click();

    expect(recorded).toEqual([
      {
        direction: 'OUTBOUND',
        outcome: 'CONNECTED',
        attemptId: expect.any(String),
        blockingReason: undefined,
        nextAction: undefined,
        nextActionAt: undefined,
      },
    ]);
  });

  describe('the call form after a submit (ADR 0111 §8)', () => {
    function openForm(host: HTMLElement): void {
      (host.querySelector('[data-testid="card-record-call"]') as HTMLButtonElement).click();
      fixture.detectChanges();
    }

    function submit(host: HTMLElement): void {
      (host.querySelector('[data-testid="recorder-submit"]') as HTMLButtonElement).click();
      fixture.detectChanges();
    }

    function recorder(host: HTMLElement): Element | null {
      return host.querySelector('[data-testid="call-recorder"]');
    }

    it('stays open, with what the operator chose, until the pane says the call was written', () => {
      const host = render({ card: card(), canRecord: true });
      openForm(host);
      const outcome = host.querySelector('[data-testid="recorder-outcome"]') as HTMLSelectElement;
      outcome.value = 'NO_ANSWER';
      outcome.dispatchEvent(new Event('change'));
      fixture.detectChanges();

      submit(host);

      expect(recorder(host)).not.toBeNull();
      expect(
        (host.querySelector('[data-testid="recorder-outcome"]') as HTMLSelectElement).value,
      ).toBe('NO_ANSWER');
    });

    it('shows why the last submit was refused under the form that stays open', () => {
      const host = render({ card: card(), canRecord: true });
      openForm(host);
      submit(host);

      render({ recordError: 'The guest is blacklisted.' });

      expect(host.querySelector('[data-testid="card-record-error"]')!.textContent).toContain(
        'blacklisted',
      );
      expect(recorder(host)).not.toBeNull();
    });

    it('sends the same attempt id on a retry, so the journal keeps one row for one call', () => {
      const sent: { attemptId?: string }[] = [];
      fixture.componentInstance.callRecorded.subscribe((request) => sent.push(request));
      const host = render({ card: card(), canRecord: true });
      openForm(host);

      submit(host);
      submit(host);

      expect(sent).toHaveLength(2);
      expect(sent[0].attemptId).toBeTruthy();
      expect(sent[1].attemptId).toBe(sent[0].attemptId);
    });

    it('closes when the pane has recorded the call, and offers the button again', () => {
      const host = render({ card: card(), canRecord: true, recordedCount: 0 });
      openForm(host);
      submit(host);

      render({ recordedCount: 1 });

      expect(recorder(host)).toBeNull();
      expect(host.querySelector('[data-testid="card-record-call"]')).not.toBeNull();
    });

    it('a form opened after a recorded call starts fresh, with an attempt id of its own', () => {
      const sent: { attemptId?: string }[] = [];
      fixture.componentInstance.callRecorded.subscribe((request) => sent.push(request));
      const host = render({ card: card(), canRecord: true, recordedCount: 0 });
      openForm(host);
      submit(host);
      render({ recordedCount: 1 });

      openForm(host);
      submit(host);

      expect(recorder(host)).not.toBeNull();
      expect(sent[1].attemptId).not.toBe(sent[0].attemptId);
    });

    it('does not carry a half-filled form to the next guest, nor back to the first one', () => {
      const host = render({ card: card({ customerAccountId: 'customer-1' }), canRecord: true });
      openForm(host);
      expect(recorder(host)).not.toBeNull();

      render({ card: null });
      render({ card: card({ customerAccountId: 'customer-2' }) });
      expect(recorder(host)).toBeNull();

      render({ card: null });
      render({ card: card({ customerAccountId: 'customer-1' }) });
      expect(recorder(host)).toBeNull();
    });

    it('keeps the form open while the same guest’s card gains an entry or an older page', () => {
      const host = render({ card: card(), canRecord: true });
      openForm(host);

      render({ card: card({ history: [entry(), entry({ referenceId: 'n-2' })] }) });

      expect(recorder(host)).not.toBeNull();
    });
  });
});
