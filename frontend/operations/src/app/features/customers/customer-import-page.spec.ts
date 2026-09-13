import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { CustomerImportPage } from './customer-import-page';
import { CustomerImportRow, CustomerImportStatus, CustomersApi } from './customers-api';

const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };

class FakeCurrentLocation {
  readonly scope = signal<LocationScope | null>(SCOPE);
}

function csvFile(name: string, content: string): File {
  return new File([content], name, { type: 'text/csv' });
}

describe('CustomerImportPage', () => {
  let fixture: ComponentFixture<CustomerImportPage>;
  let api: {
    submitImport: ReturnType<typeof vi.fn>;
    importStatus: ReturnType<typeof vi.fn>;
    importRows: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    api = {
      submitImport: vi.fn().mockResolvedValue('run-1'),
      importStatus: vi.fn(),
      importRows: vi.fn().mockResolvedValue([]),
    };

    await TestBed.configureTestingModule({
      imports: [CustomerImportPage],
      providers: [
        provideRouter([]),
        { provide: CustomersApi, useValue: api },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(CustomerImportPage);
    fixture.detectChanges();
  });

  it('finds the phone column under a recognised alias, case- and punctuation-insensitively', async () => {
    const file = csvFile('customers.csv', 'Full Name,Phone Number\nAyubkhon,+998901112233\n');

    const rows = await fixture.componentInstance['adapter'].parsePreview(file);

    expect(rows).toEqual([{ rowNumber: 1, cells: ['+998901112233'] }]);
  });

  it('leaves the cell empty when no recognised phone column exists', async () => {
    const file = csvFile('customers.csv', 'Full Name,City\nAyubkhon,Tashkent\n');

    const rows = await fixture.componentInstance['adapter'].parsePreview(file);

    expect(rows).toEqual([{ rowNumber: 1, cells: [''] }]);
  });

  it('submits with the scope’s brandId and the file’s own name and content', async () => {
    const file = csvFile('my-customers.csv', 'phone\n+998901112233\n');

    const runId = await fixture.componentInstance['adapter'].submit(file, true);

    expect(runId).toBe('run-1');
    expect(api.submitImport).toHaveBeenCalledWith(
      SCOPE,
      { brandId: 'brand-1', fileName: 'my-customers.csv', content: 'phone\n+998901112233\n' },
      true,
    );
  });

  it('maps a status poll into the wizard’s counts shape', async () => {
    const status: CustomerImportStatus = {
      runId: 'run-1',
      status: 'RUNNING',
      dryRun: true,
      sourceFileName: 'customers.csv',
      rowsTotal: 10,
      rowsProcessed: 4,
      rowsCreatedCustomer: 2,
      rowsMatchedCustomer: 1,
      rowsRejected: 1,
      failureReason: null,
    };
    api.importStatus.mockResolvedValue(status);

    const snapshot = await fixture.componentInstance['adapter'].poll('run-1');

    expect(snapshot).toEqual({
      status: 'RUNNING',
      counts: { rowsTotal: 10, rowsProcessed: 4, created: 2, matched: 1, rejected: 1 },
      failureReason: null,
    });
  });

  it('translates each row’s outcome and reject reason, and never carries a phone number', async () => {
    const rows: readonly CustomerImportRow[] = [
      { rowNumber: 1, outcome: 'CREATED_CUSTOMER', customerAccountId: 'acc-1', rejectReason: null },
      {
        rowNumber: 2,
        outcome: 'REJECTED',
        customerAccountId: null,
        rejectReason: 'MALFORMED_PHONE',
      },
    ];
    api.importRows.mockResolvedValue(rows);

    const outcomes = await fixture.componentInstance['adapter'].rows('run-1');

    expect(outcomes).toEqual([
      { rowNumber: 1, outcome: 'Created', detail: null, tone: 'success' },
      { rowNumber: 2, outcome: 'Rejected', detail: 'Not a phone number', tone: 'danger' },
    ]);
    expect(JSON.stringify(outcomes)).not.toMatch(/\+?\d{7,}/);
  });
});
