# Kế Hoạch Triển Khai Chi Tiết — FlashSale-Engine

- [x] **Phase 1: Foundation & Core Infrastructure**
  - [x] **Task 1.1**: Khởi tạo `pom.xml` (Spring Boot 3.3.5, Java 21, Spring Data JPA, Redis, RabbitMQ, Validation, Testcontainers, Actuator, No-Lombok) & Maven Wrapper.
  - [x] **Task 1.2**: Cấu hình `application.yml` & `application-test.yml` (Virtual Threads, Problem Details RFC 7807, HikariCP, Redis, RabbitMQ, Graceful shutdown).
  - [x] **Task 1.3**: Thiết kế Entities & Repositories (`Ticket`, `Order`, `Payment`, `OrderStatus`, `PaymentStatus`) kèm Java 21 Records cho DTOs & Events.
  - [x] **Task 1.4**: Data Seeder (`DataInitializer`) nạp sẵn dữ liệu mẫu vào PostgreSQL & đồng bộ tồn kho vào Redis khi startup.

- [x] **Phase 2: Concurrency & Lock Strategies (Bài toán Trừ Kho)**
  - [x] **Task 2.1**: Thiết kế interface `StockDeductionStrategy` và `DeductionResult`.
  - [x] **Task 2.2**: Triển khai `PessimisticLockStrategy` (`@Lock(LockModeType.PESSIMISTIC_WRITE)`).
  - [x] **Task 2.3**: Triển khai `OptimisticLockStrategy` (sử dụng `@Version` và retry loop).
  - [x] **Task 2.4**: Triển khai `RedisLuaLockStrategy` (Lua script nguyên tử, atomic decrement $1\text{ms}$).
  - [x] **Task 2.5**: Tạo `OrderService`, `OrderController` và `TicketController` hỗ trợ chọn dynamic strategy (`?strategy=...`).

- [x] **Phase 3: Resilience, Idempotency & Rate Limiting**
  - [x] **Task 3.1**: Triển khai `IdempotencyService` kiểm tra `X-Idempotency-Key` với state machine (`PROCESSING`, `COMPLETED`, `FAILED`).
  - [x] **Task 3.2**: Triển khai `RateLimiterService` & `RateLimitInterceptor` (Token Bucket Lua script trên Redis).
  - [x] **Task 3.3**: Triển khai `GlobalExceptionHandler` theo chuẩn RFC 7807 (Problem Details).

- [x] **Phase 4: Asynchronous Order Persistence với RabbitMQ**
  - [x] **Task 4.1**: Cấu hình RabbitMQ Topology (`TopicExchange`, queue, `DLX`, `DLQ`, Publisher Confirms).
  - [x] **Task 4.2**: Triển khai `OrderEventProducer` với fallback compensation (hoàn tồn kho Redis nếu broker lỗi).
  - [x] **Task 4.3**: Triển khai `OrderEventConsumer` với manual ACK, idempotency check và ghi nhận đơn hàng vào PostgreSQL.

- [x] **Phase 5: Concurrency Verification & Benchmark Report**
  - [x] **Task 5.1**: Viết `FlashSaleConcurrencyTest` (JUnit 5 + `ExecutorService` 100 luồng đồng thời) kiểm chứng Zero-Overselling cả 3 chiến lược.
  - [x] **Task 5.2**: Viết k6 stress test script `k6/flashsale-stress.js` (5.000 Virtual Users).
  - [x] **Task 5.3**: Tổng hợp và xuất bản `docs/BENCHMARK_REPORT.md` phục vụ hồ sơ năng lực / CV.

- [x] **Phase 6: Production Hardening — Transactional Outbox, Payment TTL Compensation & Observability**
  - [x] **Task 6.1**: Xây dựng Custom Micrometer Metrics (`FlashSaleMetrics`) và `OrderStateConflictException` (`HTTP 409 Conflict`).
  - [x] **Task 6.2**: Triển khai Transactional Outbox Pattern (`OutboxEvent`, `OutboxEventRepository`, `OutboxService`, `OutboxRelayScheduler`, `OutboxController`).
  - [x] **Task 6.3**: Cấu hình RabbitMQ Delay Queue (`x-message-ttl`) + DLX Expired Queue và `OrderExpirationService` hoàn kho kép PostgreSQL + Redis.
  - [x] **Task 6.4**: Triển khai `PaymentService` & `PaymentController` với Atomic SQL CAS Guard chống Race Condition giữa Thanh toán và Hết hạn đơn.
  - [x] **Task 6.5**: Tích hợp UI interactive Thanh toán / Hết hạn hoàn kho, cấu hình Prometheus + Grafana (`docker-compose.yml`), cập nhật tài liệu & CV.
  - [x] **Task 6.6**: Viết `OutboxAndExpirationIntegrationTest` (bao gồm concurrency race condition test 20 Virtual Threads) và xác minh toàn bộ Maven test suite pass 100%.
