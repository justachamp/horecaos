import { MessageKey } from '../../../core/i18n/messages.en';
import { HighlightContrastSurfaceId } from './highlight-contrast';

/**
 * {@link HighlightContrastSurfaceId} → its own translated label, for the
 * contrast warning sentence below any tenant-chosen colour field (gap map row
 * `X.39`): the channel presentation editor's brand colours and the order
 * policy card's late colour both render the same sentence
 * (`…color.contrastWarning`) over these four surfaces, so the labels live
 * once, here, rather than being copied to each screen.
 */
export const CONTRAST_SURFACE_LABEL_KEYS: Readonly<Record<HighlightContrastSurfaceId, MessageKey>> =
  {
    canvas: 'settings.salesChannels.field.color.surface.canvas',
    surface1: 'settings.salesChannels.field.color.surface.surface1',
    slaLateTint: 'settings.salesChannels.field.color.surface.slaLateTint',
    slaAtRiskTint: 'settings.salesChannels.field.color.surface.slaAtRiskTint',
  };
