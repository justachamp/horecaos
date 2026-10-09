import { describe, expect, it } from 'vitest';

import { messagesEn } from '../../../core/i18n/messages.en';
import { CONTENT_KINDS } from './content-api';
import { KIND_KEYS, PROBLEM_KEYS, problemText } from './content-texts';

const t = (key: string, values?: Readonly<Record<string, string | number>>): string => {
  const text = (messagesEn as Record<string, string>)[key];
  return Object.entries(values ?? {}).reduce(
    (acc, [name, value]) => acc.replace(`{${name}}`, String(value)),
    text,
  );
};

describe('content texts', () => {
  it('names every kind', () => {
    expect(Object.keys(KIND_KEYS).sort()).toEqual([...CONTENT_KINDS].sort());
  });

  it('has a sentence for every publication gate and health code ADR 0157 lists', () => {
    const adrCodes = [
      'CONTENT_DEFAULT_LOCALE_MISSING',
      'CONTENT_LOCALE_INCOMPLETE',
      'CONTENT_MEDIA_NOT_DISPLAYABLE',
      'CONTENT_MEDIA_ASPECT_MISMATCH',
      'CONTENT_TARGET_NOT_FOUND',
      'CONTENT_PROMOTION_NOT_FOUND',
      'CONTENT_LINK_HOST_NOT_ALLOWED',
      'CONTENT_CHANNEL_NOT_A_CONTENT_SURFACE',
      'CONTENT_PLACEMENT_FULL',
      'CONTENT_NOTHING_TO_PUBLISH',
      'CONTENT_VIDEO_LIMIT',
      'CONTENT_POSTER_REQUIRED',
      'CONTENT_REVISION_MEDIA_RELEASED',
      'CONTENT_KIND_NOT_ENABLED',
      'CONTENT_PROMOTION_NOT_LIVE',
      'CONTENT_MEDIA_NOT_RENDERED',
    ];
    for (const code of adrCodes) {
      expect(PROBLEM_KEYS[code], code).toBeDefined();
    }
  });

  it('renders a known code as its own sentence and any other with the server’s message', () => {
    expect(
      problemText(t, {
        field: 'media.primaryAssetId',
        code: 'CONTENT_POSTER_REQUIRED',
        message: 'x',
      }),
    ).toBe('A video needs a poster image');
    expect(
      problemText(t, {
        field: 'texts.ru.title',
        code: 'TOO_LONG',
        message: 'At most 80 characters',
      }),
    ).toBe('Needs attention: At most 80 characters');
  });
});
