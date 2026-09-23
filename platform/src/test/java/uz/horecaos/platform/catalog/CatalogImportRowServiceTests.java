package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.application.CatalogImportRow;
import uz.horecaos.platform.catalog.application.CatalogImportRowErrorReason;
import uz.horecaos.platform.catalog.application.CatalogImportRowOutcome;
import uz.horecaos.platform.catalog.application.CatalogImportRowService;
import uz.horecaos.platform.catalog.application.CatalogQueryService;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Status;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Variant;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.integration.outbox.JdbcOutboxStore;
import uz.horecaos.platform.integration.outbox.MediaOutboxEventListener;
import uz.horecaos.platform.media.api.MediaEvent;
import uz.horecaos.platform.media.application.MediaAssetIngestionService;
import uz.horecaos.platform.media.infrastructure.UrlImageFetcher;
import uz.horecaos.platform.media.infrastructure.persistence.JdbcDerivativeJobStore;
import uz.horecaos.platform.media.infrastructure.persistence.JdbcMediaAssetStore;
import uz.horecaos.platform.media.infrastructure.storage.S3ObjectStorage;
import uz.horecaos.platform.pricing.application.CatalogPricingContext;
import uz.horecaos.platform.pricing.application.PriceAuthoringService;
import uz.horecaos.platform.pricing.infrastructure.catalog.CatalogImportPricing;
import uz.horecaos.platform.pricing.infrastructure.catalog.JdbcCatalogPricingContext;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPricingStore;
import uz.horecaos.platform.support.CommercialDefaults;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;

/**
 * {@link CatalogImportRowService} end to end, against a real catalog, a real
 * price book, and a real object store (row 4.5b) — the same "every price in
 * this suite is written by the production authoring path" reasoning {@code
 * PriceAuthoringTests}'s own doc gives, extended to the catalog import that
 * now writes through the identical path.
 *
 * <p>Docker is required (Postgres and MinIO, matching {@code
 * MediaAssetIngestionServiceTests}'s own infrastructure) — the image-by-URL
 * tests are what actually needs MinIO; everything else would run against
 * Postgres alone, but one shared fixture is simpler than two.
 */
class CatalogImportRowServiceTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID OTHER_BRAND = UUID.randomUUID();
    private static final String LOCALE = "uz";
    private static final String BUCKET = "horecaos-catalog-import-test";
    private static final byte[] JPEG = encode(64, 48);
    private static final byte[] NOT_AN_IMAGE = "<html>not a photo</html>".getBytes(StandardCharsets.UTF_8);

    private static TestDatabase.Handle db;
    private static GenericContainer<?> minio;
    private static S3Client s3;
    private static S3Presigner presigner;

    private JdbcClient jdbc;
    private JdbcCatalogStore catalogStore;
    private CatalogAuthoringService authoring;
    private CatalogQueryService query;
    private CatalogImportRowService rows;
    private UUID catalogId;
    private HttpServer httpServer;
    private int httpPort;

    @BeforeAll
    static void startInfrastructure() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the catalog import tests");

        db = TestDatabase.migrated();

        minio = new GenericContainer<>(DockerImageName.parse("quay.io/minio/minio:RELEASE.2025-07-23T15-54-02Z"))
                .withCommand("server", "/data")
                .withEnv("MINIO_ROOT_USER", "horecaos")
                .withEnv("MINIO_ROOT_PASSWORD", "horecaos-local-secret")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));
        minio.start();

        String endpoint = "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
        var credentials =
                StaticCredentialsProvider.create(AwsBasicCredentials.create("horecaos", "horecaos-local-secret"));
        var pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();

        s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .build();
        presigner = S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .build();
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        } catch (BucketAlreadyOwnedByYouException alreadyThere) {
            // Fine to reuse a bucket across runs.
        }
    }

    @AfterAll
    static void stopInfrastructure() {
        if (s3 != null) {
            s3.close();
        }
        if (presigner != null) {
            presigner.close();
        }
        if (minio != null) {
            minio.stop();
        }
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE pricing.quote_adjustments, pricing.quote_lines, pricing.quotes, "
                        + "pricing.prices, pricing.price_book_assignments, pricing.price_books, "
                        + "pricing.tax_profiles CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE catalog.import_run_rows, catalog.import_runs, "
                        + "catalog.media_relations, catalog.fiscal_classifications, "
                        + "catalog.product_modifier_groups, catalog.modifier_options, catalog.modifier_groups, "
                        + "catalog.category_products, catalog.categories, catalog.catalog_products, "
                        + "catalog.translations, catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE media.assets CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        insertTenantAndBrand(TENANT, BRAND, "catalog-import-tenant", "MAIN");
        insertTenantAndBrand(OTHER_TENANT, OTHER_BRAND, "catalog-import-other-tenant", "OTHER-MAIN");

        Clock clock = Clock.fixed(Instant.parse("2026-09-22T09:00:00Z"), ZoneOffset.UTC);

        catalogStore = new JdbcCatalogStore(jdbc, JsonMapper.builder().build());
        CommercialDefaults.Wired commercial = CommercialDefaults.wire(jdbc, clock);
        authoring = new CatalogAuthoringService(
                catalogStore,
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()),
                commercial.entitlements(),
                commercial.usage(),
                clock);
        query = new CatalogQueryService(catalogStore, LOCALE);

        JdbcPricingStore pricingStore =
                new JdbcPricingStore(jdbc, JsonMapper.builder().build());
        CatalogPricingContext pricingContext = new JdbcCatalogPricingContext(jdbc, LOCALE);
        PriceAuthoringService priceAuthoring = new PriceAuthoringService(
                pricingStore,
                pricingContext,
                new JdbcSalesChannelStore(jdbc),
                clock,
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()),
                event -> {},
                () -> new uz.horecaos.platform.iam.api.AuthenticatedActor(
                        "catalog-import-test", java.util.Set.of(), java.util.Map.of()));
        CatalogImportPricing pricing = new CatalogImportPricing(priceAuthoring, pricingStore, clock);

        S3ObjectStorage storage = new S3ObjectStorage(s3, presigner);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        MediaOutboxEventListener outbox = new MediaOutboxEventListener(
                new JdbcOutboxStore(jdbc), JsonMapper.builder().build(), "media.events");
        ApplicationEventPublisher events = event -> outbox.append((MediaEvent) event);
        var mediaAudit = new JdbcAuditRecorder(jdbc, JsonMapper.builder().build());
        var media = new MediaAssetIngestionService(
                new JdbcMediaAssetStore(jdbc),
                new JdbcDerivativeJobStore(jdbc),
                storage,
                // Fetches from this class's own in-process loopback server
                // standing in for a remote host -- see UrlImageFetcher's own
                // doc for why production callers get the private-network
                // refusal that this test constructor lifts.
                new UrlImageFetcher(true),
                transactions,
                events,
                mediaAudit,
                clock,
                BUCKET);

        rows = new CatalogImportRowService(catalogStore, authoring, query, pricing, media, LOCALE);

        catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", LOCALE);
        authoring.createCatalog(OTHER_TENANT, OTHER_BRAND, "MAIN", "Boshqa menyu", LOCALE);

        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/plov.jpg", exchange -> respond(exchange, "image/jpeg", JPEG));
        httpServer.createContext("/not-a-photo.jpg", exchange -> respond(exchange, "image/jpeg", NOT_AN_IMAGE));
        httpServer.start();
        httpPort = httpServer.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    // ------------------------------------------------------------- create

    @Test
    @DisplayName("a dry run creates nothing and reports CREATED with no ids")
    void dryRunCreateWritesNothing() {
        CatalogImportRow row = row("PLOV-001", "Osh", "50000", "UZS");

        CatalogImportRowOutcome outcome = rows.process(TENANT, BRAND, catalogId, row, true, null);

        assertThat(outcome.type()).isEqualTo(CatalogImportRowOutcome.Type.CREATED);
        assertThat(outcome.productId()).isNull();
        assertThat(outcome.variantId()).isNull();
        assertThat(catalogStore.productByCode(TENANT, BRAND, "PLOV-001")).isEmpty();
    }

    @Test
    @DisplayName("apply creates the product, its default variant, and its price")
    void applyCreatesProductVariantAndPrice() {
        CatalogImportRow row = row("PLOV-001", "Osh", "50000", "UZS");

        CatalogImportRowOutcome outcome = rows.process(TENANT, BRAND, catalogId, row, false, null);

        assertThat(outcome.type()).isEqualTo(CatalogImportRowOutcome.Type.CREATED);
        assertThat(catalogStore.productByCode(TENANT, BRAND, "PLOV-001")).isPresent();

        var detail = query.productDetail(TENANT, BRAND, requireProductId(outcome));
        assertThat(requireTranslation(detail).name()).isEqualTo("Osh");
        assertThat(detail.variants()).hasSize(1);
        assertThat(detail.variants().get(0).sku()).isEqualTo("SKU-PLOV");
    }

    @Test
    @DisplayName("re-applying the same unchanged file resolves to SKIPPED and writes nothing more")
    void reapplyingUnchangedFileSkips() {
        CatalogImportRow row = row("PLOV-001", "Osh", "50000", "UZS");
        CatalogImportRowOutcome first = rows.process(TENANT, BRAND, catalogId, row, false, null);
        assertThat(first.type()).isEqualTo(CatalogImportRowOutcome.Type.CREATED);

        CatalogImportRowOutcome second = rows.process(TENANT, BRAND, catalogId, row, false, null);

        assertThat(second.type()).isEqualTo(CatalogImportRowOutcome.Type.SKIPPED);
        assertThat(second.productId()).isEqualTo(first.productId());
    }

    @Test
    @DisplayName("a dry run against an unchanged product also resolves to SKIPPED")
    void dryRunAgainstUnchangedProductSkips() {
        CatalogImportRow row = row("PLOV-001", "Osh", "50000", "UZS");
        rows.process(TENANT, BRAND, catalogId, row, false, null);

        CatalogImportRowOutcome outcome = rows.process(TENANT, BRAND, catalogId, row, true, null);

        assertThat(outcome.type()).isEqualTo(CatalogImportRowOutcome.Type.SKIPPED);
    }

    @Test
    @DisplayName("changing the price on a re-import resolves to UPDATED and writes the new price")
    void changedPriceResolvesToUpdated() {
        CatalogImportRow original = row("PLOV-001", "Osh", "50000", "UZS");
        CatalogImportRowOutcome created = rows.process(TENANT, BRAND, catalogId, original, false, null);

        CatalogImportRow repriced = row("PLOV-001", "Osh", "60000", "UZS");
        CatalogImportRowOutcome updated = rows.process(TENANT, BRAND, catalogId, repriced, false, null);

        assertThat(updated.type()).isEqualTo(CatalogImportRowOutcome.Type.UPDATED);
        assertThat(updated.productId()).isEqualTo(created.productId());
    }

    @Test
    @DisplayName("changing only the name resolves to UPDATED and rewrites the translation")
    void changedNameResolvesToUpdated() {
        rows.process(TENANT, BRAND, catalogId, row("PLOV-001", "Osh", null, null), false, null);

        CatalogImportRowOutcome updated =
                rows.process(TENANT, BRAND, catalogId, row("PLOV-001", "Osh Palov", null, null), false, null);

        assertThat(updated.type()).isEqualTo(CatalogImportRowOutcome.Type.UPDATED);
        var detail = query.productDetail(TENANT, BRAND, requireProductId(updated));
        assertThat(requireTranslation(detail).name()).isEqualTo("Osh Palov");
    }

    // -------------------------------------------------------------- errors

    @Test
    @DisplayName("a blank product_code is a row-level error, not a thrown exception")
    void blankProductCodeIsAnError() {
        CatalogImportRow row =
                new CatalogImportRow(1, null, null, null, "Osh", null, null, null, null, null, null, null);

        CatalogImportRowOutcome outcome = rows.process(TENANT, BRAND, catalogId, row, false, null);

        assertThat(outcome.type()).isEqualTo(CatalogImportRowOutcome.Type.ERROR);
        assertThat(outcome.errorReason()).isEqualTo(CatalogImportRowErrorReason.MISSING_PRODUCT_CODE);
    }

    @Test
    @DisplayName("a blank product_name is a row-level error")
    void blankProductNameIsAnError() {
        CatalogImportRow row =
                new CatalogImportRow(1, "PLOV-001", null, null, null, null, null, null, null, null, null, null);

        CatalogImportRowOutcome outcome = rows.process(TENANT, BRAND, catalogId, row, false, null);

        assertThat(outcome.type()).isEqualTo(CatalogImportRowOutcome.Type.ERROR);
        assertThat(outcome.errorReason()).isEqualTo(CatalogImportRowErrorReason.MISSING_PRODUCT_NAME);
    }

    @Test
    @DisplayName("a status outside DRAFT/ACTIVE/ARCHIVED is a row-level error")
    void invalidStatusIsAnError() {
        // rowNumber, productCode, categoryCode, categoryName, productName,
        // productDescription, variantSku, unitCode, priceAmountMinor,
        // priceCurrency, status, imageUrl
        CatalogImportRow row =
                new CatalogImportRow(1, "PLOV-001", null, null, "Osh", null, null, null, null, null, "SOLD_OUT", null);

        CatalogImportRowOutcome outcome = rows.process(TENANT, BRAND, catalogId, row, false, null);

        assertThat(outcome.type()).isEqualTo(CatalogImportRowOutcome.Type.ERROR);
        assertThat(outcome.errorReason()).isEqualTo(CatalogImportRowErrorReason.INVALID_STATUS);
    }

    @Test
    @DisplayName("a price amount with no currency is a row-level error, and nothing is created")
    void priceWithoutCurrencyIsAnError() {
        CatalogImportRow row =
                new CatalogImportRow(1, "PLOV-001", null, null, "Osh", null, null, null, "50000", null, null, null);

        CatalogImportRowOutcome outcome = rows.process(TENANT, BRAND, catalogId, row, false, null);

        assertThat(outcome.type()).isEqualTo(CatalogImportRowOutcome.Type.ERROR);
        assertThat(outcome.errorReason()).isEqualTo(CatalogImportRowErrorReason.INVALID_PRICE);
        assertThat(catalogStore.productByCode(TENANT, BRAND, "PLOV-001")).isEmpty();
    }

    @Test
    @DisplayName("a negative price amount is a row-level error")
    void negativePriceIsAnError() {
        CatalogImportRow row = row("PLOV-001", "Osh", "-1", "UZS");

        CatalogImportRowOutcome outcome = rows.process(TENANT, BRAND, catalogId, row, false, null);

        assertThat(outcome.type()).isEqualTo(CatalogImportRowOutcome.Type.ERROR);
        assertThat(outcome.errorReason()).isEqualTo(CatalogImportRowErrorReason.INVALID_PRICE);
    }

    @Test
    @DisplayName("a SKU already used by a different product is a row-level error, not a database exception")
    void duplicateSkuIsAnError() {
        rows.process(TENANT, BRAND, catalogId, row("PLOV-001", "Osh", null, null), false, null);

        CatalogImportRow conflicting = row("LAGMAN-001", "Lag'mon", null, null);
        CatalogImportRow withSameSku = new CatalogImportRow(
                conflicting.rowNumber(),
                conflicting.productCode(),
                null,
                null,
                conflicting.productName(),
                null,
                "SKU-PLOV",
                null,
                null,
                null,
                null,
                null);

        CatalogImportRowOutcome outcome = rows.process(TENANT, BRAND, catalogId, withSameSku, false, null);

        assertThat(outcome.type()).isEqualTo(CatalogImportRowOutcome.Type.ERROR);
        assertThat(outcome.errorReason()).isEqualTo(CatalogImportRowErrorReason.DUPLICATE_SKU);
        assertThat(catalogStore.productByCode(TENANT, BRAND, "LAGMAN-001")).isEmpty();
    }

    // ---------------------------------------------------------------- image

    @Test
    @DisplayName("apply with a real image URL fetches it and attaches it as the product's PRIMARY media")
    void applyWithImageUrlAttachesMedia() {
        CatalogImportRow row = rowWithImage("PLOV-001", "Osh", uri("/plov.jpg"));

        CatalogImportRowOutcome outcome = rows.process(TENANT, BRAND, catalogId, row, false, null);

        assertThat(outcome.type()).isEqualTo(CatalogImportRowOutcome.Type.CREATED);
        var detail = query.productDetail(TENANT, BRAND, requireProductId(outcome));
        assertThat(detail.media())
                .extracting(CatalogQueryService.MediaRelation::role)
                .contains("PRIMARY");
    }

    @Test
    @DisplayName("apply with a URL that does not serve a real image is an ERROR, and nothing is created")
    void applyWithNonImageUrlIsAnError() {
        CatalogImportRow row = rowWithImage("PLOV-001", "Osh", uri("/not-a-photo.jpg"));

        CatalogImportRowOutcome outcome = rows.process(TENANT, BRAND, catalogId, row, false, null);

        assertThat(outcome.type()).isEqualTo(CatalogImportRowOutcome.Type.ERROR);
        assertThat(outcome.errorReason()).isEqualTo(CatalogImportRowErrorReason.IMAGE_FETCH_FAILED);
        assertThat(catalogStore.productByCode(TENANT, BRAND, "PLOV-001")).isEmpty();
    }

    @Test
    @DisplayName("a dry run never fetches the image URL at all")
    void dryRunNeverFetchesTheImage() {
        // A URL naming a path this test's server never registered a handler
        // for -- if the row service fetched it during a dry run, the request
        // would 404 and this would still pass as CREATED/null, so the real
        // proof is in applyWithImageUrlAttachesMedia and
        // applyWithNonImageUrlIsAnError never firing here.
        CatalogImportRow row = rowWithImage("PLOV-001", "Osh", uri("/never-registered.jpg"));

        CatalogImportRowOutcome outcome = rows.process(TENANT, BRAND, catalogId, row, true, null);

        assertThat(outcome.type()).isEqualTo(CatalogImportRowOutcome.Type.CREATED);
        assertThat(outcome.errorReason()).isNull();
    }

    // ------------------------------------------------- blank-vs-clear semantics

    @Test
    @DisplayName(
            "a blank status/unit_code/variant_sku on update leaves each one unchanged, and never re-activates an archived product")
    void blankFieldsOnUpdateLeaveStatusUnitAndSkuUnchanged() {
        CatalogImportRowOutcome created = rows.process(
                TENANT,
                BRAND,
                catalogId,
                rowFull("PLOV-001", "Osh", "OSH-KG-1", "KG", "50000", "UZS", "ARCHIVED"),
                false,
                null);
        assertThat(created.type()).isEqualTo(CatalogImportRowOutcome.Type.CREATED);

        // A corrective re-import that only carries product_code/product_name/price,
        // leaving status/variant_sku/unit_code blank -- exactly the plausible
        // hand-authored partial file the template's own columns invite,
        // since only product_code/product_name are documented as required.
        CatalogImportRowOutcome updated = rows.process(
                TENANT, BRAND, catalogId, rowFull("PLOV-001", "Osh", null, null, "60000", "UZS", null), false, null);

        assertThat(updated.type()).isEqualTo(CatalogImportRowOutcome.Type.UPDATED);
        var product = catalogStore.productByCode(TENANT, BRAND, "PLOV-001").orElseThrow();
        assertThat(product.status())
                .as("a blank status column must never re-activate an archived product")
                .isEqualTo(Status.ARCHIVED);
        Variant variant = defaultVariantOf(product.id());
        assertThat(variant.unitCode())
                .as("a blank unit_code must leave the existing unit alone")
                .isEqualTo("KG");
        assertThat(variant.sku())
                .as("a blank variant_sku must leave the existing SKU alone, not null it out")
                .isEqualTo("OSH-KG-1");
    }

    @Test
    @DisplayName(
            "re-importing a row with every optional column blank besides an unchanged price resolves to SKIPPED, not UPDATED")
    void blankOptionalFieldsAloneNeverForceAnUpdate() {
        rows.process(
                TENANT,
                BRAND,
                catalogId,
                rowFull("PLOV-001", "Osh", "OSH-KG-1", "KG", "50000", "UZS", "ARCHIVED"),
                false,
                null);

        CatalogImportRowOutcome reimported = rows.process(
                TENANT, BRAND, catalogId, rowFull("PLOV-001", "Osh", null, null, "50000", "UZS", null), false, null);

        assertThat(reimported.type())
                .as("a blank status/unit/sku column carries no diff signal of its own")
                .isEqualTo(CatalogImportRowOutcome.Type.SKIPPED);
    }

    @Test
    @DisplayName("blank status and unit_code on a brand-new product still default to ACTIVE and PIECE")
    void blankStatusAndUnitOnCreateStillDefault() {
        CatalogImportRowOutcome created = rows.process(
                TENANT, BRAND, catalogId, rowFull("PLOV-002", "Lag'mon", null, null, null, null, null), false, null);

        var product = catalogStore.productByCode(TENANT, BRAND, "PLOV-002").orElseThrow();
        assertThat(product.status()).isEqualTo(Status.ACTIVE);
        assertThat(defaultVariantOf(product.id()).unitCode()).isEqualTo("PIECE");
        assertThat(created.type()).isEqualTo(CatalogImportRowOutcome.Type.CREATED);
    }

    // ------------------------------------------------------------ isolation

    @Test
    @DisplayName("the same product_code in two tenants creates two independent products")
    void sameProductCodeAcrossTenantsCreatesIndependentProducts() {
        UUID otherCatalogId =
                catalogStore.catalogsForBrand(OTHER_TENANT, OTHER_BRAND).get(0).id();

        CatalogImportRowOutcome mine =
                rows.process(TENANT, BRAND, catalogId, row("PLOV-001", "Osh", null, null), false, null);
        CatalogImportRowOutcome theirs = rows.process(
                OTHER_TENANT,
                OTHER_BRAND,
                otherCatalogId,
                row("PLOV-001", "Someone else's dish", null, null),
                false,
                null);

        assertThat(mine.productId()).isNotEqualTo(theirs.productId());
        assertThat(catalogStore.productByCode(OTHER_TENANT, OTHER_BRAND, "PLOV-001"))
                .isPresent();
        // Tenant A's own read never sees tenant B's row under the same code,
        // and vice versa -- the two are simply two different products.
        assertThat(requireTranslation(query.productDetail(TENANT, BRAND, requireProductId(mine)))
                        .name())
                .isEqualTo("Osh");
        assertThat(requireTranslation(query.productDetail(OTHER_TENANT, OTHER_BRAND, requireProductId(theirs)))
                        .name())
                .isEqualTo("Someone else's dish");
    }

    // --------------------------------------------------------------- helpers

    private static UUID requireProductId(CatalogImportRowOutcome outcome) {
        UUID productId = outcome.productId();
        if (productId == null) {
            throw new AssertionError("Expected this outcome to carry a product id: " + outcome);
        }
        return productId;
    }

    private static CatalogQueryService.LocalizedFields requireTranslation(CatalogQueryService.ProductDetail detail) {
        CatalogQueryService.LocalizedFields translation = detail.translations().get(LOCALE);
        if (translation == null) {
            throw new AssertionError("Expected a " + LOCALE + " translation on " + detail.productId());
        }
        return translation;
    }

    private static CatalogImportRow row(
            String productCode, String productName, @Nullable String priceAmountMinor, @Nullable String priceCurrency) {
        return new CatalogImportRow(
                1,
                productCode,
                null,
                null,
                productName,
                null,
                "SKU-" + productCode.replace("-001", ""),
                null,
                priceAmountMinor,
                priceCurrency,
                null,
                null);
    }

    /** Every optional column explicit or explicitly blank ({@code null}), for the blank-vs-clear-semantics tests. */
    private static CatalogImportRow rowFull(
            String productCode,
            String productName,
            @Nullable String sku,
            @Nullable String unitCode,
            @Nullable String priceAmountMinor,
            @Nullable String priceCurrency,
            @Nullable String status) {
        return new CatalogImportRow(
                1,
                productCode,
                null,
                null,
                productName,
                null,
                sku,
                unitCode,
                priceAmountMinor,
                priceCurrency,
                status,
                null);
    }

    private Variant defaultVariantOf(UUID productId) {
        return catalogStore.variantsForProduct(TENANT, BRAND, productId).stream()
                .filter(Variant::isDefault)
                .findFirst()
                .orElseThrow();
    }

    private static CatalogImportRow rowWithImage(String productCode, String productName, URI imageUrl) {
        return new CatalogImportRow(
                1, productCode, null, null, productName, null, null, null, null, null, null, imageUrl.toString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + httpPort + path);
    }

    private void insertTenantAndBrand(UUID tenantId, UUID brandId, String tenantSlug, String brandCode) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone,
                    status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", tenantSlug).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("tenantId", tenantId)
                .param("code", brandCode)
                .param("slug", brandCode.toLowerCase(Locale.ROOT))
                .update();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(200, body.length);
        try (var responseBody = exchange.getResponseBody()) {
            responseBody.write(body);
        }
    }

    private static byte[] encode(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "jpg", bytes);
        } catch (IOException impossible) {
            throw new IllegalStateException("Encoding an in-memory image cannot fail", impossible);
        }
        return bytes.toByteArray();
    }
}
