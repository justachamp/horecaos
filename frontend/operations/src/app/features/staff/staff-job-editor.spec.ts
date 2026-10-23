import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { CapabilityEntry, RoleDescriptor } from './staff-api';
import { JobEditorMode, JobSubmission, StaffJobEditor } from './staff-job-editor';

const CATALOGUE: readonly CapabilityEntry[] = [
  { code: 'order.read', area: 'order', action: 'read', held: true, composable: true },
  { code: 'order.cancel', area: 'order', action: 'cancel', held: true, composable: true },
  {
    code: 'customer.pii.export',
    area: 'customer',
    action: 'pii.export',
    held: false,
    composable: true,
  },
  {
    code: 'iam.grant.manage',
    area: 'iam',
    action: 'grant.manage',
    held: true,
    composable: false,
  },
];

const OWN: RoleDescriptor = {
  code: 'job-3f9a1c0b2d',
  scopeType: 'LOCATION',
  capabilities: ['order.cancel', 'order.read'],
  custom: true,
  name: 'Старший кассир',
  version: 3,
  status: 'ACTIVE',
};

const OWNER_JOB: RoleDescriptor = {
  code: 'tenant-owner',
  scopeType: 'TENANT',
  capabilities: ['customer.pii.export', 'iam.grant.manage', 'order.cancel', 'order.read'],
};

