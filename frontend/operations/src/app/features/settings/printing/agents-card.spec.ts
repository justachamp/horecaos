import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { AgentView, PrintingApi } from './printing-api';
import { AgentsCard } from './agents-card';
import { click, el, flush, type } from './printing-dom';
import { agent } from './printing-fixtures';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

async function render(
  api: Partial<PrintingApi>,
  agents: readonly AgentView[],
): Promise<{ fixture: ComponentFixture<AgentsCard>; changed: ReturnType<typeof vi.fn> }> {
  await TestBed.configureTestingModule({
    imports: [AgentsCard],
    providers: [{ provide: PrintingApi, useValue: api }],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(AgentsCard);
  fixture.componentRef.setInput('scope', SCOPE);
  fixture.componentRef.setInput('agents', agents);
  const changed = vi.fn();
  fixture.componentInstance.changed.subscribe(changed);
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  return { fixture, changed };
}

describe('AgentsCard', () => {
  it('lists an agent with who approved it and the printers it reports', async () => {
    const { fixture } = await render({}, [agent()]);
    const row = el(fixture, '[data-agent="agent-1"]');
    expect(row.textContent).toContain('Pilot branch mini PC');
    expect(row.textContent).toContain('manager@horecaos.uz');
    expect(row.textContent).toContain('grill');
    expect(row.textContent).toContain('Online');
  });

  it('shows what a typed code claims before it is approved, then approves it by name', async () => {
    const pendingAgent = vi.fn().mockResolvedValue({
      requestedClass: 'PRINT_AGENT',
      requestedLabel: 'pilot-mini-pc',
      expiresAt: '2026-10-09T09:00:00Z',
    });
    const approveAgent = vi.fn().mockResolvedValue(agent().device);
    const { fixture, changed } = await render({ pendingAgent, approveAgent }, []);

    type(fixture, '[data-testid="print-agent-code"]', ' ABCD-1234 ');
    await click(fixture, '[data-testid="print-agent-lookup"]');

    expect(pendingAgent).toHaveBeenCalledWith(SCOPE, 'ABCD-1234');
    expect(el(fixture, '[data-testid="print-agent-claim"]').textContent).toContain('pilot-mini-pc');
    // The label the agent gave itself is the starting name.
    expect(el<HTMLInputElement>(fixture, '[data-testid="print-agent-name"]').value).toBe(
      'pilot-mini-pc',
    );

    type(fixture, '[data-testid="print-agent-name"]', 'Grill mini PC');
    await click(fixture, '[data-testid="print-agent-approve"]');

    expect(approveAgent).toHaveBeenCalledWith(SCOPE, 'ABCD-1234', 'Grill mini PC');
    expect(changed).toHaveBeenCalledOnce();
  });

  it('does not offer to approve a code that nobody is waiting with', async () => {
    const pendingAgent = vi
      .fn()
      .mockRejectedValue(new ApiError(ApiErrorCode.RESOURCE_NOT_FOUND, 404, null, null));
    const { fixture } = await render({ pendingAgent }, []);

    type(fixture, '[data-testid="print-agent-code"]', 'NOPE');
    await click(fixture, '[data-testid="print-agent-lookup"]');

    expect(el(fixture, '[data-testid="print-agents-error"]').textContent).toContain(
      'No agent is waiting',
    );
    expect(el(fixture, '[data-testid="print-agent-approve"]')).toBeNull();
  });

  it('revokes an agent only with a reason', async () => {
    const revokeAgent = vi.fn().mockResolvedValue(undefined);
    const { fixture, changed } = await render({ revokeAgent }, [agent()]);

    await click(fixture, '[data-testid="print-agent-revoke-agent-1"]');
    expect(
      el<HTMLButtonElement>(fixture, '[data-testid="print-agent-revoke-confirm"]').disabled,
    ).toBe(true);

    type(fixture, '[data-testid="print-agent-revoke-reason"]', 'machine replaced');
    await click(fixture, '[data-testid="print-agent-revoke-confirm"]');

    expect(revokeAgent).toHaveBeenCalledWith(SCOPE, 'agent-1', 'machine replaced');
    expect(changed).toHaveBeenCalledOnce();
  });

  it('offers no revoke on an agent that is already revoked', async () => {
    const revoked = agent({
      device: {
        ...agent().device,
        status: 'REVOKED',
        revokedBy: 'owner@horecaos.uz',
        revokedAt: '2026-10-09T07:00:00Z',
        revokedReason: 'lost',
      },
    });
    const { fixture } = await render({}, [revoked]);
    expect(el(fixture, '[data-testid="print-agent-revoke-agent-1"]')).toBeNull();
    expect(el(fixture, '[data-agent="agent-1"]').textContent).toContain('owner@horecaos.uz');
  });
});
