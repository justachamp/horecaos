import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { DispatchRulesApi, DispatchRulesView } from '../../delivery/dispatch-rules-api';
import { builtInAction, emptyConditions } from '../../delivery/dispatch-rules-model';
import { DispatchRulesSummaryCard } from './dispatch-rules-summary-card';

const BUILT_IN: DispatchRulesView = {
  schema: 1,
  rules: [],
  default: builtInAction(),
  isBuiltIn: true,
  winningScope: null,
  policyId: null,
  policyVersion: 0,
  versionAtScope: 0,
  levels: [],
  groupingAllowed: false,
};

const PUBLISHED: DispatchRulesView = {
  ...BUILT_IN,
  isBuiltIn: false,
  winningScope: 'BRAND',
  rules: [
    {
      id: 'far-zone',
      name: 'Far zone: Yandex first',
      enabled: true,
      when: emptyConditions(),
      then: { ...builtInAction(), mode: 'PARTNER_FIRST' },
    },
    {
      id: 'tablets',
      name: '',
      enabled: false,
      when: emptyConditions(),
      then: { ...builtInAction(), mode: 'MANUAL' },
    },
  ],
};

async function render(
  rules: ReturnType<typeof vi.fn>,
  inputs: { scopeType?: 'TENANT' | 'BRAND' | 'LOCATION' } = {},
): Promise<{ fixture: ComponentFixture<DispatchRulesSummaryCard>; host: HTMLElement }> {
  TestBed.configureTestingModule({
    providers: [provideRouter([]), { provide: DispatchRulesApi, useValue: { rules } }],
  });
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(DispatchRulesSummaryCard);
  fixture.componentRef.setInput('tenantId', 't1');
  fixture.componentRef.setInput('scopeType', inputs.scopeType ?? 'LOCATION');
  fixture.componentRef.setInput('brandId', 'b1');
  fixture.componentRef.setInput('locationId', 'l1');
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
  return { fixture, host: fixture.nativeElement as HTMLElement };
}

describe('DispatchRulesSummaryCard (settings.md §10.3 Card 3, ADR 0142)', () => {
  it('says orders are sent as before when nothing is published, and still links to where rules are written', async () => {
    const { host } = await render(vi.fn().mockResolvedValue({ value: BUILT_IN, version: 0 }));

    expect(host.querySelector('[data-testid="dispatch-summary-built-in"]')?.textContent).toContain(
      'sent as before',
    );
    expect(host.querySelector('[data-testid="dispatch-summary-link"]')?.getAttribute('href')).toBe(
      '/delivery/dispatch-rules',
    );
  });

  it('lists the rules in force, in order, each with how it sends the order, and shows a switched-off one struck through', async () => {
    const { host } = await render(vi.fn().mockResolvedValue({ value: PUBLISHED, version: 2 }));

    const rows = [
      ...host.querySelectorAll<HTMLElement>('[data-testid="dispatch-summary-rules"] li'),
    ];
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain('Far zone: Yandex first');
    expect(rows[0].textContent).toContain('Partners first');
    expect(rows[1].textContent).toContain('tablets');
    expect(rows[1].classList.contains('summary__off')).toBe(true);
  });

  it('reads at the scope the settings bar is on and nothing narrower', async () => {
    const rules = vi.fn().mockResolvedValue({ value: BUILT_IN, version: 0 });

    await render(rules, { scopeType: 'BRAND' });

    expect(rules).toHaveBeenCalledWith({ tenantId: 't1', brandId: 'b1' });
  });

  it('shows a failure instead of a blank card', async () => {
    const refused = new ApiError('INSUFFICIENT_CAPABILITY', 403, { status: 403 }, null);

    const { host } = await render(vi.fn().mockRejectedValue(refused));

    expect(host.querySelector('[data-testid="dispatch-summary-error"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="dispatch-summary-link"]')).not.toBeNull();
  });
});
