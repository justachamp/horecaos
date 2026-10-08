import { langIdOf, platformTag } from './lang.service';

/**
 * The one mapping the storefront keeps between its catalogue ids and the platform's language tags
 * (ADR 0149): Uzbek only, because Uzbek is written in two scripts and a bare `uz` does not say which.
 */
describe('platformTag', () => {
  it('says Uzbek the way the platform does, with its script', () => {
    expect(platformTag('uz')).toBe('uz-Latn');
  });

  it('leaves a language whose id is already its tag alone', () => {
    expect(platformTag('ru')).toBe('ru');
    expect(platformTag('en')).toBe('en');
  });

  it("reads a platform tag back as this app's id, and a tag it has no id for as it was", () => {
    expect(langIdOf('uz-Latn')).toBe('uz');
    expect(langIdOf('ru')).toBe('ru');
    expect(langIdOf('kk')).toBe('kk');
  });

  it('round-trips every id this app has a catalogue for', () => {
    for (const id of ['uz', 'ru', 'en']) {
      expect(langIdOf(platformTag(id))).toBe(id);
    }
  });
});
