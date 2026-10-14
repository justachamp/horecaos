import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { ScopeGrant } from '../../core/auth/session-context';
import { RoleDescriptor } from './staff-api';
import { StaffJobNames } from './staff-job-names';
import { roleLabel } from './staff-role-labels';

const OWN: RoleDescriptor = {
  code: 'job-3f9a1c0b2d',
  scopeType: 'LOCATION',
  capabilities: ['order.read'],
  custom: true,
  name: 'Старший кассир',
};

describe('StaffJobNames (ADR 0186)', () => {
  it('learns the company’s own names from a job list and not the code-owned ones', () => {
    const names = TestBed.inject(StaffJobNames);

    names.remember([
      OWN,
      { code: 'tenant-owner', scopeType: 'TENANT', capabilities: [], custom: false, name: null },
    ]);

    expect(names.nameOf('job-3f9a1c0b2d')).toBe('Старший кассир');
    expect(names.nameOf('tenant-owner')).toBeNull();
  });

  it('learns a name from the session context of the person holding the job', () => {
    const names = TestBed.inject(StaffJobNames);
    const scope: ScopeGrant = {
      scope: { type: 'LOCATION', tenantId: 't', brandId: 'b', locationId: 'l' },
      roleCode: 'job-3f9a1c0b2d',
      roleName: 'Старший кассир',
      capabilities: ['order.read'],
    };

    names.rememberFromScopes([scope]);

    expect(names.nameOf('job-3f9a1c0b2d')).toBe('Старший кассир');
  });

  it('keeps a name after the job leaves the list, so history still says what it was called', () => {
    const names = TestBed.inject(StaffJobNames);
    names.remember([OWN]);

    names.remember([]);

    expect(names.nameOf('job-3f9a1c0b2d')).toBe('Старший кассир');
  });

  it('puts the translated name first, then the company’s, then the code - never a blank', () => {
    const translate = (key: string) => `[${key}]`;

    expect(roleLabel('tenant-owner', translate, 'ignored')).toBe('[staff.role.tenant-owner]');
    expect(roleLabel('job-3f9a1c0b2d', translate, 'Старший кассир')).toBe('Старший кассир');
    expect(roleLabel('job-3f9a1c0b2d', translate, null)).toBe('job-3f9a1c0b2d');
    expect(roleLabel('job-3f9a1c0b2d', translate)).toBe('job-3f9a1c0b2d');
  });
});
