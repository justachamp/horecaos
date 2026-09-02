import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { AcceptancePolicyResponse, OrderPolicyApi } from './order-policy-api';
import { OrderPolicyPage } from './order-policy-page';

const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };

const POLICY: AcceptancePolicyResponse = {
  mode: 'RESTAURANT_APPROVAL',
  approvalChannel: 'HORECAOS_OPERATIONS',
  approvalTimeoutSeconds: 300,
  timeoutAction: 'AUTO_REJECT',
  rejectionReasonRequired: true,
  notifyCustomerWhilePending: true,
  isPlatformDefault: false,
  policyId: 'policy-1',
  policyVersion: 7,
};

class FakeCurrentLocation {
  readonly scope = signal<LocationScope | null>(SCOPE);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('OrderPolicyPage', () => {
  let fixture: ComponentFixture<OrderPolicyPage>;
  let api: { getEffective: ReturnType<typeof vi.fn>; publish: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    api = {
      getEffective: vi.fn().mockResolvedValue(POLICY),
      publish: vi.fn().mockResolvedValue({ ...POLICY, policyVersion: 8 }),
    };

    await TestBed.configureTestingModule({
      imports: [OrderPolicyPage],
      providers: [
        { provide: OrderPolicyApi, useValue: api },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(OrderPolicyPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  });

  it('reads Card 1 at the operator’s own brand scope', () => {
    expect(api.getEffective).toHaveBeenCalledWith(SCOPE);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Restaurant approval');
    expect(text).toContain('version 7');
  });

  it('renders Cards 2, 4 and 5 as honest not-built notes naming the spec section', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('operations-spec/settings.md §10.3 (Card 2');
    expect(text).toContain('operations-spec/settings.md §10.3 (Card 4');
    expect(text).toContain('operations-spec/settings.md §10.3 (Card 5');
  });

  it('publishes a new version with a required reason', async () => {
    const editButton = fixture.nativeElement.querySelector('.card .primary') as HTMLButtonElement;
    editButton.click();
    fixture.detectChanges();

    const publishButton = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
    ).find((button) => button.textContent?.includes('Publish')) as HTMLButtonElement;
    expect(publishButton.disabled).toBe(true); // no reason typed yet

    const reasonInput = fixture.nativeElement.querySelector('#policy-reason') as HTMLInputElement;
    reasonInput.value = 'Switching to manual confirmation during peak hours';
    reasonInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(publishButton.disabled).toBe(false);
    publishButton.click();
    await flushMicrotasks();

    expect(api.publish).toHaveBeenCalledWith(
      SCOPE,
      expect.objectContaining({ reason: 'Switching to manual confirmation during peak hours' }),
    );
  });
});
