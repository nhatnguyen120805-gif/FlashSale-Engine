# FlashSale-Engine ⚡

> **High-Concurrency Ticket & Flash-Sale Booking Engine**  
> Hệ thống Săn Vé & Flash Sale chịu tải cao với khả năng chống bán âm (Zero-Overselling Guarantee), bảo đảm Idempotency, Rate Limiting và xử lý hàng đợi bất đồng bộ.

---

## 🎯 Mục Tiêu Dự Án
Xây dựng một hệ thống backend chuẩn Enterprise bằng **Java 21** và **Spring Boot 3+**, giải quyết triệt để các bài toán hóc búa nhất trong hệ thống phân tán:
1. **Chống bán âm (Zero Overselling):** Thử nghiệm và đối chuẩn (benchmark) 3 giải pháp:
   - Database Pessimistic Locking (`SELECT ... FOR UPDATE`)
   - Optimistic Locking (`@Version` pattern)
   - **Distributed Lock** với **Redis Redisson / Lua Script** (Giải pháp tối ưu cho hàng triệu RPS).
2. **Chống gian lận & Spam click (Rate Limiting):** Triển khai thuật toán Token Bucket / Sliding Window chống bot cào vé.
3. **Bảo đảm không trừ tiền 2 lần (Idempotency):** Áp dụng Idempotency Key header cho mọi giao dịch thanh toán / đặt đơn.
4. **Xử lý bất đồng bộ (Asynchronous Queue):** Tách luồng tạo đơn và thanh toán qua **RabbitMQ**, giữ API latency $\le 20\text{ms}$.
5. **Kiểm chứng thực tế với k6:** Kịch bản kiểm thử tải giả lập 5.000 virtual users bắn đồng thời vào hệ thống, chứng minh dữ liệu tồn kho toàn vẹn 100%.

---

## 🛠️ Tech Stack Cốt Lõi
- **Ngôn ngữ:** Java (JDK 21 - sử dụng Virtual Threads / Project Loom)
- **Framework:** Spring Boot 3.3+, Spring Data JPA, Spring Security
- **Database:** PostgreSQL (Core ACID Ledger & Orders)
- **Cache & Distributed Lock:** Redis (Redisson Client / Lua Scripts)
- **Message Broker:** RabbitMQ (Order Processing & Notification)
- **Observability:** Spring Boot Actuator, Micrometer, Prometheus, Grafana
- **Performance & Testing:** k6, JUnit 5, Testcontainers, Mockito
- **DevOps:** Docker, Docker Compose

---

## 📂 Cấu Trúc Dự Án Dự Kiến
```text
FlashSale-Engine/
├── docs/                     # Kiến trúc, sơ đồ sequence diagram, báo cáo tải
│   ├── ARCHITECTURE.md       # Thiết kế chi tiết kiến trúc & giải thuật
│   └── BENCHMARK_REPORT.md   # Kết quả đo đạc k6 load test
├── k6/                       # Scripts k6 kiểm thử tải mô phỏng 5000 users
│   └── flashsale-stress.js
├── src/                      # Source code Spring Boot 3
│   ├── main/
│   │   ├── java/com/nhatnguyen/flashsale/
│   │   │   ├── common/       # Idempotency, Rate Limiting, Global Exception
│   │   │   ├── config/       # Redis, RabbitMQ, Virtual Thread configs
│   │   │   ├── modules/
│   │   │   │   ├── product/  # Quản lý sự kiện, vé & tồn kho
│   │   │   │   ├── order/    # Đặt vé, xử lý lock, trừ kho
│   │   │   │   └── payment/  # Idempotent payment, RabbitMQ consumer
│   └── test/
├── docker-compose.yml        # PostgreSQL + Redis + RabbitMQ + Prometheus
├── pom.xml
└── README.md
```

---
*Tác giả: Nguyễn Văn Nhật (Java Backend Software Engineer)*
