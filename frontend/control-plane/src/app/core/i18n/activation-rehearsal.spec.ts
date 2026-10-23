import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { REGISTRY_FIXTURE } from '../../../testing/platform-locales.fixture';
import {
  PlatformLocaleEntry,
  PlatformLocales,
  seedPlatformLocalesForTesting,
} from './platform-locales';

/**
 * ADR 0149's exit criterion, rehearsed in the control plane: a language declared live in the messages
 * tier is what the owner-invitation picker offers, with no change to the picker. The staff-interface
 * tier is left alone: the control plane has no catalogue for the new language, which is the one thing
 * it must add before a console is drawn in it.
 */
describe('activating a fifth language (ADR 0149 exit criteria)', () => {
  const FIFTH: PlatformLocaleEntry = {
    tag: 'zz',
    catalogCode: 'zz',
    inputAliases: [],
    script: 'LATN',
    direction: 'LTR',
    face: 'ibm-plex-sans',
    fallbackRank: 5,
    tiers: ['CONTENT', 'MESSAGES'],
    names: { ru: 'Пятый', en: 'Fifth', zz: 'Zzzz' },
  };

  beforeEach(() => {
    seedPlatformLocalesForTesting({
      ...REGISTRY_FIXTURE,
      locales: [...REGISTRY_FIXTURE.locales, FIFTH],
    });
  });

  afterEach(() => seedPlatformLocalesForTesting(REGISTRY_FIXTURE));

  it('is offered where an owner invitation is written (the messages tier) and not as a console language', () => {
    const registry = TestBed.inject(PlatformLocales);

    expect(registry.active('MESSAGES')).toEqual(['ru', 'uz-Latn', 'en', 'zz']);
    expect(registry.active('STAFF_UI')).toEqual(['ru', 'uz-Latn', 'en']);
    expect(registry.nameOf('zz', 'en')).toBe('Fifth');
    expect(registry.directionOf('zz')).toBe('ltr');
  });
});
