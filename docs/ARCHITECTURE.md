# Kiến Trúc Kỹ Thuật — FlashSale-Engine

## 1. Bài Toán Cốt Lõi: The "Overselling" Problem
Khi 5.000 người cùng bấm mua 1 vé ca nhạc trong cùng 1 giây:
* **Hiện tượng:** Nếu chỉ đọc tồn kho `SELECT stock FROM tickets WHERE id = 1` rồi `UPDATE tickets SET stock = stock - 1`, hàng nghìn luồng (threads) sẽ cùng đọc ra số tồn kho > 0 và cùng trừ. Kết quả: Kho bị âm (`stock = -4900`), doanh nghiệp mất trắng tiền đền bù.

## 2. Ba Tầng Giải Pháp Đối Chuẩn (Benchmark Strategy)

### Tầng 1: Database Pessimistic Lock
- Cú pháp: `SELECT * FROM tickets WHERE id = :id FOR UPDATE`
- **Ưu điểm:** Đơn giản, an toàn tuyệt đối ở tầng CSDL quan hệ.
- **Nhược điểm:** Lock nguyên dòng (row-level lock) trong DB, gây thắt nút cổ chai (bottleneck), latency tăng vọt khi nhiều người tranh chấp.

### Tầng 2: Optimistic Lock với `@Version`
- Cú pháp: `UPDATE tickets SET stock = stock - 1, version = version + 1 WHERE id = :id AND version = :version`
- **Ưu điểm:** Không lock DB, tốc độ đọc cực cao.
- **Nhược điểm:** Tỷ lệ xung đột (collision) cao khi flash sale; 99% requests bị fail phải retry hoặc throw exception.

### Tầng 3: Distributed In-Memory Lock (Redis + Lua Script) — *Chiến Lược Tối Thượng*
- Toàn bộ tồn kho vé được nạp sẵn vào Redis: `HSET ticket:1 stock 100`
- Khi request mua vé đến: Chạy một **Lua Script nguyên tử (Atomic)** trực tiếp trên Redis:
  ```lua
  local stock = redis.call('get', KEYS[1])
  if stock and tonumber(stock) > 0 then
      redis.call('decr', KEYS[1])
      return 1
  else
      return 0
  end
  ```
- **Thời gian xử lý:** Chỉ mất **1 – 2ms** trên bộ nhớ RAM.
- Sau khi trừ Redis thành công, bắn message vào **RabbitMQ** để ghi nhận đơn hàng xuống PostgreSQL bất đồng bộ.

---

## 3. Cơ Chế Idempotency (Chống Trừ Tiền / Tạo Đơn Trùng)
- Client sinh mã UUID ngẫu nhiên `X-Idempotency-Key` gửi kèm trong Header request.
- Backend kiểm tra khóa `idempotency:{key}` trong Redis:
  - Nếu đã tồn tại: Trả về kết quả giao dịch cũ ngay lập tức (không chạy lại logic).
  - Nếu chưa: Lưu khóa với trạng thái `PROCESSING` (TTL 120s), thực thi xong thì cập nhật kết quả và TTL 24h.

---

## 4. Rate Limiting (Chống Spam / Bot cào vé)
- Áp dụng thuật toán **Token Bucket** qua Redis:
  - Mỗi IP / User ID chỉ có tối đa 5 tokens / giây.
  - Vượt quá hạn mức ➡️ Trả về HTTP `429 Too Many Requests` ngay tại Filter tầng ngoài, không cho request chạm vào DB.