describe('StaffJobEditor (ADR 0186, row 9.1b)', () => {
  let fixture: ComponentFixture<StaffJobEditor>;
  let submitted: JobSubmission[];
  let retired: { code: string; reason: string; version: number }[];
  let dismissed: number;

  async function open(mode: JobEditorMode): Promise<void> {
    await TestBed.configureTestingModule({ imports: [StaffJobEditor] }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(StaffJobEditor);
    submitted = [];
    retired = [];
    dismissed = 0;
    fixture.componentInstance.submitted.subscribe((value) => submitted.push(value));
    fixture.componentInstance.retired.subscribe((value) => retired.push(value));
    fixture.componentInstance.dismiss.subscribe(() => (dismissed += 1));
    fixture.componentRef.setInput('catalogue', CATALOGUE);
    fixture.componentRef.setInput('mode', mode);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  beforeEach(() => {
    localStorage.clear();
  });

  function el<T extends HTMLElement>(testId: string): T {
    return fixture.nativeElement.querySelector(`[data-testid="${testId}"]`) as T;
  }

  function type(testId: string, value: string): void {
    const input = el<HTMLInputElement>(testId);
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function tick(code: string): void {
    el<HTMLInputElement>(`job-capability-${code}`).click();
    fixture.detectChanges();
  }

  function text(): string {
    return fixture.nativeElement.textContent as string;
  }

  describe('a new job', () => {
    beforeEach(async () => {
      await open({ kind: 'new' });
    });

    it('is blank, offers the three levels and refuses to save until it has a name, something to do and a reason', () => {
      expect(el<HTMLInputElement>('job-name').value).toBe('');
      expect(el<HTMLSelectElement>('job-level').value).toBe('LOCATION');
      expect([...el<HTMLSelectElement>('job-level').options].map((option) => option.value)).toEqual(
        ['TENANT', 'BRAND', 'LOCATION'],
      );

      el<HTMLButtonElement>('job-save').click();
      fixture.detectChanges();

      expect(submitted).toEqual([]);
      expect(el('job-name-required')).not.toBeNull();
      expect(el('job-none-ticked')).not.toBeNull();
      expect(el('job-reason-required')).not.toBeNull();
    });

    it('sends the name, the level, the ticked codes in order and the reason - and no copiedFrom', () => {
      type('job-name', '  Старший кассир  ');
      el<HTMLSelectElement>('job-level').value = 'BRAND';
      el<HTMLSelectElement>('job-level').dispatchEvent(new Event('change'));
      tick('order.read');
      tick('order.cancel');
      type('job-reason', 'The evening shift needs one');

      el<HTMLButtonElement>('job-save').click();

      expect(submitted).toEqual([
        {
          kind: 'create',
          request: {
            name: 'Старший кассир',
            scopeType: 'BRAND',
            capabilities: ['order.cancel', 'order.read'],
            reason: 'The evening shift needs one',
          },
        },
      ]);
      expect('copiedFrom' in (submitted[0] as { request: object }).request).toBe(false);
    });

    it('shows what the author cannot give as locked, with why, and ticking it does nothing', () => {
      expect(el<HTMLInputElement>('job-capability-customer.pii.export').disabled).toBe(true);
      expect(el('job-locked-customer.pii.export').textContent).toContain('do not have yourself');
      expect(el<HTMLInputElement>('job-capability-iam.grant.manage').disabled).toBe(true);
      expect(el('job-locked-iam.grant.manage').textContent).toContain('Never part of a job');

      el<HTMLInputElement>('job-capability-customer.pii.export').click();
      fixture.detectChanges();

      expect(el<HTMLInputElement>('job-capability-customer.pii.export').checked).toBe(false);
      expect(el('job-selected-count').textContent).toContain('0');
    });

    it('searches the list by the sentence, the dotted code or the area', () => {
      type('job-capability-search', 'cancel');
      expect(el('job-capability-order.cancel')).not.toBeNull();
      expect(el('job-capability-order.read')).toBeNull();

      type('job-capability-search', 'pii.export');
      expect(el('job-capability-customer.pii.export')).not.toBeNull();
      expect(el('job-capability-order.cancel')).toBeNull();

      type('job-capability-search', 'zzzz');
      expect(fixture.nativeElement.querySelectorAll('fieldset')).toHaveLength(0);
    });

    it('dismisses without sending anything', () => {
      el<HTMLButtonElement>('job-cancel').click();

      expect(dismissed).toBe(1);
      expect(submitted).toEqual([]);
    });
  });

  describe('a copy', () => {
    it('starts from what the author may give of the source, says how many it left out, and records where it came from', async () => {
      await open({ kind: 'copy', source: OWNER_JOB });

      expect(el<HTMLInputElement>('job-capability-order.read').checked).toBe(true);
      expect(el<HTMLInputElement>('job-capability-order.cancel').checked).toBe(true);
      expect(el<HTMLInputElement>('job-capability-customer.pii.export').checked).toBe(false);
      expect(el('job-trimmed').textContent).toContain('2');
      expect(el<HTMLSelectElement>('job-level').value).toBe('TENANT');
      expect(text()).toContain('Copy of “Owner”');

      type('job-name', 'Главный кассир');
      type('job-reason', 'Template');
      el<HTMLButtonElement>('job-save').click();

      expect(submitted).toEqual([
        {
          kind: 'create',
          request: {
            name: 'Главный кассир',
            scopeType: 'TENANT',
            capabilities: ['order.cancel', 'order.read'],
            copiedFrom: 'tenant-owner',
            reason: 'Template',
          },
        },
      ]);
    });

    it('can copy a job of the company’s own', async () => {
      await open({ kind: 'copy', source: OWN });

      expect(el('job-trimmed')).toBeNull();
      expect(el<HTMLSelectElement>('job-level').value).toBe('LOCATION');
      expect(text()).toContain('Copy of “Старший кассир”');
    });
  });

  describe('an edit', () => {
    it('opens on the job, fixes its level, says how many people the change reaches, and sends the version it was opened at', async () => {
      await open({ kind: 'edit', job: OWN, holders: 4 });

      expect(el<HTMLInputElement>('job-name').value).toBe('Старший кассир');
      expect(el('job-level')).toBeNull();
      expect(el('job-level-fixed').textContent).toContain('Location');
      expect(text()).toContain('The level of a job does not change');
      expect(el('job-holders').textContent).toContain('4 people hold this job');

      tick('order.cancel'); // take it out
      type('job-reason', 'No more cancellations');
      el<HTMLButtonElement>('job-save').click();

      expect(submitted).toEqual([
        {
          kind: 'update',
          code: 'job-3f9a1c0b2d',
          request: {
            name: 'Старший кассир',
            capabilities: ['order.read'],
            reason: 'No more cancellations',
          },
          expectedVersion: 3,
        },
      ]);
    });

    it('says when nobody holds the job yet', async () => {
      await open({ kind: 'edit', job: OWN, holders: 0 });

      expect(el('job-holders').textContent).toContain('Nobody holds this job yet');
    });

    it('lets a capability the author cannot give be taken out and blocks saving while it stays in', async () => {
      const withPii: RoleDescriptor = {
        ...OWN,
        capabilities: ['customer.pii.export', 'order.read'],
      };
      await open({ kind: 'edit', job: withPii, holders: 1 });
      type('job-reason', 'Tidy up');

      expect(el<HTMLInputElement>('job-capability-customer.pii.export').checked).toBe(true);
      expect(el<HTMLInputElement>('job-capability-customer.pii.export').disabled).toBe(false);
      expect(el('job-must-drop')).not.toBeNull();
      el<HTMLButtonElement>('job-save').click();
      expect(submitted).toEqual([]);

      tick('customer.pii.export');
      expect(el('job-must-drop')).toBeNull();
      el<HTMLButtonElement>('job-save').click();
      expect(submitted).toHaveLength(1);
    });

    it('starts over when the host opens it for another job', async () => {
      await open({ kind: 'edit', job: OWN, holders: 0 });
      type('job-name', 'Half-typed');

      fixture.componentRef.setInput('mode', {
        kind: 'edit',
        job: { ...OWN, code: 'job-0000000001', name: 'Бариста', capabilities: ['order.read'] },
        holders: 0,
      });
      fixture.detectChanges();

      expect(el<HTMLInputElement>('job-name').value).toBe('Бариста');
      expect(el<HTMLInputElement>('job-capability-order.cancel').checked).toBe(false);
    });

    it('refuses to close a job somebody holds, and says who is in the way', async () => {
      await open({ kind: 'edit', job: OWN, holders: 2 });

      expect(el('job-retire-blocked').textContent).toContain('Still held by 2 people');
      expect(el('job-retire-open')).toBeNull();
      expect(el('job-retire-confirm')).toBeNull();
    });

    it('closes a job nobody holds once it has a reason', async () => {
      await open({ kind: 'edit', job: OWN, holders: 0 });

      el<HTMLButtonElement>('job-retire-open').click();
      fixture.detectChanges();
      expect(el<HTMLButtonElement>('job-retire-confirm').disabled).toBe(true);

      type('job-retire-reason', 'Replaced by the barista job');
      el<HTMLButtonElement>('job-retire-confirm').click();

      expect(retired).toEqual([
        { code: 'job-3f9a1c0b2d', reason: 'Replaced by the barista job', version: 3 },
      ]);
    });
  });

  it('shows the platform’s refusal beside the form', async () => {
    await open({ kind: 'new' });
    fixture.componentRef.setInput('serverError', 'Another active job already has this name');
    fixture.detectChanges();

    expect(el('job-error').textContent).toContain('already has this name');
  });
});
