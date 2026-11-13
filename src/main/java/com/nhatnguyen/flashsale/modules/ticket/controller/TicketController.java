package com.nhatnguyen.flashsale.modules.ticket.controller;

import com.nhatnguyen.flashsale.modules.ticket.dto.TicketResponse;
import com.nhatnguyen.flashsale.modules.ticket.service.TicketService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @GetMapping
    public ResponseEntity<List<TicketResponse>> getAllTickets() {
        return ResponseEntity.ok(ticketService.getAllTickets());
    }

    @GetMapping("/{id}")
    public ResponseEntity<TicketResponse> getTicketById(@PathVariable Long id) {
        return ResponseEntity.ok(ticketService.getTicketById(id));
    }

    @GetMapping("/{id}/stock")
    public ResponseEntity<Map<String, Object>> getRealtimeStock(@PathVariable Long id) {
        Integer stock = ticketService.getRealtimeStock(id);
        return ResponseEntity.ok(Map.of("ticketId", id, "realtimeStock", stock));
    }
}
