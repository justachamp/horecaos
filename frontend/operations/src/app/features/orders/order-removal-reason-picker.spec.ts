import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { ReasonResponse } from '../settings/reference-data/reference-data-api';
import { OrderRemovalReasonPicker } from './order-removal-reason-picker';

function reason(id: string, name: string): ReasonResponse {
  return {
    id,
    kind: 'LINE_REMOVAL',
    systemCategory: 'OTHER',
    internalName: name,
    stockDisposition: 'RETURN_TO_STOCK',
    liabilityParty: 'TENANT',
    customerRefund: null,
    allowedFulfillmentModes: null,
    customerTexts: {},
    status: 'ACTIVE',
    version: 1,
    updatedAt: '2026-10-08T00:00:00Z',
  };
}

function render(reasons: readonly ReasonResponse[], selectedId: string | null = null) {
  const fixture = TestBed.createComponent(OrderRemovalReasonPicker);
  fixture.componentRef.setInput('reasons', reasons);
  fixture.componentRef.setInput('selectedId', selectedId);
  fixture.detectChanges();
  return { fixture, host: fixture.nativeElement as HTMLElement };
}

describe('OrderRemovalReasonPicker (ADR 0169)', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    TestBed.inject(I18n).setLocale('en');
  });

  it('lists the tenant’s reasons by name and never offers a disposition to pick', () => {
    const { host } = render([
      reason('r1', 'Customer changed their mind'),
      reason('r2', 'Kitchen error'),
    ]);

    const options = Array.from(host.querySelectorAll('option')).map((option) =>
      option.textContent?.trim(),
    );
    expect(options).toEqual(['Choose a reason', 'Customer changed their mind', 'Kitchen error']);
    expect(host.textContent).not.toContain('RETURN_TO_STOCK');
    expect(host.textContent).not.toContain('WRITE_OFF');
  });

  it('emits the picked reason id, and null when the placeholder is chosen again', () => {
    const { fixture, host } = render([reason('r1', 'Customer changed their mind')]);
    const picked: (string | null)[] = [];
    fixture.componentInstance.pick.subscribe((id) => picked.push(id));
    const select = host.querySelector('[data-testid="removal-reason-select"]') as HTMLSelectElement;

    select.value = 'r1';
    select.dispatchEvent(new Event('change'));
    select.value = '';
    select.dispatchEvent(new Event('change'));

    expect(picked).toEqual(['r1', null]);
  });

  it('says there is no reason to pick, and links to where one is authored', () => {
    const { host } = render([]);

    expect(host.querySelector('[data-testid="removal-reason-select"]')).toBeNull();
    expect(host.querySelector('[data-testid="removal-reason-empty"]')).not.toBeNull();
    expect(host.querySelector('a')?.getAttribute('href')).toBe('/settings/reference-data');
  });
});
