import { TestBed } from '@angular/core/testing';

import { TranslateService } from '../../../services/translate.service';
import { PaymentListComponent, type PaymentChoice } from './payment-list.component';

class FakeTranslateService {
  get = (key: string): string => `t:${key}`;
  getWithParams = (key: string): string => `t:${key}`;
  current = (): Record<string, unknown> => ({});
}

const CHOICES: readonly PaymentChoice[] = [
  { code: 'CASH', labelKey: 'cart.cash' },
  { code: 'CLICK', labelKey: 'cart.click' },
  { code: 'UZCARD', labelKey: '' },
];

function setUp(selected: string | null = null) {
  TestBed.configureTestingModule({
    providers: [{ provide: TranslateService, useClass: FakeTranslateService }],
  });
  const fixture = TestBed.createComponent(PaymentListComponent);
  fixture.componentRef.setInput('choices', CHOICES);
  fixture.componentRef.setInput('selected', selected);
  fixture.detectChanges();
  const rows = () => [
    ...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('.pay-row'),
  ];
  return { fixture, rows };
}

describe('PaymentListComponent', () => {
  it('draws one row per payment method, badged with its first letter', () => {
    const { rows } = setUp();

    expect(rows().map((row) => row.querySelector('.pay-row__badge')?.textContent?.trim())).toEqual([
      'C',
      'C',
      'U',
    ]);
  });

  it('labels a known method with its message and an unknown one with its own code', () => {
    const { rows } = setUp();

    expect(rows().map((row) => row.querySelector('.pay-row__label')?.textContent?.trim())).toEqual([
      't:cart.cash',
      't:cart.click',
      'UZCARD',
    ]);
  });

  it('marks only the selected method active', () => {
    const { rows } = setUp('CLICK');

    expect(rows().map((row) => row.classList.contains('is-active'))).toEqual([false, true, false]);
  });

  it('reports the method a tap chose and leaves the selection to its owner', () => {
    const { fixture, rows } = setUp();
    const chosen: string[] = [];
    fixture.componentInstance.choose.subscribe((code) => chosen.push(code));

    rows()[2].click();

    expect(chosen).toEqual(['UZCARD']);
    expect(rows().some((row) => row.classList.contains('is-active'))).toBe(false);
  });
});
