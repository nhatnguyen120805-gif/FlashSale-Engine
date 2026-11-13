package com.nhatnguyen.flashsale.modules.ticket.service;

import com.nhatnguyen.flashsale.common.exception.TicketNotFoundException;
import com.nhatnguyen.flashsale.modules.ticket.dto.TicketResponse;
import com.nhatnguyen.flashsale.modules.ticket.repository.TicketRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class TicketService {

    private final TicketRepository ticketRepository;
    private final StringRedisTemplate redisTemplate;

    public TicketService(TicketRepository ticketRepository, StringRedisTemplate redisTemplate) {
        this.ticketRepository = ticketRepository;
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
}
