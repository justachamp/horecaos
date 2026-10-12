import { describe, expect, it } from 'vitest';

import {
  NEW_SLIDE,
  albumImagesOf,
  bodyOf,
  diffDocuments,
  documentForSave,
  emptyDocument,
  groupOf,
  isLocaleComplete,
  linkFrom,
  linkOf,
  linkToWire,
  localeImageId,
  localesMissingTitle,
  popupOf,
  posterAssetId,
  primaryAssetId,
  slidesOf,
  textOf,
  vacancyOf,
  withAlbumImages,
  withBody,
  withCoverAsset,
  withGroup,
  withLink,
  withLocaleImage,
  withPopup,
  withPrimaryAsset,
  withSlides,
  withText,
  withVacancy,
} from './content-document';

const ID = (n: number): string => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;

describe('texts', () => {
  it('sets and reads a field of one language without touching another', () => {
    let doc = emptyDocument('BANNER');
    doc = withText(doc, 'ru', 'title', 'Скидка');
    doc = withText(doc, 'uz-Latn', 'title', 'Chegirma');
    doc = withText(doc, 'ru', 'subtitle', 'Только сегодня');

    expect(textOf(doc, 'ru', 'title')).toBe('Скидка');
    expect(textOf(doc, 'ru', 'subtitle')).toBe('Только сегодня');
    expect(textOf(doc, 'uz-Latn', 'title')).toBe('Chegirma');
    expect(textOf(doc, 'en', 'title')).toBe('');
  });

  it('removes a field when it is emptied, and a language when nothing is left in it', () => {
    let doc = withText(emptyDocument('BANNER'), 'ru', 'title', 'Скидка');
    doc = withText(doc, 'ru', 'title', '');

    expect(doc['texts']).toEqual({});
  });

  it('does not mutate the document it was given', () => {
    const original = emptyDocument('BANNER');
    withText(original, 'ru', 'title', 'x');

    expect(original['texts']).toEqual({});
  });

  it('calls a language complete only when its title is there', () => {
    let doc = withText(emptyDocument('BANNER'), 'ru', 'subtitle', 'sub only');
    expect(isLocaleComplete(doc, 'ru')).toBe(false);
    expect(localesMissingTitle(doc)).toEqual(['ru']);

    doc = withText(doc, 'ru', 'title', '   ');
    expect(isLocaleComplete(doc, 'ru')).toBe(false);

    doc = withText(doc, 'ru', 'title', 'Title');
    expect(isLocaleComplete(doc, 'ru')).toBe(true);
    expect(localesMissingTitle(doc)).toEqual([]);
  });
});

describe('media', () => {
  it('sets the primary asset with its poster, and clearing the asset clears the poster', () => {
    let doc = withPrimaryAsset(emptyDocument('BANNER'), ID(1), ID(2));
    expect(primaryAssetId(doc)).toBe(ID(1));
    expect(posterAssetId(doc)).toBe(ID(2));

    doc = withPrimaryAsset(doc, null);
    expect(primaryAssetId(doc)).toBeNull();
    expect(posterAssetId(doc)).toBeNull();
    expect(doc['media']).toEqual({});
  });

  it('gives a language its own image and takes it away again', () => {
    let doc = withText(emptyDocument('BANNER'), 'ru', 'title', 'Т');
    doc = withLocaleImage(doc, 'ru', ID(3));
    expect(localeImageId(doc, 'ru')).toBe(ID(3));
    expect(localeImageId(doc, 'en')).toBeNull();

    doc = withLocaleImage(doc, 'ru', null);
    expect(localeImageId(doc, 'ru')).toBeNull();
    expect(textOf(doc, 'ru', 'title')).toBe('Т');
  });

  it('sets and clears a cover', () => {
    const doc = withCoverAsset(emptyDocument('NEWS'), ID(4));
    expect(doc['coverAssetId']).toBe(ID(4));
    expect('coverAssetId' in withCoverAsset(doc, null)).toBe(false);
  });
});

describe('links', () => {
  it('writes only the keys a type uses, so a stale target never rides along', () => {
    expect(linkToWire({ type: 'CATEGORY', targetId: ID(1), url: 'https://x.uz' })).toEqual({
      type: 'CATEGORY',
      targetId: ID(1),
    });
    expect(linkToWire({ type: 'EXTERNAL', targetId: ID(1), url: ' https://shop.uz/a ' })).toEqual({
      type: 'EXTERNAL',
      url: 'https://shop.uz/a',
    });
    expect(linkToWire({ type: 'CART', targetId: ID(1), url: 'x' })).toEqual({ type: 'CART' });
    expect(linkToWire({ type: 'NONE', targetId: null, url: '' })).toEqual({ type: 'NONE' });
  });

  it('reads a link back, tolerating a missing or unknown one as NONE', () => {
    expect(linkFrom(undefined)).toEqual({ type: 'NONE', targetId: null, url: '' });
    expect(linkFrom({ type: 'TELEPORT' }).type).toBe('NONE');
    expect(
      linkOf(withLink(emptyDocument('BANNER'), { type: 'PRODUCT', targetId: ID(7), url: '' })),
    ).toEqual({ type: 'PRODUCT', targetId: ID(7), url: '' });
  });
});

