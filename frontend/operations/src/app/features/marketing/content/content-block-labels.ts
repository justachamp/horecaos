import { RichTextBlockLabels } from '../../../shared/ui/rich-text-blocks';
import { Translate } from './content-texts';

/**
 * Every label the block-document editor shows, in the author's language. `q-rich-text` never looks
 * a key up itself (like `q-action-menu`), so the host that knows the language builds this once.
 */
export function blockLabels(t: Translate): RichTextBlockLabels {
  return {
    text: t('marketing.content.blocks.text'),
    bold: t('marketing.content.blocks.bold'),
    italic: t('marketing.content.blocks.italic'),
    link: t('marketing.content.blocks.link'),
    linkAddress: t('marketing.content.blocks.linkAddress'),
    linkRefused: t('marketing.content.blocks.linkRefused'),
    addRun: t('marketing.content.blocks.addRun'),
    removeRun: t('marketing.content.blocks.removeRun'),
    blockType: t('marketing.content.blocks.blockType'),
    types: {
      PARAGRAPH: t('marketing.content.blocks.type.PARAGRAPH'),
      HEADING: t('marketing.content.blocks.type.HEADING'),
      QUOTE: t('marketing.content.blocks.type.QUOTE'),
      LIST: t('marketing.content.blocks.type.LIST'),
      IMAGE: t('marketing.content.blocks.type.IMAGE'),
      DIVIDER: t('marketing.content.blocks.type.DIVIDER'),
    },
    headingLevel: t('marketing.content.blocks.headingLevel'),
    level2: t('marketing.content.blocks.level2'),
    level3: t('marketing.content.blocks.level3'),
    ordered: t('marketing.content.blocks.ordered'),
    moveUp: t('marketing.content.blocks.moveUp'),
    moveDown: t('marketing.content.blocks.moveDown'),
    removeBlock: t('marketing.content.blocks.removeBlock'),
    addBlock: t('marketing.content.blocks.addBlock'),
    addItem: t('marketing.content.blocks.addItem'),
    removeItem: t('marketing.content.blocks.removeItem'),
    caption: t('marketing.content.blocks.caption'),
    imageNone: t('marketing.content.blocks.imageNone'),
    imageUploaded: t('marketing.content.blocks.imageUploaded'),
    imageFailed: t('marketing.content.blocks.imageFailed'),
    dividerLabel: t('marketing.content.blocks.divider'),
    problems: {
      TOO_MANY_BLOCKS: t('marketing.content.blocks.problem.TOO_MANY_BLOCKS'),
      TOO_MANY_RUNS: t('marketing.content.blocks.problem.TOO_MANY_RUNS'),
      TOO_MANY_ITEMS: t('marketing.content.blocks.problem.TOO_MANY_ITEMS'),
      TEXT_TOO_LONG: t('marketing.content.blocks.problem.TEXT_TOO_LONG'),
      RUN_TOO_LONG: t('marketing.content.blocks.problem.RUN_TOO_LONG'),
      FORBIDDEN_CHARACTER: t('marketing.content.blocks.problem.FORBIDDEN_CHARACTER'),
      HREF_NOT_ALLOWED: t('marketing.content.blocks.problem.HREF_NOT_ALLOWED'),
      EMPTY_LIST: t('marketing.content.blocks.problem.EMPTY_LIST'),
      IMAGE_WITHOUT_ASSET: t('marketing.content.blocks.problem.IMAGE_WITHOUT_ASSET'),
    },
  };
}
