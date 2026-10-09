import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { PlatformLocales } from '../../core/i18n/platform-locales';
import { PREDICATE_TYPES, descriptorFor } from './audience-predicates';

describe('the audience predicate catalogue', () => {
  it('does not carry a list of languages: a customer’s language is read from the registry’s messages tier', () => {
    const descriptor = descriptorFor('PREFERRED_LOCALE');

    expect(descriptor.fixedValues).toBeNull();
    expect(descriptor.fixedValuesFromTier).toBe('MESSAGES');
    expect(
      TestBed.inject(PlatformLocales).active(descriptor.fixedValuesFromTier ?? 'MESSAGES'),
    ).toEqual(['ru', 'uz-Latn', 'en']);
  });

  it('keeps fixed values only where a predicate really has a closed set of its own', () => {
    const withLists = PREDICATE_TYPES.filter((type) => type.fixedValues !== null);

    expect(withLists).toEqual([]);
  });
});
