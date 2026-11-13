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
            log.info("Seeding initial flash-sale concert tickets (Vietnam) into database...");

            Ticket mtpTicket = new Ticket(
                    null,
                    "Sơn Tùng M-TP — Live Concert 2026 (Hạng VVIP Sky)",
                    "MTP-VVIP-2026",
                    new BigDecimal("3500000"),
                    100,
                    100
            );
            ticketRepository.save(mtpTicket);

            Ticket atvncgTicket = new Ticket(
                    null,
                    "Anh Trai Vượt Ngàn Chông Gai — Concert Mỹ Đình (Fanzone VIP)",
                    "ATVNCG-VIP-2026",
                    new BigDecimal("2200000"),
                    250,
                    250
            );
            ticketRepository.save(atvncgTicket);

            Ticket hatTicket = new Ticket(
                    null,
                    "Hà Anh Tuấn — Live Concert Chân Trời Rực Rỡ (Hạng GA)",
                    "HAT-GA-2026",
                    new BigDecimal("950000"),
                    500,
                    500
            );
            ticketRepository.save(hatTicket);

            log.info("Seeded Vietnamese concert tickets successfully: IDs [{}, {}, {}]",
                    mtpTicket.getId(), atvncgTicket.getId(), hatTicket.getId());
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