describe('product groups', () => {
  it('writes a manual group as its members only, once each', () => {
    const doc = withGroup(emptyDocument('PRODUCT_GROUP'), {
      mode: 'MANUAL',
      members: [ID(1), ID(2), ID(1)],
      categoryId: ID(9),
      order: 'NEWEST',
      limit: 12,
    });

    expect(doc['members']).toEqual([
      { type: 'PRODUCT', id: ID(1) },
      { type: 'PRODUCT', id: ID(2) },
    ]);
    expect('categoryId' in doc).toBe(false);
    expect('order' in doc).toBe(false);
    expect('limit' in doc).toBe(false);
  });

  it('writes a by-category group as its category, order and limit only', () => {
    const doc = withGroup(emptyDocument('PRODUCT_GROUP'), {
      mode: 'BY_CATEGORY',
      members: [ID(1)],
      categoryId: ID(9),
      order: 'NEWEST',
      limit: 12,
    });

    expect(doc).toMatchObject({
      mode: 'BY_CATEGORY',
      members: [],
      categoryId: ID(9),
      order: 'NEWEST',
      limit: 12,
    });
    expect(groupOf(doc)).toEqual({
      mode: 'BY_CATEGORY',
      members: [],
      categoryId: ID(9),
      order: 'NEWEST',
      limit: 12,
    });
  });

  it('reads a document with nothing in it as an empty manual group with the default limit', () => {
    expect(groupOf({})).toEqual({
      mode: 'MANUAL',
      members: [],
      categoryId: null,
      order: 'MENU_ORDER',
      limit: 8,
    });
  });

  it('keeps the limit inside the 1 to 40 the server accepts', () => {
    expect(groupOf({ limit: 500 }).limit).toBe(40);
    expect(groupOf({ limit: 0 }).limit).toBe(1);
  });
});

describe('pop-ups', () => {
  it('defaults to once a day, three seconds in, with no daily window', () => {
    expect(popupOf(emptyDocument('POPUP'))).toEqual({
      showAfterSeconds: 3,
      daily: null,
      capMode: 'ONCE_PER_DAY',
      capN: 1,
    });
  });

  it('round-trips a window and an every-N-days cap, and drops the window when cleared', () => {
    const settings = {
      showAfterSeconds: 10,
      daily: { from: '09:00', until: '21:30', daysOfWeek: [1, 2, 3] },
      capMode: 'EVERY_N_DAYS' as const,
      capN: 7,
    };
    let doc = withPopup(emptyDocument('POPUP'), settings);
    expect(popupOf(doc)).toEqual(settings);

    doc = withPopup(doc, { ...settings, daily: null, capMode: 'ONCE_EVER' });
    expect('daily' in doc).toBe(false);
    expect(doc['cap']).toEqual({ mode: 'ONCE_EVER', n: 1 });
  });

  it('keeps the delay inside 3 to 60 seconds', () => {
    expect(popupOf({ showAfterSeconds: 1 }).showAfterSeconds).toBe(3);
    expect(popupOf({ showAfterSeconds: 600 }).showAfterSeconds).toBe(60);
  });
});

describe('stories', () => {
  it('numbers the slides by their place and carries only the keys a slide uses', () => {
    const doc = withSlides(emptyDocument('STORY_GROUP'), [
      {
        ...NEW_SLIDE,
        assetId: ID(1),
        captions: { ru: 'Привет', en: '' },
        link: { type: 'CATEGORY', targetId: ID(5), url: '' },
      },
      { ...NEW_SLIDE, kind: 'VIDEO', assetId: ID(2), posterAssetId: ID(3), durationSeconds: 9 },
    ]);

    expect(doc['slides']).toEqual([
      {
        position: 1,
        media: { kind: 'IMAGE', assetId: ID(1) },
        durationSeconds: 5,
        texts: { ru: { caption: 'Привет' } },
        link: { type: 'CATEGORY', targetId: ID(5) },
      },
      {
        position: 2,
        media: { kind: 'VIDEO', assetId: ID(2), posterAssetId: ID(3) },
        durationSeconds: 9,
        texts: {},
        link: { type: 'NONE' },
      },
    ]);
  });

  it('reads slides back and keeps the duration inside 3 to 15 seconds', () => {
    const slides = slidesOf({
      slides: [{ media: { kind: 'IMAGE', assetId: ID(1) }, durationSeconds: 99, texts: {} }],
    });

    expect(slides).toHaveLength(1);
    expect(slides[0].durationSeconds).toBe(15);
    expect(slides[0].kind).toBe('IMAGE');
  });
});

