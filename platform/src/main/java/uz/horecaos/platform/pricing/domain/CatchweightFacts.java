package uz.horecaos.platform.pricing.domain;

/**
 * What pricing needs to know about a catchweight variant (ADR 0137): the weight
 * its price is quoted per, and the weight a quote is provisional against.
 *
 * <p>Resolved from the published menu, never from the draft, and handed to the
 * engine as a value like every other input -- the engine reads no table.
 *
 * @param quantumGrams        {@code pricing.prices.amount_minor} is the price per this many grams
 * @param nominalGramsPerUnit the menu estimate of one unit's weight, which the quote is provisional against
 */
public record CatchweightFacts(int quantumGrams, int nominalGramsPerUnit) {

    public CatchweightFacts {
        if (quantumGrams <= 0) {
            throw new IllegalArgumentException("A catchweight pricing quantum must be positive");
        }
        if (nominalGramsPerUnit <= 0) {
            throw new IllegalArgumentException("A catchweight nominal weight must be positive");
        }
    }
}
