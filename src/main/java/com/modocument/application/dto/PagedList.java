package com.modocument.application.dto;

import java.util.List;

/**
 * The paged-list envelope of the v1.2.0 optimization round
 * (docs/optimization/contracts.md §5): {@code {items, page, size, total}} —
 * {@code page} is zero-based, {@code size} the page size served (at most
 * {@link #MAX_SIZE}) and {@code total} the number of items across all pages.
 */
public record PagedList<T>(List<T> items, int page, int size, long total) {

    /** The largest page a paged list serves. */
    public static final int MAX_SIZE = 200;

    /** The page size when the caller names none. */
    public static final int DEFAULT_SIZE = 50;

    /** A requested page number, floored at 0. */
    public static int page(Integer requested) {
        return requested == null || requested < 0 ? 0 : requested;
    }

    /** A requested page size, defaulted and clamped to 1..{@link #MAX_SIZE}. */
    public static int size(Integer requested) {
        if (requested == null) {
            return DEFAULT_SIZE;
        }
        return Math.max(1, Math.min(MAX_SIZE, requested));
    }
}
