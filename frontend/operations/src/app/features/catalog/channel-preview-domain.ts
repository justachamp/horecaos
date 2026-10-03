/**
 * Wire types for the marketplace projection preview (ADR 0138), hand-mirrored from
 * `catalog.web.ChannelPreviewResponse` and `CatalogAuthoringController`'s channel
 * media override records, by the same house convention `catalog-domain.ts` documents.
 *
 * The menu shapes are the live storefront menu's own on purpose: the preview is the
 * storefront's menu assembly run over the draft, so a variant here reads exactly as it
 * does on the customer-facing menu (`amountMinor` beside one `currency`, `orderable`,
 * `onSaleNow`). What is added is what only a preview knows — where an image came from,
 * who sets the price, and the findings.
 */

import { ValidationFinding } from './catalog-domain';

/** Which layer an entity's images on the previewed channel came from (ADR 0138 step 4). */
export type MediaSource = 'DEFAULT' | 'CHANNEL_RELATION' | 'CHANNEL_OVERRIDE';

/** Who raised a finding: the catalog's own rules, a mechanical fact about the projection, or a marketplace ruleset. */
export type FindingSource = 'CATALOG' | 'PROJECTION' | 'MARKETPLACE';

/** `ChannelPreviewResponse.Finding` — a validation finding plus where it came from and the product that owns it. */
export interface PreviewFinding extends ValidationFinding {
  readonly source: FindingSource;
  /** Owner of a variant-scoped finding's entity, so it can deep-link to the editor that fixes it. */
  readonly productId?: string | null;
}

export interface PreviewChannel {
  readonly id: string;
  readonly code: string;
  readonly displayName: string;
  readonly systemType: string;
  readonly status: string;
  readonly externallyPriced: boolean;
}

/** The marketplace binding at the previewed branch — a catalogue provider code and a tenant-typed name, never a credential. */
export interface PreviewBinding {
  readonly bindingId: string;
  readonly status: string;
  readonly rulesetCode?: string | null;
  readonly providerType: string;
  readonly displayName: string;
}

/** `authority` is `EXTERNAL` when the aggregator sets the price: no amount is stated and `currency` is null. */
export interface PreviewPricing {
  readonly authority: 'HORECAOS' | 'EXTERNAL';
  readonly currency?: string | null;
}

export interface PreviewVariant {
  readonly variantId: string;
  readonly sku?: string | null;
  readonly unitCode?: string | null;
  readonly isDefault: boolean;
  /** False means shown but stopped (86'd or out of stock), not absent. */
  readonly orderable: boolean;
  readonly onSaleNow: boolean;
  /** Null when the aggregator sets the price, or the variant has none on this channel's plane — never zero. */
  readonly amountMinor?: number | null;
  readonly remainingQuantity?: number | null;
  readonly mediaAssetIds: readonly string[];
  readonly imageUrls: readonly string[];
  readonly mediaSource: MediaSource;
}

export interface PreviewProduct {
  readonly productId: string;
  readonly code: string;
  readonly name: string;
  readonly description?: string | null;
  readonly mediaAssetIds: readonly string[];
  readonly imageUrls: readonly string[];
  readonly mediaSource: MediaSource;
  readonly variants: readonly PreviewVariant[];
  readonly modifierGroupIds: readonly string[];
}

export interface PreviewCategory {
  readonly categoryId: string;
  readonly code: string;
  readonly name: string;
  readonly parentCategoryId?: string | null;
  readonly sortOrder: number;
  readonly productIds: readonly string[];
  readonly mediaAssetIds: readonly string[];
  readonly imageUrls: readonly string[];
  readonly mediaSource: MediaSource;
}

export interface PreviewModifierGroup {
  readonly modifierGroupId: string;
  readonly code: string;
  readonly name: string;
  readonly required: boolean;
  readonly minimumSelections: number;
  readonly maximumSelections: number;
}

/**
 * `ChannelPreviewResponse` — one page. `findings`, `categories` and `modifierGroups` are the whole menu's
 * and arrive on the first page only (no cursor); later pages carry `items` alone.
 */
export interface ChannelPreviewPageBody {
  readonly channel: PreviewChannel;
  readonly locationId: string;
  readonly binding?: PreviewBinding | null;
  readonly locale: string;
  readonly pricing: PreviewPricing;
  /** What `GET .../validation` says and `publish` decides: no universal blocker. */
  readonly publishable: boolean;
  /** `publishable` and no blocker from the projection or a marketplace ruleset — the verdict for this channel. */
  readonly channelReady: boolean;
  readonly findings: readonly PreviewFinding[];
  readonly categories: readonly PreviewCategory[];
  readonly modifierGroups: readonly PreviewModifierGroup[];
  readonly items: readonly PreviewProduct[];
  readonly nextCursor?: string | null;
}

/**
 * `ChannelPreviewResponse.PreviewTargetView` — a branch a channel sells at, and the marketplace binding that covers it.
 *
 * `locationName` is the branch's own name; `binding.displayName` is the marketplace installation's, which a
 * brand-wide binding gives to every branch alike, so it can only ever be the second half of a label.
 */
export interface PreviewTarget {
  readonly locationId: string;
  readonly locationName?: string | null;
  readonly binding?: PreviewBinding | null;
}

/** `ChannelMediaOverrideView`. */
export interface ChannelMediaOverride {
  readonly entityType: string;
  readonly entityId: string;
  readonly mediaAssetId: string;
  readonly role: 'PRIMARY' | 'GALLERY';
  readonly sortOrder: number;
  readonly version: number;
}

export interface ChannelMediaImage {
  readonly mediaAssetId: string;
  readonly role: 'PRIMARY' | 'GALLERY';
  readonly sortOrder: number;
}
