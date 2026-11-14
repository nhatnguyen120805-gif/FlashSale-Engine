package com.nhatnguyen.flashsale.modules.outbox.dto;

public record OutboxStatsResponse(
        long pendingCount,
        long publishedCount,
        long failedCount
) {
}
