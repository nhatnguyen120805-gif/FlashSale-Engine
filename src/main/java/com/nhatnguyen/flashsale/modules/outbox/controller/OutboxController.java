package com.nhatnguyen.flashsale.modules.outbox.controller;

import com.nhatnguyen.flashsale.modules.outbox.dto.OutboxStatsResponse;
import com.nhatnguyen.flashsale.modules.outbox.service.OutboxService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/outbox")
public class OutboxController {

    private final OutboxService outboxService;

    public OutboxController(OutboxService outboxService) {
        this.outboxService = outboxService;
    }

    @GetMapping("/stats")
    public ResponseEntity<OutboxStatsResponse> getStats() {
        return ResponseEntity.ok(outboxService.getStats());
    }

    @PostMapping("/flush")
    public ResponseEntity<Map<String, Object>> flushPending() {
        int published = outboxService.processPendingEvents();
        OutboxStatsResponse stats = outboxService.getStats();
        return ResponseEntity.ok(Map.of(
                "publishedCount", published,
                "stats", stats
        ));
    }
}
