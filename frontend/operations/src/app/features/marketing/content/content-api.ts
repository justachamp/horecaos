import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { BrandScope } from '../../../core/api/catalog-paths';
import { contentPaths } from '../../../core/api/content-paths';
import { command } from '../../../core/api/idempotency';

/** Every kind of brand-authored content (ADR 0157, Decision 1): tier 1 first, then the editorial tier. */
export type ContentKind =
  | 'BANNER'
  | 'PROMO_CARD'
  | 'PRODUCT_GROUP'
  | 'STORY_GROUP'
  | 'POPUP'
  | 'NEWS'
  | 'GALLERY_ALBUM'
  | 'RECIPE'
  | 'VACANCY';

export const CONTENT_KINDS: readonly ContentKind[] = [
  'BANNER',
  'PROMO_CARD',
  'PRODUCT_GROUP',
  'STORY_GROUP',
  'POPUP',
  'NEWS',
  'GALLERY_ALBUM',
  'RECIPE',
  'VACANCY',
];

/** The editorial tier: addressed by a slug, placed nowhere, off until the plan and the brand both say yes. */
export const EDITORIAL_KINDS: readonly ContentKind[] = [
  'NEWS',
  'GALLERY_ALBUM',
  'RECIPE',
  'VACANCY',
];

export type ContentPlacement =
  'HOME_CAROUSEL' | 'HOME_GROUPS' | 'STORY_RAIL' | 'PROMO_GRID' | 'ENTRY_MODAL';

/** The placements a kind may sit in; the first is its default. Editorial kinds sit nowhere. */
export const PLACEMENTS_OF_KIND: Readonly<Record<ContentKind, readonly ContentPlacement[]>> = {
  BANNER: ['HOME_CAROUSEL'],
  PROMO_CARD: ['HOME_CAROUSEL', 'PROMO_GRID'],
  PRODUCT_GROUP: ['HOME_GROUPS'],
  STORY_GROUP: ['STORY_RAIL'],
  POPUP: ['ENTRY_MODAL'],
  NEWS: [],
  GALLERY_ALBUM: [],
  RECIPE: [],
  VACANCY: [],
};

/** Platform guardrails on live items per placement (a code constant on the server, not a setting). */
export const PLACEMENT_LIMITS: Readonly<Record<ContentPlacement, number>> = {
  HOME_CAROUSEL: 10,
  HOME_GROUPS: 8,
  STORY_RAIL: 20,
  PROMO_GRID: 12,
  ENTRY_MODAL: 1,
};

/** Stored states only: scheduled, live and expired are computed (ADR 0157, Decision 2). */
export type ItemState = 'DRAFT' | 'PUBLISHED' | 'WITHDRAWN' | 'ARCHIVED';

export type ContentVisibility = 'LIVE' | 'SCHEDULED' | 'EXPIRED';

/** One thing wrong at one place, with a stable code. The message is English, for a developer. */
export interface ContentProblem {
  readonly field: string;
  readonly code: string;
  readonly message: string;
}

/** A document is the server's grammar for its kind; the console edits it in its wire shape. */
export type ContentDocument = Readonly<Record<string, unknown>>;

export interface ContentDraftView {
  readonly document: ContentDocument;
  readonly channelCodes: readonly string[];
  readonly startsAt: string | null;
  readonly endsAt: string | null;
  readonly promotionId: string | null;
  readonly locationId: string | null;
  readonly basedOnRevisionNo: number | null;
  readonly updatedBy: string;
  readonly updatedAt: string;
}

export interface ContentRevisionView {
  readonly revisionNo: number;
  readonly contentHash: string;
  readonly document: ContentDocument;
  readonly channelCodes: readonly string[];
  readonly startsAt: string | null;
  readonly endsAt: string | null;
  readonly promotionId: string | null;
  readonly locationId: string | null;
  readonly publishedBy: string;
  readonly publishedAt: string;
}

export interface ContentRevisionSummary {
  readonly revisionNo: number;
  readonly contentHash: string;
  readonly channelCodes: readonly string[];
  readonly startsAt: string | null;
  readonly endsAt: string | null;
  readonly promotionId: string | null;
  readonly publishedBy: string;
  readonly publishedAt: string;
  readonly live: boolean;
}

