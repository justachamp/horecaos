import { vacancyContact } from './vacancy-contact';

describe('vacancyContact: a vacancy has one outbound contact and no application form', () => {
  describe('PHONE', () => {
    it('is a tel: link without the spaces and punctuation, shown as typed', () => {
      expect(vacancyContact({ type: 'PHONE', value: '+998 (90) 123-45-67' })).toEqual({
        label: '+998 (90) 123-45-67',
        href: 'tel:+998901234567',
        external: false,
      });
    });

    it.each(['call us', '12', '+998abc', 'tel:+998901234567; rm -rf'])(
      'is plain text for %j',
      (value) => {
        expect(vacancyContact({ type: 'PHONE', value }).href).toBeNull();
      },
    );
  });

  describe('EMAIL', () => {
    it('is a mailto: link', () => {
      expect(vacancyContact({ type: 'EMAIL', value: 'jobs@example.uz' })).toEqual({
        label: 'jobs@example.uz',
        href: 'mailto:jobs@example.uz',
        external: false,
      });
    });

    it.each([
      'jobs',
      'jobs@',
      '@example.uz',
      'a b@example.uz',
      'jobs@example.uz?cc=evil@example.com',
    ])('is plain text for %j', (value) => {
      expect(vacancyContact({ type: 'EMAIL', value }).href).toBeNull();
    });
  });

  describe('URL', () => {
    it('an https address is an external link', () => {
      expect(vacancyContact({ type: 'URL', value: 'https://example.uz/jobs/1' })).toEqual({
        label: 'https://example.uz/jobs/1',
        href: 'https://example.uz/jobs/1',
        external: true,
      });
    });

    it.each(['http://example.uz/', 'javascript:alert(1)', 'data:text/html,x', 'example.uz/jobs'])(
      'is plain text for %j',
      (value) => {
        const contact = vacancyContact({ type: 'URL', value });
        expect(contact.href).toBeNull();
        expect(contact.label).toBe(value);
      },
    );
  });

  describe('TELEGRAM', () => {
    it.each([
      '@horeca_jobs',
      'horeca_jobs',
      't.me/horeca_jobs',
      'https://t.me/horeca_jobs',
      'http://t.me/horeca_jobs',
    ])('reads %s as the handle horeca_jobs', (value) => {
      expect(vacancyContact({ type: 'TELEGRAM', value })).toEqual({
        label: '@horeca_jobs',
        href: 'https://t.me/horeca_jobs',
        external: true,
      });
    });

    it.each([
      '@a',
      'two words',
      '@bad-handle!',
      'https://evil.example/horeca_jobs',
      'javascript:alert(1)',
    ])('is plain text for %j', (value) => {
      expect(vacancyContact({ type: 'TELEGRAM', value }).href).toBeNull();
    });
  });
});
