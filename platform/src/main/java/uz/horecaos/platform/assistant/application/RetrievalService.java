package uz.horecaos.platform.assistant.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.assistant.application.KnowledgeService.KnowledgeMatch;
import uz.horecaos.platform.assistant.domain.FactKind;
import uz.horecaos.platform.assistant.domain.HoursText;
import uz.horecaos.platform.assistant.domain.MoneyFormat;
import uz.horecaos.platform.assistant.domain.QuestionClassification;
import uz.horecaos.platform.assistant.domain.RetrievalKind;
import uz.horecaos.platform.catalog.api.MenuSearchPort;
import uz.horecaos.platform.catalog.api.MenuSearchPort.Dish;
import uz.horecaos.platform.catalog.api.MenuSearchPort.Form;
import uz.horecaos.platform.catalog.api.MenuSearchPort.MenuSearchResult;
import uz.horecaos.platform.configuration.SearchText;
import uz.horecaos.platform.fulfillment.api.BranchResolutionPort;
import uz.horecaos.platform.fulfillment.api.BranchResolutionPort.DeliveryBranchMatch;
import uz.horecaos.platform.ordering.api.CustomerBotOrderingPort;
import uz.horecaos.platform.ordering.api.CustomerBotOrderingPort.OrderCard;
import uz.horecaos.platform.tenancy.api.BranchDirectory;
import uz.horecaos.platform.tenancy.api.BranchDirectory.Branch;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;
import uz.horecaos.platform.tenancy.api.Serviceability;
import uz.horecaos.platform.tenancy.api.ServiceabilityResolver;

/**
 * Fetching the platform facts a question needs (ADR 0069: "for each turn:
 * classify the question, resolve scope from the conversation's brand and
 * location, then fetch -- knowledge entries for that scope and locale, and live
 * platform reads for price, availability, serviceability, or order state as the
 * classification requires").
 *
 * <p>Every fact comes from the module that owns it, through its {@code api} port:
 * a dish's price and whether it is sold out from the storefront's own assembled
 * menu ({@code MenuSearchPort}); branches and hours from {@code BranchDirectory}
 * and the one serviceability resolver; coverage from ADR 0037's zone algorithm;
 * an order from {@code CustomerBotOrderingPort}, and only for the customer
 * account the channel adapter proved. Nothing is read from this module's own
 * copy of anything, because this module keeps no copy.
 *
 * <p><strong>Absence is not an answer.</strong> A dish that is not found, a
 * currency with no decided exponent, a channel with no price book: each yields no
 * fact, never a fact that says "free" or "not sold". An empty result is how the
 * caller knows to refuse.
 */
@Service
public class RetrievalService {

    static final int MAX_DISHES = 3;
    static final int MAX_FORMS_PER_DISH = 6;
    static final int MAX_BRANCHES_FOR_LISTING = 8;
    static final int MAX_BRANCHES_FOR_MENU = 4;

    private final BranchDirectory branches;
    private final MenuSearchPort menu;
    private final SalesChannelLookup channels;
    private final ServiceabilityResolver serviceability;
    private final BranchResolutionPort coverage;
    private final CustomerBotOrderingPort orders;
    private final KnowledgeService knowledge;
    private final Clock clock;

    RetrievalService(
            BranchDirectory branches,
            MenuSearchPort menu,
            SalesChannelLookup channels,
            ServiceabilityResolver serviceability,
            BranchResolutionPort coverage,
            CustomerBotOrderingPort orders,
            KnowledgeService knowledge,
            Clock clock) {
        this.branches = branches;
        this.menu = menu;
        this.channels = channels;
        this.serviceability = serviceability;
        this.coverage = coverage;
        this.orders = orders;
        this.knowledge = knowledge;
        this.clock = clock;
    }