/** Mirrors `ContentViews.ItemView`; `version` is the ETag every change presents in If-Match. */
export interface ContentItemView {
  readonly id: string;
  readonly kind: ContentKind;
  readonly placement: ContentPlacement | null;
  readonly internalName: string;
  readonly displayTitle: string;
  readonly slug: string | null;
  readonly state: ItemState;
  readonly visibility: ContentVisibility | null;
  readonly sortKey: number;
  readonly version: number;
  readonly createdBy: string;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly liveRevision: ContentRevisionView | null;
  readonly draft: ContentDraftView | null;
  readonly hasUnpublishedChanges: boolean;
  readonly problems: readonly ContentProblem[];
}

/** Mirrors `ContentViews.ItemSummary`: a row of the list, no documents. */
export interface ContentItemSummary {
  readonly id: string;
  readonly kind: ContentKind;
  readonly placement: ContentPlacement | null;
  readonly internalName: string;
  readonly displayTitle: string;
  readonly slug: string | null;
  readonly state: ItemState;
  readonly visibility: ContentVisibility | null;
  readonly sortKey: number;
  readonly version: number;
  readonly liveRevisionNo: number | null;
  readonly startsAt: string | null;
  readonly endsAt: string | null;
  readonly channelCodes: readonly string[];
  readonly hasUnpublishedChanges: boolean;
  readonly updatedAt: string;
  readonly problems: readonly ContentProblem[];
}

export interface ContentItemPage {
  readonly items: readonly ContentItemSummary[];
  readonly nextCursor: string | null;
  /** Present when the list is of one placement: presented back when ordering it. */
  readonly orderToken: string | null;
}

export interface ContentItemFilter {
  readonly kind?: ContentKind;
  readonly placement?: ContentPlacement;
  readonly state?: ItemState;
  readonly visibility?: ContentVisibility;
  readonly cursor?: string | null;
  readonly limit?: number;
}

/** The body of a draft: the document and everything around it that publication freezes into a revision. */
export interface ContentDraftBody {
  readonly document: ContentDocument;
  readonly channelCodes: readonly string[];
  readonly startsAt: string | null;
  readonly endsAt: string | null;
  readonly promotionId: string | null;
  readonly locationId: string | null;
}

export interface ContentCreateRequest extends ContentDraftBody {
  readonly kind: ContentKind;
  readonly placement: ContentPlacement | null;
  readonly internalName: string;
  readonly slug: string | null;
}

export interface PlacementOrder {
  readonly ids: readonly string[];
  readonly orderToken: string;
}

export interface UnhealthyItem {
  readonly id: string;
  readonly kind: ContentKind;
  readonly placement: ContentPlacement | null;
  readonly displayTitle: string;
  readonly visibility: ContentVisibility;
  readonly problems: readonly ContentProblem[];
}

export interface AllowedHost {
  readonly host: string;
  /** `ADDED` by an author, or `CHANNEL` for a verified channel hostname read and never copied. */
  readonly source: string;
  readonly addedBy: string;
  readonly addedAt: string | null;
}

export interface ContentPolicies {
  readonly publishing: { readonly requireAllBrandLocales: boolean };
  readonly publishingVersion: number;
  readonly editorial: {
    readonly news: boolean;
    readonly gallery: boolean;
    readonly recipes: boolean;
    readonly vacancies: boolean;
  };
  readonly editorialVersion: number;
  /** Whether the plan lets the editorial tier exist at all (`content.editorial.enabled`). */
  readonly editorialEntitled: boolean;
  /** Whether the plan lets a video be uploaded (`content.video.enabled`). */
  readonly videoEntitled: boolean;
}

export interface ContentStatsRow {
  readonly revisionNo: number;
  readonly day: string;
  readonly channelCode: string;
  readonly impressions: number;
  readonly clicks: number;
  readonly dismissals: number;
  readonly completions: number;
}

export interface ContentStats {
  readonly clientReported: boolean;
  readonly from: string;
  readonly to: string;
  readonly rows: readonly ContentStatsRow[];
}

/** The storefront document, as the preview endpoint builds it (a subset the console draws). */
export interface PreviewMedia {
  readonly kind: 'IMAGE' | 'VIDEO';
  readonly alt?: string | null;
  readonly widthPx?: number | null;
  readonly heightPx?: number | null;
  readonly renditions: readonly { readonly widthPx: number; readonly url: string }[];
  readonly url?: string | null;
  readonly posterUrl?: string | null;
}

export interface PreviewLink {
  readonly type: string;
  readonly targetId?: string | null;
  readonly url?: string | null;
}

