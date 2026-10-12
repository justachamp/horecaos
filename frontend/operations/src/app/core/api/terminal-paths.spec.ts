import { describe, expect, it } from 'vitest';

import { terminalPaths } from './terminal-paths';

const scope = { tenantId: 't 1', brandId: 'b1', locationId: 'l1' };

describe('terminalPaths', () => {
  it('puts the terminal-session family under the branch, where the ordinary capability strategy applies', () => {
    const base = '/api/v1/tenants/t%201/brands/b1/locations/l1/terminal-session';
    expect(terminalPaths.roster(scope)).toBe(`${base}/roster`);
    expect(terminalPaths.unlocks(scope)).toBe(`${base}/unlocks`);
    expect(terminalPaths.pinActivations(scope)).toBe(`${base}/pin-activations`);
    expect(terminalPaths.pinChanges(scope)).toBe(`${base}/pin-changes`);
    expect(terminalPaths.touch(scope)).toBe(`${base}/touch`);
    expect(terminalPaths.locks(scope)).toBe(`${base}/locks`);
  });

  it('builds the registry, its device record, revoke and history', () => {
    expect(terminalPaths.terminals(scope)).toBe(
      '/api/v1/tenants/t%201/brands/b1/locations/l1/terminals',
    );
    expect(terminalPaths.terminal(scope, 'd/1')).toBe(
      '/api/v1/tenants/t%201/brands/b1/locations/l1/terminals/d%2F1',
    );
    expect(terminalPaths.terminalRevoke(scope, 'd1')).toContain('/terminals/d1/revoke');
    expect(terminalPaths.terminalSessions(scope, 'd1')).toContain('/terminals/d1/sessions');
  });

  it('keeps the policy at the tenant and «Мой PIN» without a member id', () => {
    expect(terminalPaths.policy('t1')).toBe('/api/v1/operations/tenants/t1/terminal-policy');
    expect(terminalPaths.myPin('t1')).toBe('/api/v1/operations/tenants/t1/staff/me/pin');
    expect(terminalPaths.myPinActivations('t1')).toBe(
      '/api/v1/operations/tenants/t1/staff/me/pin-activations',
    );
  });

  it('addresses a person at the tenant, or at one branch for a branch manager', () => {
    expect(terminalPaths.memberPin('t1', 'm1')).toBe(
      '/api/v1/operations/tenants/t1/staff/members/m1/pin',
    );
    expect(terminalPaths.memberPinActivations('t1', 'm1', scope)).toBe(
      '/api/v1/operations/tenants/t1/brands/b1/locations/l1/staff/members/m1/pin-activations',
    );
    expect(terminalPaths.memberPinReset('t1', 'm1')).toBe(
      '/api/v1/operations/tenants/t1/staff/members/m1/pin-reset',
    );
  });

  it('builds the kiosk endpoints under the branch', () => {
    const base = '/api/v1/tenants/t%201/brands/b1/locations/l1/kiosk';
    expect(terminalPaths.kioskEnrolment(scope, 'AB12 CD')).toBe(`${base}/enrolments/AB12%20CD`);
    expect(terminalPaths.kioskApprove(scope, 'ABCD1234')).toBe(
      `${base}/enrolments/ABCD1234/approve`,
    );
    expect(terminalPaths.kioskChannels(scope)).toBe(`${base}/channels`);
    expect(terminalPaths.kioskDevices(scope)).toBe(`${base}/devices`);
    expect(terminalPaths.kioskDevice(scope, 'd1')).toBe(`${base}/devices/d1`);
    expect(terminalPaths.kioskRevoke(scope, 'd1')).toBe(`${base}/devices/d1/revoke`);
    expect(terminalPaths.kioskConfiguration(scope)).toBe(`${base}/configuration`);
  });
});
