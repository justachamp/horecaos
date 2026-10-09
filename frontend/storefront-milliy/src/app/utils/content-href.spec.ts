import { safeLink, vacancyContact } from './content-href';

describe('safeLink: the schemes a link in a body may carry', () => {
  it('lets an https address through, normalised, as an external link', () => {
    expect(safeLink('https://Example.uz/a b')).toEqual({
      href: 'https://example.uz/a%20b',
      external: true,
    });
  });

  it('lets a telephone number through, with the separators taken out', () => {
    expect(safeLink('tel:+998 90 123-45-67')).toEqual({
      href: 'tel:+998901234567',
      external: false,
    });
    expect(safeLink('TEL:(71)2345678')).toEqual({ href: 'tel:712345678', external: false });
  });

  it('lets a plain e-mail address through', () => {
    expect(safeLink('mailto:info@example.uz')).toEqual({
      href: 'mailto:info@example.uz',
      external: false,
    });
    expect(safeLink('MAILTO:Info@Example.uz')).toEqual({
      href: 'mailto:Info@Example.uz',
      external: false,
    });
  });

  it.each([
    'javascript:alert(1)',
    'JaVaScRiPt:alert(1)',
    'java\tscript:alert(1)',
    'vbscript:msgbox(1)',
    'data:text/html,<b>x</b>',
    'http://example.uz/plain',
    'ftp://example.uz/file',
    'file:///etc/passwd',
    'sms:+998901234567',
    '//example.uz/protocol-relative',
    '/relative/path',
    'example.uz',
    'https://user:secret@example.uz/',
    'https://',
    'tel:call-me',
    'tel:',
    'mailto:',
    'mailto:not an address',
    'mailto:a@b.uz?subject=x&bcc=evil@example.uz',
    'mailto:a@b.uz,c@d.uz',
    '',
    '   ',
  ])('refuses %j', (href) => {
    expect(safeLink(href)).toBeNull();
  });

  it('refuses what is not text', () => {
    expect(safeLink(null)).toBeNull();
    expect(safeLink(undefined)).toBeNull();
    expect(safeLink(42)).toBeNull();
    expect(safeLink({ href: 'https://example.uz' })).toBeNull();
  });
});

describe('vacancyContact: the one outbound contact of a vacancy', () => {
  it('is nothing when the vacancy publishes none', () => {
    expect(vacancyContact(undefined)).toBeNull();
    expect(vacancyContact({ type: 'PHONE', value: '' })).toBeNull();
    expect(vacancyContact({ type: 'PHONE', value: '   ' })).toBeNull();
  });

  it('dials a phone number', () => {
    expect(vacancyContact({ type: 'PHONE', value: '+998 90 123-45-67' })).toEqual({
      kind: 'tel',
      href: 'tel:+998901234567',
      label: '+998 90 123-45-67',
      external: false,
    });
  });

  it('writes to an e-mail address', () => {
    expect(vacancyContact({ type: 'EMAIL', value: 'hr@example.uz' })).toEqual({
      kind: 'mailto',
      href: 'mailto:hr@example.uz',
      label: 'hr@example.uz',
      external: false,
    });
  });

  it('opens a web or Telegram address that is https, in a new tab', () => {
    expect(vacancyContact({ type: 'URL', value: 'https://jobs.example.uz/cook?id=7' })).toEqual({
      kind: 'link',
      href: 'https://jobs.example.uz/cook?id=7',
      label: 'jobs.example.uz/cook?id=7',
      external: true,
    });
    expect(vacancyContact({ type: 'TELEGRAM', value: 'https://t.me/example_hr' })).toEqual({
      kind: 'link',
      href: 'https://t.me/example_hr',
      label: 't.me/example_hr',
      external: true,
    });
  });

  it.each([
    ['PHONE', 'call the manager'],
    ['EMAIL', 'not an address'],
    ['URL', 'javascript:alert(1)'],
    ['URL', 'http://jobs.example.uz'],
    ['TELEGRAM', '@example_hr'],
  ] as const)('shows a %s of %j as plain text, never as a link', (type, value) => {
    expect(vacancyContact({ type, value })).toEqual({ kind: 'text', label: value });
  });
});
