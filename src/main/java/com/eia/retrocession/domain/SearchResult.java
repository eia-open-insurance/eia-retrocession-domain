package com.eia.retrocession.domain;

import java.util.List;
import java.util.UUID;

/**
 * Common search response shared by every EIA vertical so the shell can render a
 * single global-search list.
 *
 * <p>{@code type} identifies the record vertical ({@code CONTRACT},
 * {@code CESSION}, {@code RETROCESSION}) and {@code href} is the shell route
 * that opens the record. The payload is intentionally projection-only: the
 * search endpoint must not leak the full aggregate.</p>
 */
public record SearchResult(String query, int limit, List<Item> items) {

    /** Page size used when the caller does not provide {@code limit}. */
    public static final int DEFAULT_LIMIT = 10;

    /** Hard ceiling for {@code limit}; larger requests are clamped, not rejected. */
    public static final int MAX_LIMIT = 25;

    public record Item(
            UUID id,
            String type,
            String label,
            String secondary,
            String status,
            String href
    ) {}

    /** Applies the documented default and the maximum page size. */
    public static int effectiveLimit(int requested) {
        return Math.max(1, Math.min(requested, MAX_LIMIT));
    }

    /** Renders the {@code primary · status} descriptor used by the shell. */
    public static String secondary(String descriptor, String status) {
        if (descriptor == null || descriptor.isBlank()) {
            return status == null ? "" : status;
        }
        return status == null ? descriptor : descriptor + " · " + status;
    }
}
