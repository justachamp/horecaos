import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { LockedState } from './locked-state';

describe('LockedState', () => {
  let fixture: ComponentFixture<LockedState>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LockedState],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(LockedState);
    fixture.detectChanges();
  });

  it('renders the lock', () => {
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="q-locked-state"]')).not.toBeNull();
  });

  it('renders with no buy CTA when nothing is projected into it', () => {
    // locked-state.ts's own doc: the CTA is a deliberate absence, not an
    // omission — there is no tenant-scoped purchase endpoint to wire one to.
    // This asserts the actions slot stays empty by default, so a later change
    // that projects an unwired button/link into `<q-locked-state>` fails a
    // test instead of shipping silently.
    const el = fixture.nativeElement as HTMLElement;
    const actions = el.querySelector('.q-state__actions');
    expect(actions).not.toBeNull();
    expect(actions?.querySelector('button, a')).toBeNull();
    expect(actions?.children.length).toBe(0);
  });
});
