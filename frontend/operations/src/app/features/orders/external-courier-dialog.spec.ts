import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { ExternalPartnerResponse, ExternalQuoteResponse } from '../delivery/dispatch-api';
import { ExternalBookingSubmission, ExternalCourierDialog } from './external-courier-dialog';

const PARTNER: ExternalPartnerResponse = {
  bindingId: 'binding-1',
  providerType: 'noor-delivery',
  supportsHold: false,
};

function cheapQuote(): ExternalQuoteResponse {
  return {
    priced: true,
    quoteId: 'quote-cheap',
    bindingId: PARTNER.bindingId,
    providerType: PARTNER.providerType,
    priceMinor: 10_000,
    currency: 'UZS',
    customerDeliveryFeeMinor: 12_000,
    deltaMinor: -2_000,
    failureCode: null,
  };
}

function expensiveQuote(quoteId = 'quote-expensive'): ExternalQuoteResponse {
  return {
    priced: true,
    quoteId,
    bindingId: PARTNER.bindingId,
    providerType: PARTNER.providerType,
    priceMinor: 19_000,
    currency: 'UZS',
    customerDeliveryFeeMinor: 12_000,
    deltaMinor: 7_000,
    failureCode: null,
  };
}

/** `setInput`, not a host template — zoneless (ADR 0035), same note every sibling dialog spec carries. */
function render(partners: readonly ExternalPartnerResponse[] = [PARTNER]): {
  fixture: ReturnType<typeof TestBed.createComponent<ExternalCourierDialog>>;
} {
  const fixture = TestBed.createComponent(ExternalCourierDialog);
  fixture.componentRef.setInput('partners', partners);
  fixture.detectChanges();
  return { fixture };
}

function acceptButton(host: HTMLElement): HTMLButtonElement {
  return host.querySelector('[data-testid="external-courier-accept"]') as HTMLButtonElement;
}

function acknowledgeCheckbox(host: HTMLElement): HTMLInputElement {
  return host.querySelector('[data-testid="external-courier-acknowledge"]') as HTMLInputElement;
}

describe('ExternalCourierDialog', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('emits quoteRequested for the selected partner', () => {
    const { fixture } = render();
    const host: HTMLElement = fixture.nativeElement;
    const requested: string[] = [];
    fixture.componentInstance.quoteRequested.subscribe((bindingId) => requested.push(bindingId));

    (host.querySelector('[data-testid="external-courier-quote"]') as HTMLButtonElement).click();

    expect(requested).toEqual([PARTNER.bindingId]);
  });

  it('a quote at or under the customer fee can be accepted immediately -- no acknowledgement shown at all', () => {
    const { fixture } = render();
    fixture.componentRef.setInput('quote', cheapQuote());
    fixture.detectChanges();
    const host: HTMLElement = fixture.nativeElement;

    expect(acknowledgeCheckbox(host)).toBeNull();
    expect(acceptButton(host).disabled).toBe(false);
  });

  it('a price increase cannot be accepted implicitly -- Принять stays disabled until the increase is acknowledged', () => {
    const { fixture } = render();
    fixture.componentRef.setInput('quote', expensiveQuote());
    fixture.detectChanges();
    const host: HTMLElement = fixture.nativeElement;

    // The delta is visibly rendered and the accept button is disabled --
    // there is no way to click it before acknowledging.
    expect(host.querySelector('[data-testid="external-courier-delta"]')).not.toBeNull();
    expect(acceptButton(host).disabled).toBe(true);

    const submissions: ExternalBookingSubmission[] = [];
    fixture.componentInstance.accepted.subscribe((submission) => submissions.push(submission));
    acceptButton(host).click();
    expect(submissions).toEqual([]);

    acknowledgeCheckbox(host).checked = true;
    acknowledgeCheckbox(host).dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(acceptButton(host).disabled).toBe(false);
    acceptButton(host).click();
    expect(submissions).toEqual([{ bindingId: PARTNER.bindingId, quoteId: 'quote-expensive' }]);
  });

  it('a re-quote never inherits an earlier acknowledgement -- a fresh higher price is disabled again', () => {
    const { fixture } = render();
    fixture.componentRef.setInput('quote', expensiveQuote('quote-round-1'));
    fixture.detectChanges();
    const host: HTMLElement = fixture.nativeElement;

    acknowledgeCheckbox(host).checked = true;
    acknowledgeCheckbox(host).dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(acceptButton(host).disabled).toBe(false);

    // The operator re-quotes -- a different, still-higher price arrives under a new id.
    fixture.componentRef.setInput('quote', expensiveQuote('quote-round-2'));
    fixture.detectChanges();

    // The new quote id must be acknowledged on its own; the earlier tick does not carry over.
    expect(acceptButton(host).disabled).toBe(true);

    const submissions: ExternalBookingSubmission[] = [];
    fixture.componentInstance.accepted.subscribe((submission) => submissions.push(submission));
    acceptButton(host).click();
    expect(submissions).toEqual([]);
  });

  it('abandon is always available once a quote exists, acknowledgement included or not', () => {
    const { fixture } = render();
    fixture.componentRef.setInput('quote', expensiveQuote());
    fixture.detectChanges();
    const host: HTMLElement = fixture.nativeElement;
    const submissions: ExternalBookingSubmission[] = [];
    fixture.componentInstance.abandoned.subscribe((submission) => submissions.push(submission));

    (host.querySelector('[data-testid="external-courier-abandon"]') as HTMLButtonElement).click();

    expect(submissions).toEqual([{ bindingId: PARTNER.bindingId, quoteId: 'quote-expensive' }]);
  });

  it('an unpriced quote shows the failure and offers neither accept nor abandon', () => {
    const { fixture } = render();
    fixture.componentRef.setInput('quote', {
      priced: false,
      customerDeliveryFeeMinor: 0,
      providerType: PARTNER.providerType,
      failureCode: 'BINDING_UNAVAILABLE',
    } satisfies ExternalQuoteResponse);
    fixture.detectChanges();
    const host: HTMLElement = fixture.nativeElement;

    expect(host.querySelector('[data-testid="external-courier-unavailable"]')).not.toBeNull();
    expect(acceptButton(host)).toBeNull();
    expect(host.querySelector('[data-testid="external-courier-abandon"]')).toBeNull();
  });

  it('emits dismiss when the operator closes the dialog', () => {
    const { fixture } = render();
    const host: HTMLElement = fixture.nativeElement;
    let dismissed = false;
    fixture.componentInstance.dismiss.subscribe(() => (dismissed = true));

    const dismissButton = [...host.querySelectorAll('button')].find(
      (b) => b.textContent?.trim() === 'Dismiss',
    ) as HTMLButtonElement;
    dismissButton.click();

    expect(dismissed).toBe(true);
  });
});
