import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { PaymentMixNote } from './payment-mix-note';

describe('PaymentMixNote (ADR 0115)', () => {
  let fixture: ComponentFixture<PaymentMixNote>;

  async function render(
    inputs: {
      provisional?: boolean;
      openQuestion?: boolean;
      notCutByChannelOrFulfilment?: boolean;
    } = {},
    locale: 'en' | 'ru' | 'uz-Latn' = 'en',
  ): Promise<HTMLElement> {
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({ imports: [PaymentMixNote] }).compileComponents();
    TestBed.inject(I18n).setLocale(locale);
    fixture = TestBed.createComponent(PaymentMixNote);
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    history.replaceState(null, '', '/statistics/overview');
  });

  it('renders nothing at all when there is nothing to say', async () => {
    const host = await render();

    expect(host.querySelector('[data-testid="payment-mix-note"]')).toBeNull();
  });

  it('says each fact only when its own flag is set', async () => {
    const provisional = await render({ provisional: true });
    expect(provisional.querySelector('[data-testid="payment-mix-provisional"]')).not.toBeNull();
    expect(provisional.querySelector('[data-testid="payment-mix-open-question"]')).toBeNull();
    expect(provisional.querySelector('[data-testid="payment-mix-not-cut"]')).toBeNull();

    const question = await render({ openQuestion: true });
    expect(question.querySelector('[data-testid="payment-mix-provisional"]')).toBeNull();
    expect(
      question.querySelector('[data-testid="payment-mix-open-question"]')?.textContent,
    ).toContain('commission');

    const notCut = await render({ notCutByChannelOrFulfilment: true });
    expect(notCut.querySelector('[data-testid="payment-mix-not-cut"]')?.textContent).toContain(
      'channel or fulfilment type',
    );
  });

  it('speaks the reader’s language, not the registry’s', async () => {
    const ru = await render({ openQuestion: true }, 'ru');
    expect(ru.querySelector('[data-testid="payment-mix-open-question"]')?.textContent).toContain(
      'комисси',
    );

    const uz = await render({ provisional: true }, 'uz-Latn');
    expect(uz.querySelector('[data-testid="payment-mix-provisional"]')?.textContent).toContain(
      'Dastlabki',
    );
  });
});
