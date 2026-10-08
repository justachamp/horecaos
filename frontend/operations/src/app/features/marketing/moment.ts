import { formatDate, formatTime } from '../../core/format/datetime';

/**
 * The zone a moment is written in when nothing says otherwise. HorecaOS operates in Uzbekistan
 * today and a campaign carries its own brand zone (`CampaignView.timezone`), which every caller
 * that has one passes; this is the least-wrong constant for the offers screen, whose responses
 * carry instants and no zone, the same gap `reports-filter-state.ts` documents.
 */
export const MARKETING_FALLBACK_ZONE = 'Asia/Tashkent';

/** `DD.MM.YYYY HH:mm` in the brand's zone, the way operators here read a moment. */
export function formatMoment(iso: string, zone: string | null = null): string {
  const instant = new Date(iso);
  const at = zone ?? MARKETING_FALLBACK_ZONE;
  return `${formatDate(instant, at)} ${formatTime(instant, at)}`;
}
