import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { Toasts } from '../../shared/ui/toast';
import { SettingsSaved } from './settings-saved';

describe('SettingsSaved', () => {
  let saved: SettingsSaved;
  let toasts: Toasts;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
    toasts = TestBed.inject(Toasts);
    toasts.clear();
    saved = TestBed.inject(SettingsSaved);
  });

  const shown = (): string[] => toasts.visible().map((toast) => toast.message);

  it('names the branch a setting was written for, with the value (settings.md §1.3)', () => {
    saved.announce('set', 'Late threshold', { level: 'LOCATION', name: 'Chilanzar' }, '15 min');

    expect(shown()).toEqual(['Late threshold: 15 min — set for branch “Chilanzar”']);
  });

  it('names the brand, and the whole company, at those levels', () => {
    saved.announce('set', 'Cart expiry', { level: 'BRAND', name: 'Rayhon' });
    saved.announce('set', 'Cart expiry', { level: 'TENANT', name: null });

    expect(shown()).toEqual([
      'Cart expiry — set for brand “Rayhon”',
      'Cart expiry — set for the whole company',
    ]);
  });

  it('still names the level when the scope bar has not resolved the name', () => {
    saved.announce('set', 'Cart expiry', { level: 'LOCATION', name: null });

    expect(shown()).toEqual(['Cart expiry — set for the branch']);
  });

  it('says a revert and a published version in their own words', () => {
    saved.announce('reverted', 'Cart expiry', { level: 'BRAND', name: 'Rayhon' });
    saved.announce('published', 'Order acceptance', { level: 'TENANT', name: null });

    expect(shown()).toEqual([
      'Cart expiry — back to the inherited value for brand “Rayhon”',
      'Order acceptance — new version published for the whole company',
    ]);
  });

  it('is a success toast, so it does not interrupt a screen reader mid-sentence', () => {
    saved.announce('set', 'Cart expiry', { level: 'TENANT', name: null });

    expect(toasts.visible()[0].tone).toBe('success');
  });

  it('speaks Russian and Uzbek when the operator does', () => {
    TestBed.inject(I18n).setLocale('ru');
    saved.announce('set', 'Порог опоздания', { level: 'LOCATION', name: 'Чиланзар' }, '15 мин');
    TestBed.inject(I18n).setLocale('uz-Latn');
    saved.announce('set', 'Kechikish', { level: 'LOCATION', name: 'Chilanzar' }, '15 daq');

    expect(shown()).toEqual([
      'Порог опоздания: 15 мин — задано для филиала «Чиланзар»',
      'Kechikish: 15 daq — «Chilanzar» filiali uchun belgilandi',
    ]);
  });
});