describe('editorial', () => {
  it('keeps one block document per language and drops a language whose body is emptied', () => {
    let doc = emptyDocument('NEWS');
    doc = withBody(doc, 'ru', {
      schemaVersion: 1,
      blocks: [{ type: 'PARAGRAPH', inlines: [{ text: 'Текст' }] }],
    });
    expect(bodyOf(doc, 'ru').blocks).toHaveLength(1);
    expect(bodyOf(doc, 'en').blocks).toHaveLength(0);

    doc = withBody(doc, 'ru', { schemaVersion: 1, blocks: [] });
    expect(doc['body']).toEqual({});
  });

  it('reads a vacancy and writes only the contact it has', () => {
    let doc = emptyDocument('VACANCY');
    expect(vacancyOf(doc)).toEqual({
      employmentType: 'FULL_TIME',
      applyType: 'PHONE',
      applyValue: '',
    });

    doc = withVacancy(doc, {
      employmentType: 'SHIFT',
      applyType: 'EMAIL',
      applyValue: ' jobs@example.uz ',
    });
    expect(doc['vacancy']).toEqual({
      employmentType: 'SHIFT',
      apply: { type: 'EMAIL', value: 'jobs@example.uz' },
    });

    doc = withVacancy(doc, { employmentType: 'SHIFT', applyType: 'PHONE', applyValue: '' });
    expect(doc['vacancy']).toEqual({ employmentType: 'SHIFT', apply: { type: 'PHONE' } });
  });

  it('keeps album images in order with their captions', () => {
    const doc = withAlbumImages(emptyDocument('GALLERY_ALBUM'), [
      { assetId: ID(1), captions: { ru: 'Один' } },
      { assetId: ID(2), captions: {} },
    ]);

    expect(albumImagesOf(doc)).toEqual([
      { assetId: ID(1), captions: { ru: 'Один' } },
      { assetId: ID(2), captions: {} },
    ]);
  });
});

describe('documentForSave', () => {
  it('drops a language with nothing in it and omits a blank optional text instead of sending ""', () => {
    const doc = documentForSave({
      schemaVersion: 1,
      texts: {
        ru: { title: '  Скидка  ', subtitle: '', ctaLabel: '   ' },
        en: { title: '' },
      },
    });

    expect(doc['texts']).toEqual({ ru: { title: 'Скидка' } });
  });

  it('keeps a language that has a subtitle but no title, so the editor can name it (and not silently lose it)', () => {
    const doc = documentForSave({ texts: { ru: { subtitle: 'sub' } } });

    expect(doc['texts']).toEqual({ ru: { subtitle: 'sub' } });
  });

  it('leaves the rest of the document alone', () => {
    const doc = documentForSave({
      schemaVersion: 1,
      texts: {},
      media: { primaryAssetId: ID(1) },
      link: { type: 'CART' },
    });

    expect(doc['media']).toEqual({ primaryAssetId: ID(1) });
    expect(doc['link']).toEqual({ type: 'CART' });
  });
});

describe('diffDocuments (revision history)', () => {
  it('names every path that changed, with before and after, and nothing that did not', () => {
    const before = {
      texts: { ru: { title: 'Старое', subtitle: 'Тот же' } },
      link: { type: 'NONE' },
    };
    const after = {
      texts: { ru: { title: 'Новое', subtitle: 'Тот же' }, en: { title: 'New' } },
      link: { type: 'CART' },
    };

    expect(diffDocuments(before, after)).toEqual({
      'texts.ru.title': { before: 'Старое', after: 'Новое' },
      'texts.en.title': { before: null, after: 'New' },
      'link.type': { before: 'NONE', after: 'CART' },
    });
  });

  it('reads a missing side as nothing', () => {
    expect(diffDocuments(null, { a: 1 })).toEqual({ a: { before: null, after: 1 } });
    expect(diffDocuments({ a: 1 }, { a: 1 })).toEqual({});
  });

  it('walks arrays by index, so a changed list member is one line', () => {
    const diff = diffDocuments(
      { members: [{ id: 'a' }, { id: 'b' }] },
      { members: [{ id: 'a' }, { id: 'c' }] },
    );

    expect(diff).toEqual({ 'members[1].id': { before: 'b', after: 'c' } });
  });
});
