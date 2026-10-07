package com.geekonsites.backend.dto;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * PHASE 9 — normalizes raw pagination query parameters into a safe {@link Pageable}.
 *
 * <p>Rules: page &lt; 0 is clamped to 0; size &lt;= 0 falls back to {@link #DEFAULT_SIZE};
 * size is capped at {@link #MAX_SIZE}. This prevents callers from recreating the
 * unbounded-read problem with {@code size=1000000}.
 */
public final class PageRequestParams {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private PageRequestParams() {
    }

    public static Pageable of(Integer page, Integer size) {
        return of(page, size, Sort.unsorted());
    }

    public static Pageable of(Integer page, Integer size, Sort sort) {
        int normalizedPage = page == null || page < 0 ? 0 : page;
        int normalizedSize = size == null || size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return PageRequest.of(normalizedPage, normalizedSize, sort == null ? Sort.unsorted() : sort);
    }
}
