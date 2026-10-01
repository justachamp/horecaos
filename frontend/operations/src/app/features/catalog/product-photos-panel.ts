import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';

import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { MediaUploader } from '../../shared/ui/media-uploader';
import { ChannelView } from '../settings/sales-channels/sales-channels-api';
import { MediaRelation } from './catalog-domain';

/**
 * The product editor's Photos tab (IA 4.2f): the channel picker, the gallery of
 * the picked channel with move and detach, and the uploader.
 *
 * Presentation only. The product, its media relations, the resolved image URLs,
 * every write and the upload itself belong to the editor (`ProductEditorPage`);
 * this component shows them and raises what the operator asks for. It is its own
 * component so the tab's rules do not count against the editor's component-style
 * budget.
 */
@Component({
  selector: 'q-product-photos-panel',
  imports: [MediaUploader, TPipe],
  templateUrl: './product-photos-panel.html',
  styleUrl: './product-photos-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductPhotosPanel {
  private readonly i18n = inject(I18n);

  readonly photoChannels = input.required<readonly ChannelView[]>();
  readonly selectedPhotoChannel = input.required<string>();
  /** The code of the gallery every channel falls back to. */
  readonly allChannelsCode = input.required<string>();
  readonly selectedChannelPhotos = input.required<readonly MediaRelation[]>();
  /** Resolved image URL per media asset id; an asset with none draws a placeholder. */
  readonly photoUrls = input.required<Readonly<Record<string, string>>>();
  /** The field being saved right now (`photo-reorder:<id>`, `photo-detach:<id>`), if any. */
  readonly savingField = input.required<string | null>();
  readonly uploadingPhoto = input.required<boolean>();

  readonly channelChanged = output<string>();
  readonly reorderRequested = output<{ item: MediaRelation; direction: -1 | 1 }>();
  readonly detachRequested = output<MediaRelation>();
  readonly uploadRequested = output<File>();
  readonly rejected = output<string>();

  protected photoRoleLabel(role: string): string {
    switch (role) {
      case 'PRIMARY':
        return this.i18n.t('catalog.editor.photos.role.PRIMARY');
      case 'GALLERY':
        return this.i18n.t('catalog.editor.photos.role.GALLERY');
      default:
        return role;
    }
  }
}