    /**
     * @param customerAccountId the account the channel adapter has proved for this
     *                          chat, or null -- the only identity an order is read for
     * @param localeOrder       the reply language first, then the languages knowledge
     *                          entries and dish names are tried in
     */
    RetrievedFacts retrieve(
            UUID tenantId,
            UUID brandId,
            @Nullable UUID customerAccountId,
            QuestionClassification classification,
            String question,
            String locale,
            List<String> localeOrder,
            String priceChannelCode,
            @Nullable GeoPoint sharedLocation) {
        RetrievedFacts.Builder facts = new RetrievedFacts.Builder();
        List<Branch> allBranches = branches.activeBranches(tenantId, brandId);
        List<Branch> inScope = resolveScope(allBranches, classification);
        facts.locations(inScope.stream().map(Branch::locationId).collect(java.util.stream.Collectors.toSet()));

        if (classification.kinds().contains(RetrievalKind.ORDER_STATUS)) {
            orderFacts(facts, tenantId, brandId, customerAccountId, allBranches, locale);
        }
        if (classification.needsMenu() && !classification.dishTerms().isEmpty()) {
            menuFacts(facts, tenantId, brandId, inScope, classification, locale, localeOrder, priceChannelCode);
        }
        if (classification.kinds().contains(RetrievalKind.BRANCHES)) {
            branchFacts(facts, scopedForListing(allBranches, inScope), locale);
        }
        if (classification.kinds().contains(RetrievalKind.HOURS)) {
            hoursFacts(facts, tenantId, brandId, scopedForListing(allBranches, inScope), locale, priceChannelCode);
        }
        if (classification.kinds().contains(RetrievalKind.COVERAGE)) {
            coverageFacts(facts, tenantId, brandId, sharedLocation);
        }
        knowledgeFacts(facts, tenantId, brandId, facts.build().locationIds(), localeOrder, question);
        return facts.build();
    }

    // ------------------------------------------------------------------- scope

    /**
     * The branches a question is about: a branch it names (by its own name, its
     * district or its landmark), else the only branch there is, else all of them.
     * "All of them" is honest -- a question about the menu with no branch named is
     * answered for every branch, and a price that is the same everywhere is said
     * once.
     */
    static List<Branch> resolveScope(List<Branch> all, QuestionClassification classification) {
        if (all.size() <= 1) {
            return all;
        }
        List<String> questionWords = SearchText.tokens(classification.skeleton());
        List<Branch> named = new ArrayList<>();
        for (Branch branch : all) {
            if (namesBranch(branch, questionWords)) {
                named.add(branch);
            }
        }
        return named.isEmpty() ? all : named;
    }

