package com.nhatnguyen.flashsale.common;

import com.nhatnguyen.flashsale.modules.ticket.entity.Ticket;
import com.nhatnguyen.flashsale.modules.ticket.repository.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final TicketRepository ticketRepository;
    private final StringRedisTemplate redisTemplate;

    public DataInitializer(TicketRepository ticketRepository, StringRedisTemplate redisTemplate) {
        this.ticketRepository = ticketRepository;
        this.redisTemplate = redisTemplate;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (ticketRepository.count() == 0) {
            log.info("Seeding initial flash-sale tickets into database...");
            Ticket vipTicket = new Ticket(
                    null,
                    "Taylor Swift Eras Tour — VIP Flash Sale",
                    "TS-VIP-2026",
                    new BigDecimal("299.99"),
                    100,
                    100
            );
            ticketRepository.save(vipTicket);

            Ticket gaTicket = new Ticket(
                    null,
                    "Taylor Swift Eras Tour — General Admission",
                    "TS-GA-2026",
                    new BigDecimal("99.99"),
                    500,
                    500
            );
            ticketRepository.save(gaTicket);
            log.info("Seeded tickets successfully with IDs: {} and {}", vipTicket.getId(), gaTicket.getId());
        }

        // Sync initial stock to Redis
        try {
            if (redisTemplate != null && redisTemplate.opsForValue() != null) {
                ticketRepository.findAll().forEach(ticket -> {
                    String redisKey = "ticket:" + ticket.getId() + ":stock";
                    redisTemplate.opsForValue().set(redisKey, String.valueOf(ticket.getStock()));
                    log.info("Preloaded stock to Redis: key={}, stock={}", redisKey, ticket.getStock());
                });
            }
        } catch (Exception ex) {
            log.warn("Could not sync stock to Redis during startup (Redis might not be running yet): {}", ex.getMessage());
        }
    }
}
