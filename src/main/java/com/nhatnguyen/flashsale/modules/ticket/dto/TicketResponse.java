package com.nhatnguyen.flashsale.modules.ticket.dto;

import com.nhatnguyen.flashsale.modules.ticket.entity.Ticket;

import java.math.BigDecimal;

public record TicketResponse(
        Long id,
        String eventName,
        String ticketCode,
        BigDecimal price,
        Integer stock,
        Integer initialStock
) {
    public static TicketResponse from(Ticket ticket) {
        return new TicketResponse(
                ticket.getId(),
                ticket.getEventName(),
                ticket.getTicketCode(),
                ticket.getPrice(),
                ticket.getStock(),
                ticket.getInitialStock()
        );
    }
}
