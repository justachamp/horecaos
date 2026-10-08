import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CurrentTenant } from '../../../core/auth/current-tenant';
import { AttendanceStatus } from './attendance-status';
import { StaffShiftsApi } from './staff-shifts-api';

describe('AttendanceStatus (ADR 0156)', () => {
  let api: { enabled: ReturnType<typeof vi.fn> };

  function status(tenantId: string | null): AttendanceStatus {
    api = { enabled: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        { provide: StaffShiftsApi, useValue: api },
        { provide: CurrentTenant, useValue: { ensureLoaded: () => Promise.resolve(), tenantId: () => tenantId } },
      ],
    });
    return TestBed.inject(AttendanceStatus);
  }

  beforeEach(() => TestBed.resetTestingModule());

  it('is off before it has been read', () => {
    expect(status('t').enabled()).toBeNull();
  });

  it('reads once and reports the answer', async () => {
    const attendance = status('t');
    api.enabled.mockResolvedValue(true);

    await attendance.ensureLoaded();
    await attendance.ensureLoaded();

    expect(attendance.enabled()).toBe(true);
    expect(api.enabled).toHaveBeenCalledTimes(1);
    expect(api.enabled).toHaveBeenCalledWith('t');
  });

  it('treats a read that fails as off: the safe direction', async () => {
    const attendance = status('t');
    api.enabled.mockRejectedValue(new Error('down'));

    await attendance.ensureLoaded();

    expect(attendance.enabled()).toBe(false);
  });

  it('is off, and asks nothing, when there is no tenant', async () => {
    const attendance = status(null);

    await attendance.ensureLoaded();

    expect(attendance.enabled()).toBe(false);
    expect(api.enabled).not.toHaveBeenCalled();
  });

  it('turns on in this session when the tenant has just switched it on', async () => {
    const attendance = status('t');
    api.enabled.mockResolvedValue(false);
    await attendance.ensureLoaded();

    attendance.markEnabled();

    expect(attendance.enabled()).toBe(true);
  });
});
