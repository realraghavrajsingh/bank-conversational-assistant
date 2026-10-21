package io.lakeside.assistant.guard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Grounding guard: every amount and reference the customer sees must have been returned by
 * the banking backend during this conversation.
 *
 * <p>The system prompt says the same thing, but prompts are advisory. This check is
 * deterministic and runs on every final reply before it leaves the server. Arithmetic such
 * as totals is done by tools, so legitimate figures always appear verbatim in tool results.
 */
public final class Grounding {

    private static final String NUM = "([0-9][0-9,]*(?:\\.[0-9]+)?)";
    /** Currency-marked amounts: ₹1,899 / Rs. 1899.00 / INR 1,899 / 1,899 rupees. */
    private static final Pattern CURRENCY = Pattern.compile(
            "(?:₹|\\brs\\.?|\\binr)\\s*" + NUM + "|" + NUM + "\\s*(?:rupees|inr)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Bare numbers that look like money: digit grouping (incl. Indian 1,00,000) or exactly two decimals. */
    private static final Pattern BARE = Pattern.compile(
            "(?<![\\w.-])([0-9]{1,3}(?:,[0-9]{2,3})+(?:\\.[0-9]{1,2})?|[0-9]+\\.[0-9]{2})(?![\\w%])");
    private static final Pattern REFS = Pattern.compile("\\b(SR-\\d+|TXN-\\d+)\\b");
    private static final Pattern ANY_NUMBER = Pattern.compile("[0-9][0-9,]*(?:\\.[0-9]+)?");

    private Grounding() {
    }

    public record Result(boolean ok, List<String> unsupportedAmounts, List<String> unsupportedRefs) {
        public List<String> all() {
            return java.util.stream.Stream.concat(unsupportedAmounts.stream(), unsupportedRefs.stream()).toList();
        }
    }

    public static String normalise(String raw) {
        try {
            return new BigDecimal(raw.replace(",", "")).setScale(2, RoundingMode.HALF_UP).toPlainString();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Records every number and reference found anywhere in a tool result. */
    public static void collectEvidence(JsonNode node, Set<String> amounts, Set<String> refs) {
        if (node == null || node.isNull() || node.isBoolean()) {
            return;
        }
        if (node.isContainerNode()) {
            node.forEach(child -> collectEvidence(child, amounts, refs));
        } else if (node.isNumber()) {
            amounts.add(node.decimalValue().setScale(2, RoundingMode.HALF_UP).toPlainString());
        } else if (node.isTextual()) {
            String s = node.asText();
            Matcher r = REFS.matcher(s);
            while (r.find()) {
                refs.add(r.group(1));
            }
            Matcher n = ANY_NUMBER.matcher(s);
            while (n.find()) {
                String norm = normalise(n.group());
                if (norm != null) {
                    amounts.add(norm);
                }
            }
        }
    }

    public static Result check(String reply, Set<String> amounts, Set<String> refs) {
        Set<String> found = new TreeSet<>();
        Matcher c = CURRENCY.matcher(reply);
        while (c.find()) {
            found.add(c.group(1) != null ? c.group(1) : c.group(2));
        }
        Matcher b = BARE.matcher(reply);
        while (b.find()) {
            found.add(b.group(1));
        }
        List<String> badAmounts = found.stream()
                .filter(raw -> {
                    String n = normalise(raw);
                    return n == null || !amounts.contains(n);
                })
                .toList();
        Set<String> badRefs = new TreeSet<>();
        Matcher r = REFS.matcher(reply);
        while (r.find()) {
            if (!refs.contains(r.group(1))) {
                badRefs.add(r.group(1));
            }
        }
        return new Result(badAmounts.isEmpty() && badRefs.isEmpty(), badAmounts, List.copyOf(badRefs));
    }
}
