import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { DeniedState } from './denied-state';
import { EmptyState } from './empty-state';
import { LockedState } from './locked-state';

/**
 * The three together, in one file, because the assertion that matters is the
 * one *between* them: the IA's whole point about `EmptyState`, `DeniedState`
 * and `LockedState` is that they are different — "one is an upsell, one is a
 * wall" — and a spec per component could not state that.
 */
describe('EmptyState, DeniedState and LockedState', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  describe('EmptyState', () => {
    it('says what is not there, and nothing about permission', () => {
      const fixture = TestBed.createComponent(EmptyState);
      fixture.componentRef.setInput('titleKey', 'orders.queue.empty.default');
      fixture.detectChanges();

      const host = fixture.nativeElement as HTMLElement;
      expect(host.querySelector('[data-testid="q-empty-state"]')?.textContent).toContain(
        'No orders yet',
      );
      expect(host.textContent).not.toContain('access');
    });

    it('adds a second line only when one is given', () => {
      const fixture = TestBed.createComponent(EmptyState);
      fixture.componentRef.setInput('titleKey', 'orders.queue.empty.default');
      fixture.detectChanges();
      expect(
        (fixture.nativeElement as HTMLElement).querySelectorAll('.q-state__body'),
      ).toHaveLength(0);

      fixture.componentRef.setInput('bodyKey', 'orders.queue.empty.attention');
      fixture.detectChanges();
      expect(
        (fixture.nativeElement as HTMLElement).querySelectorAll('.q-state__body'),
      ).toHaveLength(1);
    });
  });

  describe('DeniedState', () => {
    it('names the capability as data, not as a translated phrase', () => {
      const fixture = TestBed.createComponent(DeniedState);
      fixture.componentRef.setInput('capability', 'ORDER_CANCEL');
      fixture.detectChanges();

      const code = (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="q-denied-state-capability"]',
      );
      // The exact token a manager types into the role editor. Translating it
      // would send the operator to ask for something nobody can look up.
      expect(code?.textContent?.trim()).toBe('ORDER_CANCEL');
      expect(code?.className).toContain('q-mono');
    });

    it('always says who can grant it, capability named or not', () => {
      const fixture = TestBed.createComponent(DeniedState);
      fixture.detectChanges();

      const host = fixture.nativeElement as HTMLElement;
      expect(host.querySelector('[data-testid="q-denied-state-capability"]')).toBeNull();
      expect(host.textContent).toContain('A manager who can edit staff roles can grant it.');
    });

    it('lets a screen override who to ask', () => {
      const fixture = TestBed.createComponent(DeniedState);
      fixture.componentRef.setInput('askKey', 'ui.locked.ask');
      fixture.detectChanges();

      expect((fixture.nativeElement as HTMLElement).textContent).toContain(
        'The account owner can add it to the plan.',
      );
    });
  });

  describe('LockedState', () => {
    it('reads as an upsell, not as a refusal of this operator', () => {
      const fixture = TestBed.createComponent(LockedState);
      fixture.componentRef.setInput('module', 'LOYALTY');
      fixture.detectChanges();

      const host = fixture.nativeElement as HTMLElement;
      expect(host.querySelector('[data-testid="q-locked-state"]')?.textContent).toContain(
        'Not included in this plan',
      );
      expect(host.querySelector('[data-testid="q-locked-state-module"]')?.textContent?.trim()).toBe(
        'LOYALTY',
      );
      // The distinction the IA insists on: a lock is about the plan, a denial
      // is about the grant, and neither may borrow the other's sentence.
      expect(host.textContent).not.toContain('You do not have access');
    });
  });

  it('are three different sentences, not one component with a mode', () => {
    const rendered = (): Record<string, string> => {
      const empty = TestBed.createComponent(EmptyState);
      empty.componentRef.setInput('titleKey', 'customers.empty');
      empty.detectChanges();

      const denied = TestBed.createComponent(DeniedState);
      denied.detectChanges();

      const locked = TestBed.createComponent(LockedState);
      locked.detectChanges();

      return {
        empty: (empty.nativeElement as HTMLElement).textContent ?? '',
        denied: (denied.nativeElement as HTMLElement).textContent ?? '',
        locked: (locked.nativeElement as HTMLElement).textContent ?? '',
      };
    };

    const { empty, denied, locked } = rendered();
    expect(new Set([empty.trim(), denied.trim(), locked.trim()]).size).toBe(3);
  });
});
