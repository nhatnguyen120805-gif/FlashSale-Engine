import concurrent.futures
import json
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE_URL = "http://localhost:8080/api/v1"


def get_tickets():
    req = urllib.request.Request(f"{BASE_URL}/tickets")
    with urllib.request.urlopen(req) as resp:
        return resp.status, json.loads(resp.read().decode())


def get_ticket_stock(ticket_id):
    req = urllib.request.Request(f"{BASE_URL}/tickets/{ticket_id}")
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read().decode())
        return data["stock"]


def post_order(
    ticket_id,
    quantity,
    user_id,
    strategy="REDIS_LUA",
    idempotency_key=None,
    client_ip="10.10.0.1",
):
    url = f"{BASE_URL}/orders"
    payload = {
        "ticketId": ticket_id,
        "quantity": quantity,
        "userId": user_id,
        "strategy": strategy,
    }
    data = json.dumps(payload).encode()
    req = urllib.request.Request(
        url,
        data=data,
        headers={
            "Content-Type": "application/json",
            "X-Idempotency-Key": idempotency_key or str(uuid.uuid4()),
            "X-Forwarded-For": client_ip,
        },
    )
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, raw


def test_get_tickets():
    status, data = get_tickets()
    print(f"[TEST 1] GET /tickets -> HTTP {status}, count={len(data)}")
    for t in data:
        print(
            f"   Event: {t['eventName']} | Code: {t['ticketCode']} | Stock: {t['stock']} | Price: {t['price']}"
        )
    assert status == 200 and len(data) >= 3
    return data


def test_deplete_and_reject_sold_out(ticket_id):
    current_stock = get_ticket_stock(ticket_id)
    idx = 0
    while current_stock > 0:
        qty = min(10, current_stock)
        code, _ = post_order(
            ticket_id,
            qty,
            f"buyer-deplete-{idx}",
            "REDIS_LUA",
            client_ip=f"10.20.0.{idx + 1}",
        )
        assert code == 201, (
            f"Expected 201 while depleting ticket {ticket_id}, got {code}"
        )
        current_stock -= qty
        idx += 1

    code, body = post_order(
        ticket_id, 1, "qa-tester-01", "REDIS_LUA", client_ip="10.20.1.1"
    )
    print(
        f"[TEST 2] Purchase sold out ticketId={ticket_id} EXPECTED REJECTION: HTTP {code} -> {body}"
    )
    assert code == 400, f"Expected 400 Sold Out, got {code}"


def test_purchase_valid_all_strategies(ticket_id):
    for i, strat in enumerate(["REDIS_LUA", "PESSIMISTIC", "OPTIMISTIC"]):
        code, body = post_order(
            ticket_id, 1, f"qa-tester-{strat}", strat, client_ip=f"10.30.0.{i + 1}"
        )
        print(
            f"[TEST 3] Purchase valid ({strat}) ticketId={ticket_id}: HTTP {code} -> OrderNo: {body.get('orderNumber')}, TotalPrice: {body.get('totalPrice')}"
        )
        assert code == 201, f"Strategy {strat} failed with HTTP {code}: {body}"


def test_idempotency(ticket_id):
    stock_before = get_ticket_stock(ticket_id)
    shared_key = f"idemp-test-{uuid.uuid4()}"

    code1, body1 = post_order(
        ticket_id,
        1,
        "qa-tester-idemp",
        "REDIS_LUA",
        idempotency_key=shared_key,
        client_ip="10.40.0.1",
    )
    code2, body2 = post_order(
        ticket_id,
        1,
        "qa-tester-idemp",
        "REDIS_LUA",
        idempotency_key=shared_key,
        client_ip="10.40.0.2",
    )
    stock_after = get_ticket_stock(ticket_id)

    print(
        f"[TEST 4] Idempotency: Req1 HTTP {code1} ({body1.get('orderNumber')}), "
        f"Req2 HTTP {code2} ({body2.get('orderNumber')}), stock {stock_before} -> {stock_after}"
    )
    assert code1 == 201 and code2 == 201, f"Expected both 201, got {code1}, {code2}"
    assert body1.get("orderNumber") == body2.get("orderNumber"), (
        "Order numbers must match on idempotent retry!"
    )
    assert stock_before - stock_after == 1, (
        f"Stock should decrement by 1 only, dropped by {stock_before - stock_after}"
    )


def test_rate_limiting(sold_out_ticket_id):
    results = []
    start = time.time()
    for i in range(25):
        code, _ = post_order(
            sold_out_ticket_id,
            1,
            f"spammer-{i}",
            "REDIS_LUA",
            client_ip="192.168.99.99",
        )
        results.append(code)
    duration = time.time() - start
    count_429 = sum(1 for c in results[20:] if c == 429)
    if count_429 > 0:
        count_allowed = sum(1 for c in results[:20] if c in (201, 400))
        print(
            f"[TEST 5] Rate Limiter burst 25 requests (prod capacity=20) in {duration:.3f}s -> Allowed={count_allowed}, HTTP 429={count_429}"
        )
        assert count_allowed == 20, f"Expected first 20 allowed, got {count_allowed}"
        assert count_429 == 5, f"Expected last 5 to return 429, got {count_429}"
        return

    # Local profile configures flashsale.rate-limit.capacity=1000; burst concurrently to prove HTTP 429
    def fire(idx):
        c, _ = post_order(
            sold_out_ticket_id,
            1,
            f"spammer-burst-{idx}",
            "REDIS_LUA",
            client_ip="192.168.99.99",
        )
        return c

    with concurrent.futures.ThreadPoolExecutor(max_workers=32) as pool:
        burst_codes = list(pool.map(fire, range(1025)))
    total_duration = time.time() - start
    all_codes = results + burst_codes
    total_429 = sum(1 for c in all_codes if c == 429)
    total_allowed = sum(1 for c in all_codes if c in (201, 400))
    print(
        f"[TEST 5] Rate Limiter burst {len(all_codes)} requests (local capacity=1000) in {total_duration:.3f}s -> Allowed(400/201)={total_allowed}, HTTP 429={total_429}"
    )
    assert total_429 >= 5, (
        f"Expected HTTP 429 after exhausting token bucket, got {total_429}"
    )


def test_negative_quantity(ticket_id):
    code, body = post_order(
        ticket_id, -2, "hacker-neg-qty", "REDIS_LUA", client_ip="10.50.0.1"
    )
    print(f"[TEST 6] Negative quantity EXPECTED REJECTION: HTTP {code} -> {body}")
    assert code == 400, f"Expected 400 Validation Error, got {code}"


def test_sql_injection(ticket_id):
    code, body = post_order(
        ticket_id,
        1,
        "' OR 1=1 --",
        "REDIS_LUA",
        idempotency_key="'; DROP TABLE tickets; --",
        client_ip="10.60.0.1",
    )
    print(f"[TEST 7] SQL injection payload safely parameterized (no 500): HTTP {code}")
    assert code != 500, f"Unexpected HTTP 500 on SQL injection test: {body}"


if __name__ == "__main__":
    print("=== STARTING QA AUDIT LIVE TEST SUITE ===")
    test_get_tickets()
    test_deplete_and_reject_sold_out(1)  # Son Tung VVIP
    test_purchase_valid_all_strategies(3)  # Ha Anh Tuan GA
    test_idempotency(3)
    test_rate_limiting(1)
    test_negative_quantity(3)
    test_sql_injection(3)
    print("=== QA AUDIT LIVE TEST SUITE PASSED 100% ===")
    sys.exit(0)
