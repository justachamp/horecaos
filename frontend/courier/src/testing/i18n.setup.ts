import { CATALOGUE_LOADERS } from '../app/core/i18n/catalogue-loaders';
import { registerCatalogueLoaders } from '../app/core/i18n/i18n';

// The app registers the loaders in main.ts; a spec has no main.ts, so it is done once here.
registerCatalogueLoaders(CATALOGUE_LOADERS);
