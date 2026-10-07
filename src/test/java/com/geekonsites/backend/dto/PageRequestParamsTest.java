package com.geekonsites.backend.dto;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** PHASE 9 — pagination input normalization and envelope behaviour. */
class PageRequestParamsTest {

    @Test
    void defaultsApplyForMissingOrInvalidInput() {
        assertEquals(0, PageRequestParams.of(null, null).getPageNumber());
        assertEquals(PageRequestParams.DEFAULT_SIZE, PageRequestParams.of(null, null).getPageSize());
        assertEquals(0, PageRequestParams.of(-5, 0).getPageNumber());
        assertEquals(PageRequestParams.DEFAULT_SIZE, PageRequestParams.of(0, -10).getPageSize());
    }

    @Test
    void sizeIsCappedSoCallersCannotRecreateUnboundedReads() {
        assertEquals(PageRequestParams.MAX_SIZE, PageRequestParams.of(0, 1_000_000).getPageSize());
        Pageable pageable = PageRequestParams.of(2, 5);
        assertEquals(2, pageable.getPageNumber());
        assertEquals(5, pageable.getPageSize());
    }

    @Test
    void envelopeComputesTotalsFirstAndLast() {
        PageResponse<String> middle = PageResponse.of(List.of("a", "b"), 1, 2, 5);
        assertEquals(5, middle.totalElements());
        assertEquals(3, middle.totalPages());
        assertFalse(middle.first());
        assertFalse(middle.last());

        PageResponse<String> last = PageResponse.of(List.of("e"), 2, 2, 5);
        assertTrue(last.last());
        assertFalse(last.first());
    }
}
