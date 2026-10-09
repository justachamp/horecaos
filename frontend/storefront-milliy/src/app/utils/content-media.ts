import type { ContentMedia, ContentRendition } from '../types/content.types';
import { safeHttpsUrl } from './content-link';

/** What is drawn for a piece of media. */
export type MediaPlan =
  /** An `<img>` with a `srcset` built from the renditions; `src` is the largest one. */
  | { readonly kind: 'image'; readonly src: string; readonly srcset: string }
  /** A still: a video shown as its poster only (reduced motion, or no file to play). */
  | { readonly kind: 'poster'; readonly src: string }
  /** A `<video muted playsinline preload="metadata">`. */
  | { readonly kind: 'video'; readonly src: string; readonly poster: string | null };

/**
 * A media URL from the document, resolved to the one the browser may load -- or null.
 *
 * The server sends site-relative paths under `/api/v1/storefront/...`; they are put on the origin
 * the API client already talks to (`apiBaseUrl` is an origin plus `/api/v1`, or just `/api/v1`
 * when the page is served from the API's own origin). An absolute URL must be `https:`. Anything
 * else -- `http:`, `javascript:`, `data:`, a protocol-relative `//host`, a bare relative path -- is
 * not media.
 */
export function resolveMediaUrl(url: unknown, apiBaseUrl: string): string | null {
  if (typeof url !== 'string') {
    return null;
  }
  const text = url.trim();
  if (!text) {
    return null;
  }
  if (text.startsWith('/')) {
    // `//host` and `/\host` both leave the origin in a browser.
    if (/^\/[/\\]/.test(text)) {
      return null;
    }
    const origin = /^(https?:\/\/[^/]+)/i.exec(apiBaseUrl)?.[1] ?? '';
    return `${origin}${text}`;
  }
  return safeHttpsUrl(text);
}

function ascending(renditions: readonly ContentRendition[]): ContentRendition[] {
  return [...renditions].sort((a, b) => a.widthPx - b.widthPx);
}

/** `url 400w, url 800w`, narrowest first. */
export function buildSrcset(renditions: readonly ContentRendition[]): string {
  return ascending(renditions)
    .map((rendition) => `${rendition.url} ${rendition.widthPx}w`)
    .join(', ');
}

/**
 * What to draw for a piece of media, or null when there is nothing to draw (so the tile that holds
 * it should not be on the page).
 *
 * An image is its renditions -- never an original -- with the largest as the fallback `src`. A video
 * plays muted and inline with its poster, except under `prefers-reduced-motion: reduce`, where only
 * the poster is shown and no `<video>` is created at all.
 */
export function mediaPlan(media: ContentMedia, reducedMotion: boolean): MediaPlan | null {
  if (media.kind === 'IMAGE') {
    const sorted = ascending(media.renditions);
    const largest = sorted[sorted.length - 1];
    return largest ? { kind: 'image', src: largest.url, srcset: buildSrcset(sorted) } : null;
  }
  const poster = media.posterUrl ?? null;
  if (reducedMotion || !media.url) {
    return poster ? { kind: 'poster', src: poster } : null;
  }
  return { kind: 'video', src: media.url, poster };
}

/** A CSS `aspect-ratio` that keeps the page from jumping when the image arrives, or null. */
export function mediaAspectRatio(media: ContentMedia): string | null {
  const { widthPx, heightPx } = media;
  return widthPx !== undefined && heightPx !== undefined && widthPx > 0 && heightPx > 0
    ? `${widthPx} / ${heightPx}`
    : null;
}
