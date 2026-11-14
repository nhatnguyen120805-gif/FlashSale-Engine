package com.nhatnguyen.flashsale.modules.order.repository;

import com.nhatnguyen.flashsale.modules.order.entity.Order;
import com.nhatnguyen.flashsale.modules.order.entity.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderNumber(String orderNumber);

    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);

    List<Order> findByUserId(String userId);

    long countByTicketId(Long ticketId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Order o SET o.status = :newStatus, o.updatedAt = CURRENT_TIMESTAMP WHERE o.orderNumber = :orderNumber AND o.status = :expectedStatus")
    int transitionStatusAtomically(@Param("orderNumber") String orderNumber,
                                   @Param("expectedStatus") OrderStatus expectedStatus,
                                   @Param("newStatus") OrderStatus newStatus);
}
