import type { BrandConfig } from './app-config';
import { applyBrand } from './apply-brand';
import { NEUTRAL_BRAND } from './load-config';

const CONFIGURED_BRAND: BrandConfig = {
  displayName: 'Tandir House',
  logoUrl: 'https://cdn.example.com/tandir-house/logo.svg',
  theme: {
    accent: '#c0392b',
    accentDeep: '#7b241c',
  },
};

/** A minimal stand-in for `Document`: just enough of the two things `applyBrand` touches. */
function fakeDocument(): Document {
  const style = new Map<string, string>();
  return {
    title: '',
    documentElement: {
      style: {
        setProperty: (name: string, value: string) => style.set(name, value),
        getPropertyValue: (name: string) => style.get(name) ?? '',
      },
    },
  } as unknown as Document;
}

describe('applyBrand', () => {
  it('sets the document title to the configured brand name -- proving it is not hardcoded', () => {
    const doc = fakeDocument();

    applyBrand(CONFIGURED_BRAND, doc);

    expect(doc.title).toBe('Tandir House');
  });

  it('writes the configured accent colours onto --brand-accent and --brand-accent-deep', () => {
    const doc = fakeDocument();

    applyBrand(CONFIGURED_BRAND, doc);

    const root = doc.documentElement;
    expect(root.style.getPropertyValue('--brand-accent')).toBe('#c0392b');
    expect(root.style.getPropertyValue('--brand-accent-deep')).toBe('#7b241c');
  });

  it('applies the neutral brand -- never the legacy product name -- when config supplied none', () => {
    const doc = fakeDocument();

    applyBrand(NEUTRAL_BRAND, doc);

    expect(doc.title.toLowerCase()).not.toContain('jizbiz');
    expect(doc.title).toBe(NEUTRAL_BRAND.displayName);
    expect(doc.documentElement.style.getPropertyValue('--brand-accent')).toBe(
      NEUTRAL_BRAND.theme.accent,
    );
  });

  it('defaults to the real document when none is passed', () => {
    expect(() => applyBrand(CONFIGURED_BRAND)).not.toThrow();
    expect(document.title).toBe('Tandir House');
  });
});