export interface PreviewCard {
  readonly id: string;
  readonly kind: string;
  readonly title: string;
  readonly subtitle?: string | null;
  readonly ctaLabel?: string | null;
  readonly textLocale: string;
  readonly media: PreviewMedia;
  readonly link: PreviewLink;
}

export interface PreviewGroup {
  readonly id: string;
  readonly title: string;
  readonly textLocale: string;
  readonly productIds: readonly string[];
  readonly categoryId?: string | null;
  readonly order?: string | null;
  readonly limit?: number | null;
}

export interface PreviewStory {
  readonly id: string;
  readonly title: string;
  readonly avatar?: PreviewMedia | null;
  readonly slides: readonly { readonly media: PreviewMedia; readonly caption?: string | null }[];
}

export interface PreviewPopup {
  readonly id: string;
  readonly title: string;
  readonly body?: string | null;
  readonly ctaLabel?: string | null;
  readonly media?: PreviewMedia | null;
  readonly showAfterSeconds: number;
}

export interface PreviewDocument {
  readonly locale: string;
  readonly carousel: readonly PreviewCard[];
  readonly groups: readonly PreviewGroup[];
  readonly stories?: readonly PreviewStory[];
  readonly promoGrid?: readonly PreviewCard[];
  readonly popup?: PreviewPopup | null;
}

/**
 * Staff authoring of a brand's storefront content (ADR 0157, Staff API). Four capabilities --
 * `content.read`, `content.author`, `content.publish`, `content.manage` -- are enforced by the server;
 * this client does not predict them, so a principal without one gets the server's legible refusal.
 *
 * Every change presents the item's `version` as `If-Match` (a mismatch is `STALE_VERSION`), and every
 * `POST` carries an `Idempotency-Key`. A draft is in front of nobody; only a publication reaches
 * customers.
 */
@Injectable({ providedIn: 'root' })
export class ContentApi {
  private readonly api = inject(ApiClient);

  async list(scope: BrandScope, filter: ContentItemFilter = {}): Promise<ContentItemPage> {
    const result = await firstValueFrom(
      this.api.get<ContentItemPage>(contentPaths.items(scope), {
        params: {
          kind: filter.kind,
          placement: filter.placement,
          state: filter.state,
          visibility: filter.visibility,
          cursor: filter.cursor ?? undefined,
          limit: filter.limit,
        },
      }),
    );
    return result.value;
  }

  async get(scope: BrandScope, itemId: string): Promise<ContentItemView> {
    return (await firstValueFrom(this.api.get<ContentItemView>(contentPaths.item(scope, itemId))))
      .value;
  }

  create(scope: BrandScope, request: ContentCreateRequest): Promise<ContentItemView> {
    return firstValueFrom(
      this.api.post<ContentCreateRequest, ContentItemView>(
        contentPaths.items(scope),
        command(request),
      ),
    );
  }

  replaceDraft(
    scope: BrandScope,
    itemId: string,
    version: number,
    internalName: string,
    body: ContentDraftBody,
  ): Promise<ContentItemView> {
    return firstValueFrom(
      this.api.put<ContentDraftBody & { internalName: string }, ContentItemView>(
        contentPaths.draft(scope, itemId),
        command({ ...body, internalName }),
        { expectedVersion: version },
      ),
    );
  }

  discardDraft(scope: BrandScope, itemId: string, version: number): Promise<ContentItemView> {
    return firstValueFrom(
      this.api.send<null, ContentItemView>(
        'DELETE',
        contentPaths.draft(scope, itemId),
        command(null),
        { expectedVersion: version },
      ),
    ).then((response) => response.body as ContentItemView);
  }

  publish(
    scope: BrandScope,
    itemId: string,
    version: number,
    reason?: string,
  ): Promise<ContentItemView> {
    return this.act(contentPaths.publications(scope, itemId), version, reason);
  }

  withdraw(
    scope: BrandScope,
    itemId: string,
    version: number,
    reason?: string,
  ): Promise<ContentItemView> {
    return this.act(contentPaths.withdrawals(scope, itemId), version, reason);
  }

  archive(
    scope: BrandScope,
    itemId: string,
    version: number,
    reason?: string,
  ): Promise<ContentItemView> {
    return this.act(contentPaths.archives(scope, itemId), version, reason);
  }

