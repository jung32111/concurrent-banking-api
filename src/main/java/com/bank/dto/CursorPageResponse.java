package com.bank.dto;

import java.util.List;

public record CursorPageResponse<T>(
        List<T> data,
        Long nextCursor,
        boolean hasNext
) {
    public static <T> CursorPageResponse<T> of(List<T> data, Long nextCursor, boolean hasNext) {
        return new CursorPageResponse<>(data, nextCursor, hasNext);
    }
}
