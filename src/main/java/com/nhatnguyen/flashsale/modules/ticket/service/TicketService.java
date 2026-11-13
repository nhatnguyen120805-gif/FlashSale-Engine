package com.nhatnguyen.flashsale.modules.ticket.service;

import com.nhatnguyen.flashsale.common.exception.TicketNotFoundException;
import com.nhatnguyen.flashsale.common.idempotency.IdempotencyService;
import com.nhatnguyen.flashsale.modules.order.repository.OrderRepository;
import com.nhatnguyen.flashsale.modules.ticket.dto.TicketResponse;
import com.nhatnguyen.flashsale.modules.ticket.repository.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    private final TicketRepository ticketRepository;
    private final OrderRepository orderRepository;
    private final IdempotencyService idempotencyService;
    private final StringRedisTemplate redisTemplate;

    public TicketService(
            TicketRepository ticketRepository,
            OrderRepository orderRepository,
            IdempotencyService idempotencyService,
            StringRedisTemplate redisTemplate) {
        this.ticketRepository = ticketRepository;
        this.orderRepository = orderRepository;
        this.idempotencyService = idempotencyService;
        this.redisTemplate = redisTemplate;
    }

    public List<TicketResponse> getAllTickets() {
        return ticketRepository.findAll().stream()
                .map(TicketResponse::from)
                .toList();
    }

    public TicketResponse getTicketById(Long id) {
        return ticketRepository.findById(id)
                .map(TicketResponse::from)
                .orElseThrow(() -> new TicketNotFoundException("Ticket not found with id: " + id));
    }

    public Integer getRealtimeStock(Long id) {
        String redisKey = "ticket:" + id + ":stock";
        String val = redisTemplate.opsForValue().get(redisKey);
        if (val != null) {
            try {
                return Integer.parseInt(val);
            } catch (NumberFormatException ignored) {
            }
        }
        return getTicketById(id).stock();
    }

    @Transactional
    public List<TicketResponse> resetAllTickets() {
        orderRepository.deleteAllInBatch();
        ticketRepository.resetAllStockToInitial();
        idempotencyService.clearLocalCache();

        List<TicketResponse> tickets = getAllTickets();
        try {
            for (TicketResponse t : tickets) {
                redisTemplate.opsForValue().set("ticket:" + t.id() + ":stock", String.valueOf(t.initialStock()));
            }
        } catch (Exception ex) {
            log.warn("Redis unreachable while resetting ticket stock keys, DB reset succeeded: {}", ex.getMessage());
        }
        return tickets;
    }
}
