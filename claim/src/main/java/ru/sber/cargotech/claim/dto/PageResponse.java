package ru.sber.cargotech.claim.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record PageResponse<T>(
    List<T> content,
    long totalElements,
    int totalPages,
    int size,
    int number,
    boolean first,
    boolean last,
    int numberOfElements,
    boolean empty
) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
            page.getContent(),
            page.getTotalElements(),
            page.getTotalPages(),
            page.getSize(),
            page.getNumber(),
            page.isFirst(),
            page.isLast(),
            page.getNumberOfElements(),
            page.isEmpty()
        );
    }
}