    private static boolean namesBranch(Branch branch, List<String> questionWords) {
        List<String> nameWords = new ArrayList<>();
        nameWords.addAll(SearchText.tokens(branch.name()));
        if (branch.district() != null) {
            nameWords.addAll(SearchText.tokens(branch.district()));
        }
        if (branch.landmark() != null) {
            nameWords.addAll(SearchText.tokens(branch.landmark()));
        }
        for (String nameWord : nameWords) {
            if (nameWord.length() < 4) {
                continue;
            }
            for (String questionWord : questionWords) {
                if (SearchText.wordMatches(questionWord, nameWord)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Branch> scopedForListing(List<Branch> all, List<Branch> inScope) {
        List<Branch> chosen = inScope.isEmpty() ? all : inScope;
        return chosen.size() > MAX_BRANCHES_FOR_LISTING ? chosen.subList(0, MAX_BRANCHES_FOR_LISTING) : chosen;
    }

    // -------------------------------------------------------------------- menu

    private void menuFacts(
            RetrievedFacts.Builder facts,
            UUID tenantId,
            UUID brandId,
            List<Branch> inScope,
            QuestionClassification classification,
            String locale,
            List<String> localeOrder,
            String channelCode) {
        boolean priceAsked = classification.kinds().contains(RetrievalKind.PRICE);
        List<Branch> queried =
                inScope.size() > MAX_BRANCHES_FOR_MENU ? inScope.subList(0, MAX_BRANCHES_FOR_MENU) : inScope;

        // One search per branch; a dish's forms are then grouped by what the customer
        // would be told, so a price that is the same at every branch asked is one fact.
        Map<FormKey, FormAt> byForm = new LinkedHashMap<>();
        Map<UUID, Dish> dishes = new LinkedHashMap<>();
        for (Branch branch : queried) {
            MenuSearchResult found = menu.search(
                    tenantId,
                    brandId,
                    branch.locationId(),
                    channelCode,
                    localeOrder,
                    classification.dishTerms(),
                    MAX_DISHES);
            String currency = found.currency();
            for (Dish dish : found.hits()) {
                dishes.putIfAbsent(dish.productId(), dish);
                List<Form> forms = dish.forms().size() > MAX_FORMS_PER_DISH
                        ? dish.forms().subList(0, MAX_FORMS_PER_DISH)
                        : dish.forms();
                for (Form form : forms) {
                    FormKey key = new FormKey(
                            dish.productId(),
                            form.variantId(),
                            form.amountMinor(),
                            currency,
                            form.orderable(),
                            form.onSaleNow());
                    byForm.computeIfAbsent(key, ignored -> new FormAt(form, new ArrayList<>()))
                            .branches()
                            .add(branch);
                }
            }
        }

        for (Map.Entry<FormKey, FormAt> entry : byForm.entrySet()) {
            FormKey key = entry.getKey();
            Form form = entry.getValue().form();
            List<Branch> where = entry.getValue().branches();
            Dish dish = java.util.Objects.requireNonNull(dishes.get(key.productId()));
            boolean everywhere = where.size() == queried.size();
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("item", dish.name());
            if (form.unitCode() != null && dishHasSeveralForms(dishes, byForm, key.productId())) {
                attributes.put("form", form.unitCode());
            }
            attributes.put(
                    "branch", everywhere && queried.size() > 1 ? "every branch asked about" : branchNames(where));
            attributes.put("availability", availability(form));
            if (form.remainingQuantity() != null) {
                attributes.put(
                        "remaining",
                        form.remainingQuantity().stripTrailingZeros().toPlainString());
            }

            Map<String, Object> provenance = new LinkedHashMap<>();
            provenance.put("variantId", key.variantId().toString());
            provenance.put("productId", key.productId().toString());
            provenance.put(
                    "locationIds",
                    where.stream().map(b -> b.locationId().toString()).toList());
            provenance.put("channel", channelCode);

            if (priceAsked) {
                Optional<String> price = key.amountMinor() == null || key.currency() == null
                        ? Optional.empty()
                        : MoneyFormat.format(key.amountMinor(), key.currency(), locale);
                if (price.isPresent()) {
                    attributes.put("price", price.get());
                    attributes.put(
                            "priceCovers",
                            "the menu price of this item alone; extras, promotions and delivery are not included");
                    if (form.pricePerGrams() != null) {
                        attributes.put("priceIsPer", form.pricePerGrams() + " g");
                    }
                    provenance.put("amountMinor", key.amountMinor());
                    provenance.put("currency", key.currency());
                } else {
                    attributes.put("price", "no price is published for this item right now");
                }
                facts.add(FactKind.PRICE, attributes, provenance);
            } else {
                facts.add(FactKind.AVAILABILITY, attributes, provenance);
            }
        }
    }

    private static boolean dishHasSeveralForms(Map<UUID, Dish> dishes, Map<FormKey, FormAt> byForm, UUID productId) {
        return byForm.keySet().stream()
                        .filter(key -> key.productId().equals(productId))
                        .map(FormKey::variantId)
                        .distinct()
                        .count()
                > 1;
    }

    private static String availability(Form form) {
        if (!form.orderable()) {
            return "sold out or not stocked right now";
        }
        if (!form.onSaleNow()) {
            return "not on sale at this hour";
        }
        return "available";
    }

    private static String branchNames(List<Branch> where) {
        return String.join(", ", where.stream().map(Branch::name).toList());
    }

    private record FormKey(
            UUID productId,
            UUID variantId,
            @Nullable Long amountMinor,
            @Nullable String currency,
            boolean orderable,
            boolean onSaleNow) {}

    private record FormAt(Form form, List<Branch> branches) {}

    // ---------------------------------------------------------------- branches

    private void branchFacts(RetrievedFacts.Builder facts, List<Branch> chosen, String locale) {
        for (Branch branch : chosen) {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("branch", branch.name());
            String address = addressOf(branch);
            if (!address.isEmpty()) {
                attributes.put("address", address);
            }
            if (branch.landmark() != null && !branch.landmark().isBlank()) {
                attributes.put("landmark", branch.landmark());
            }
            if (branch.contactPhone() != null && !branch.contactPhone().isBlank()) {
                attributes.put("branchPhone", branch.contactPhone());
            }
            if (attributes.size() == 1) {
                // A branch with no published address says nothing a customer can use.
                continue;
            }
            facts.add(
                    FactKind.BRANCH,
                    attributes,
                    Map.of("locationId", branch.locationId().toString()));
        }
    }

    private static String addressOf(Branch branch) {
        List<String> parts = new ArrayList<>();
        for (String part : new String[] {branch.addressLine(), branch.district(), branch.city()}) {
            if (part != null && !part.isBlank()) {
                parts.add(part.strip());
            }
        }
        return String.join(", ", parts);
    }

    // ------------------------------------------------------------------- hours

    private void hoursFacts(
            RetrievedFacts.Builder facts,
            UUID tenantId,
            UUID brandId,
            List<Branch> chosen,
            String locale,
            String channelCode) {
        Optional<SalesChannel> channel = channels.byCode(tenantId, channelCode);
        Instant now = clock.instant();
        for (Branch branch : chosen) {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("branch", branch.name());
            List<BranchDirectory.WeeklyWindow> pickup = branch.weeklyHours().get(FulfillmentMode.PICKUP);
            List<BranchDirectory.WeeklyWindow> delivery = branch.weeklyHours().get(FulfillmentMode.DELIVERY);
            if (pickup != null) {
                attributes.put("pickupHours", HoursText.format(pickup, locale));
            }
            if (delivery != null) {
                attributes.put("deliveryHours", HoursText.format(delivery, locale));
            }
            channel.ifPresent(found -> {
                Serviceability answer = serviceability.resolve(
                        tenantId, brandId, branch.locationId(), found.id(), FulfillmentMode.PICKUP, now);
                ZoneId zone = ZoneId.of(branch.timezone());
                if (answer.available()) {
                    attributes.put("rightNow", "open for orders");
                } else if (answer.nextAvailableAt() != null) {
                    attributes.put(
                            "rightNow",
                            "closed now, opens "
                                    + HoursText.dayAndTime(
                                            ZonedDateTime.ofInstant(answer.nextAvailableAt(), zone), locale));
                } else {
                    attributes.put("rightNow", "closed now");
                }
            });
            if (attributes.size() == 1) {
                continue;
            }
            facts.add(
                    FactKind.HOURS,
                    attributes,
                    Map.of("locationId", branch.locationId().toString()));
        }
    }

    // ---------------------------------------------------------------- coverage

    private void coverageFacts(
            RetrievedFacts.Builder facts, UUID tenantId, UUID brandId, @Nullable GeoPoint sharedLocation) {
        if (sharedLocation == null) {
            facts.add(
                    FactKind.COVERAGE,
                    Map.of(
                            "note",
                            "The customer has not shared a location, so delivery cannot be checked. Ask them to "
                                    + "share their location in this chat (the attachment menu, then Location)."),
                    Map.of("located", false));
            return;
        }
        List<DeliveryBranchMatch> matches =
                coverage.deliveryCandidates(tenantId, brandId, sharedLocation, clock.instant());
        if (matches.isEmpty()) {
            facts.add(
                    FactKind.COVERAGE,
                    Map.of("result", "none of this brand's branches deliver to the location the customer shared"),
                    Map.of("located", true, "branchCount", 0));
            return;
        }
        List<DeliveryBranchMatch> ranked = matches.stream()
                .sorted(java.util.Comparator.comparingInt(DeliveryBranchMatch::zonePriority)
                        .reversed()
                        .thenComparingDouble(DeliveryBranchMatch::zoneAreaSquareMeters)
                        .thenComparing(match -> match.zoneId().toString()))
                .toList();
        for (DeliveryBranchMatch match : ranked) {
            Map<String, Object> provenance = new LinkedHashMap<>();
            provenance.put("located", true);
            provenance.put("locationId", match.locationId().toString());
            provenance.put("zoneId", match.zoneId().toString());
            provenance.put("zoneVersion", match.zoneVersion());
            facts.add(
                    FactKind.COVERAGE,
                    Map.of(
                            "branch",
                            match.displayName(),
                            "result",
                            "this branch delivers to the location the customer shared"),
                    provenance);
        }
    }

    // ------------------------------------------------------------------- order

    private void orderFacts(
            RetrievedFacts.Builder facts,
            UUID tenantId,
            UUID brandId,
            @Nullable UUID customerAccountId,
            List<Branch> allBranches,
            String locale) {
        facts.customerSpecific();
        if (customerAccountId == null) {
            facts.add(
                    FactKind.ORDER,
                    Map.of(
                            "note",
                            "This chat is not linked to a customer account, so no order can be looked up. A person "
                                    + "can help, or the customer can sign in through the storefront."),
                    Map.of("linked", false));
            return;
        }
        Optional<OrderCard> latest = orders.latestOrder(tenantId, brandId, customerAccountId);
        if (latest.isEmpty()) {
            facts.add(
                    FactKind.ORDER,
                    Map.of("note", "There is no order on this customer's account yet."),
                    Map.of("linked", true, "orders", 0));
            return;
        }
        OrderCard card = latest.get();
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("scope", "the customer's most recent order");
        attributes.put("orderNumber", card.publicOrderNumber());
        attributes.put("status", card.status());
        attributes.put("statusMeaning", statusMeaning(card.status()));
        attributes.put("stillInProgress", Boolean.toString(card.live()));
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("linked", true);
        provenance.put("orderId", card.orderId().toString());
        provenance.put("status", card.status());
        if (card.live() && card.promisedAt() != null && !allBranches.isEmpty()) {
            ZoneId zone = ZoneId.of(allBranches.getFirst().timezone());
            attributes.put("promisedAround", HoursText.time(ZonedDateTime.ofInstant(card.promisedAt(), zone)));
        }
        Optional<String> total = MoneyFormat.format(card.totalMinor(), card.currency(), locale);
        if (total.isPresent()) {
            attributes.put("orderTotal", total.get());
            provenance.put("amountMinor", card.totalMinor());
            provenance.put("currency", card.currency());
        }
        facts.add(FactKind.ORDER, attributes, provenance);
    }

    private static String statusMeaning(String status) {
        return switch (status) {
            case "RECEIVED" -> "received, not yet accepted by the restaurant";
            case "PAYMENT_AUTHORIZING" -> "waiting for the payment to be confirmed";
            case "AWAITING_APPROVAL" -> "waiting for the restaurant to accept it";
            case "PAYMENT_FAILED" -> "the payment did not go through, so the order was not placed";
            case "CONFIRMED" -> "accepted by the restaurant";
            case "REJECTED" -> "declined by the restaurant";
            case "EXPIRED" -> "not accepted in time, so it was cancelled";
            case "PREPARING" -> "being prepared in the kitchen";
            case "READY" -> "ready";
            case "FULFILLING" -> "on its way to the customer";
            case "COMPLETED" -> "completed";
            case "CANCELLED" -> "cancelled";
            default -> "in a state this assistant has no description for";
        };
    }

    // --------------------------------------------------------------- knowledge

    private void knowledgeFacts(
            RetrievedFacts.Builder facts,
            UUID tenantId,
            UUID brandId,
            Set<UUID> locationIds,
            List<String> localeOrder,
            String question) {
        List<KnowledgeMatch> matches =
                knowledge.retrieve(tenantId, brandId, new LinkedHashSet<>(locationIds), localeOrder, question);
        for (KnowledgeMatch match : matches) {
            Map<String, Object> provenance = new LinkedHashMap<>();
            provenance.put("entryId", match.entryId().toString());
            provenance.put("version", match.version());
            facts.add(
                    FactKind.KNOWLEDGE,
                    Map.of("question", match.questionForm(), "answer", match.answerBody()),
                    provenance);
            facts.knowledgeVersion(match.entryId(), match.version());
        }
    }
}
