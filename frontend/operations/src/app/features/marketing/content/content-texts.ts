import { MessageKey } from '../../../core/i18n/messages.en';
import {
  ContentKind,
  ContentPlacement,
  ContentProblem,
  ContentVisibility,
  ItemState,
} from './content-api';

/** The translated names of the closed vocabularies, so a template never builds a key by concatenation. */
export const KIND_KEYS: Readonly<Record<ContentKind, MessageKey>> = {
  BANNER: 'marketing.content.kind.BANNER',
  PROMO_CARD: 'marketing.content.kind.PROMO_CARD',
  PRODUCT_GROUP: 'marketing.content.kind.PRODUCT_GROUP',
  STORY_GROUP: 'marketing.content.kind.STORY_GROUP',
  POPUP: 'marketing.content.kind.POPUP',
  NEWS: 'marketing.content.kind.NEWS',
  GALLERY_ALBUM: 'marketing.content.kind.GALLERY_ALBUM',
  RECIPE: 'marketing.content.kind.RECIPE',
  VACANCY: 'marketing.content.kind.VACANCY',
};

export const PLACEMENT_KEYS: Readonly<Record<ContentPlacement, MessageKey>> = {
  HOME_CAROUSEL: 'marketing.content.placement.HOME_CAROUSEL',
  HOME_GROUPS: 'marketing.content.placement.HOME_GROUPS',
  STORY_RAIL: 'marketing.content.placement.STORY_RAIL',
  PROMO_GRID: 'marketing.content.placement.PROMO_GRID',
  ENTRY_MODAL: 'marketing.content.placement.ENTRY_MODAL',
};

export const STATE_KEYS: Readonly<Record<ItemState, MessageKey>> = {
  DRAFT: 'marketing.content.state.DRAFT',
  PUBLISHED: 'marketing.content.state.PUBLISHED',
  WITHDRAWN: 'marketing.content.state.WITHDRAWN',
  ARCHIVED: 'marketing.content.state.ARCHIVED',
};

export const VISIBILITY_KEYS: Readonly<Record<ContentVisibility, MessageKey>> = {
  LIVE: 'marketing.content.visibility.LIVE',
  SCHEDULED: 'marketing.content.visibility.SCHEDULED',
  EXPIRED: 'marketing.content.visibility.EXPIRED',
};

/** The publication gates and health codes of ADR 0157, each with its own sentence. */
export const PROBLEM_KEYS: Readonly<Record<string, MessageKey>> = {
  CONTENT_DEFAULT_LOCALE_MISSING: 'marketing.content.problem.CONTENT_DEFAULT_LOCALE_MISSING',
  CONTENT_LOCALE_INCOMPLETE: 'marketing.content.problem.CONTENT_LOCALE_INCOMPLETE',
  CONTENT_MEDIA_NOT_DISPLAYABLE: 'marketing.content.problem.CONTENT_MEDIA_NOT_DISPLAYABLE',
  CONTENT_MEDIA_ASPECT_MISMATCH: 'marketing.content.problem.CONTENT_MEDIA_ASPECT_MISMATCH',
  CONTENT_TARGET_NOT_FOUND: 'marketing.content.problem.CONTENT_TARGET_NOT_FOUND',
  CONTENT_PROMOTION_NOT_FOUND: 'marketing.content.problem.CONTENT_PROMOTION_NOT_FOUND',
  CONTENT_LINK_HOST_NOT_ALLOWED: 'marketing.content.problem.CONTENT_LINK_HOST_NOT_ALLOWED',
  CONTENT_CHANNEL_NOT_A_CONTENT_SURFACE:
    'marketing.content.problem.CONTENT_CHANNEL_NOT_A_CONTENT_SURFACE',
  CONTENT_PLACEMENT_FULL: 'marketing.content.problem.CONTENT_PLACEMENT_FULL',
  CONTENT_NOTHING_TO_PUBLISH: 'marketing.content.problem.CONTENT_NOTHING_TO_PUBLISH',
  CONTENT_VIDEO_LIMIT: 'marketing.content.problem.CONTENT_VIDEO_LIMIT',
  CONTENT_POSTER_REQUIRED: 'marketing.content.problem.CONTENT_POSTER_REQUIRED',
  CONTENT_REVISION_MEDIA_RELEASED: 'marketing.content.problem.CONTENT_REVISION_MEDIA_RELEASED',
  CONTENT_KIND_NOT_ENABLED: 'marketing.content.problem.CONTENT_KIND_NOT_ENABLED',
  CONTENT_PROMOTION_NOT_LIVE: 'marketing.content.problem.CONTENT_PROMOTION_NOT_LIVE',
  CONTENT_MEDIA_NOT_RENDERED: 'marketing.content.problem.CONTENT_MEDIA_NOT_RENDERED',
  CONTENT_VIDEO_NOT_ENTITLED: 'marketing.content.problem.CONTENT_VIDEO_NOT_ENTITLED',
};

export type Translate = (
  key: MessageKey,
  values?: Readonly<Record<string, string | number>>,
) => string;

/**
 * The sentence for one problem. A code this console has a sentence for gets it, in the author's
 * language; any other (a grammar code such as `TOO_LONG`) is shown with the server's English message,
 * which never quotes what the author wrote.
 */
export function problemText(t: Translate, problem: ContentProblem): string {
  const key = PROBLEM_KEYS[problem.code];
  return key ? t(key) : t('marketing.content.problem.other', { detail: problem.message });
}