  async revisions(scope: BrandScope, itemId: string): Promise<readonly ContentRevisionSummary[]> {
    return (
      await firstValueFrom(
        this.api.get<readonly ContentRevisionSummary[]>(contentPaths.revisions(scope, itemId)),
      )
    ).value;
  }

  async revision(
    scope: BrandScope,
    itemId: string,
    revisionNo: number,
  ): Promise<ContentRevisionView> {
    return (
      await firstValueFrom(
        this.api.get<ContentRevisionView>(contentPaths.revision(scope, itemId, revisionNo)),
      )
    ).value;
  }

  /** Clones an older revision into the draft. Publishing it is a separate act. */
  republish(
    scope: BrandScope,
    itemId: string,
    revisionNo: number,
    version: number,
  ): Promise<ContentItemView> {
    return firstValueFrom(
      this.api.post<null, ContentItemView>(
        contentPaths.republications(scope, itemId, revisionNo),
        command(null),
        { expectedVersion: version },
      ),
    );
  }

  /** The whole placement, in its new order, with the token the list returned. */
  order(
    scope: BrandScope,
    placement: ContentPlacement,
    ids: readonly string[],
    orderToken: string,
  ): Promise<PlacementOrder> {
    return firstValueFrom(
      this.api.put<{ ids: readonly string[]; orderToken: string }, PlacementOrder>(
        contentPaths.placementOrder(scope, placement),
        command({ ids, orderToken }),
      ),
    );
  }

  async preview(
    scope: BrandScope,
    channel: string,
    locale: string | null,
    asOf: string | null,
  ): Promise<PreviewDocument> {
    return (
      await firstValueFrom(
        this.api.get<PreviewDocument>(contentPaths.preview(scope), {
          params: { channel, locale: locale ?? undefined, asOf: asOf ?? undefined },
        }),
      )
    ).value;
  }

  async health(scope: BrandScope): Promise<readonly UnhealthyItem[]> {
    return (
      await firstValueFrom(this.api.get<readonly UnhealthyItem[]>(contentPaths.health(scope)))
    ).value;
  }

  async stats(scope: BrandScope, itemId: string): Promise<ContentStats> {
    return (await firstValueFrom(this.api.get<ContentStats>(contentPaths.stats(scope, itemId))))
      .value;
  }

  async allowedHosts(scope: BrandScope): Promise<readonly AllowedHost[]> {
    return (
      await firstValueFrom(this.api.get<readonly AllowedHost[]>(contentPaths.allowedHosts(scope)))
    ).value;
  }

  addAllowedHost(scope: BrandScope, host: string, reason?: string): Promise<{ host: string }> {
    return firstValueFrom(
      this.api.post<{ host: string; reason?: string }, { host: string }>(
        contentPaths.allowedHosts(scope),
        command({ host, ...(reason ? { reason } : {}) }),
      ),
    );
  }

  async removeAllowedHost(scope: BrandScope, host: string): Promise<void> {
    await firstValueFrom(
      this.api.send<null, void>('DELETE', contentPaths.allowedHost(scope, host), command(null)),
    );
  }

  async policies(scope: BrandScope): Promise<ContentPolicies> {
    return (await firstValueFrom(this.api.get<ContentPolicies>(contentPaths.policies(scope))))
      .value;
  }

  setPublishingPolicy(
    scope: BrandScope,
    version: number,
    requireAllBrandLocales: boolean,
    reason?: string,
  ): Promise<ContentPolicies> {
    return firstValueFrom(
      this.api.put<{ requireAllBrandLocales: boolean; reason?: string }, ContentPolicies>(
        contentPaths.publishingPolicy(scope),
        command({ requireAllBrandLocales, ...(reason ? { reason } : {}) }),
        { expectedVersion: version },
      ),
    );
  }

  setEditorialPolicy(
    scope: BrandScope,
    version: number,
    kinds: ContentPolicies['editorial'],
    reason?: string,
  ): Promise<ContentPolicies> {
    return firstValueFrom(
      this.api.put<ContentPolicies['editorial'] & { reason?: string }, ContentPolicies>(
        contentPaths.editorialPolicy(scope),
        command({ ...kinds, ...(reason ? { reason } : {}) }),
        { expectedVersion: version },
      ),
    );
  }

  private act(path: string, version: number, reason: string | undefined): Promise<ContentItemView> {
    return firstValueFrom(
      this.api.post<{ reason?: string }, ContentItemView>(path, command(reason ? { reason } : {}), {
        expectedVersion: version,
      }),
    );
  }
}
