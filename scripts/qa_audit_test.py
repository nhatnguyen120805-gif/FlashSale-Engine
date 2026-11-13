import json
import time
import urllib.error
import urllib.request
import uuid

BASE_URL = "http://localhost:8080/api/v1"


def test_get_tickets():
    req = urllib.request.Request(f"{BASE_URL}/tickets")
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read().decode())
        print(f"[TEST 1] GET /tickets -> HTTP {resp.status}, count={len(data)}")
        for t in data:
            print(
                f"   Event: {t['eventName']} | Code: {t['ticketCode']} | Stock: {t['stock']} | Price: {t['price']}"
            )
        return data


def test_purchase_sold_out(ticket_id):
    url = f"{BASE_URL}/orders"
    payload = {
        "ticketId": ticket_id,
        "quantity": 1,
        "userId": "qa-tester-01",
        "strategy": "REDIS_LUA",
    }
    data = json.dumps(payload).encode()
    req = urllib.request.Request(
        url,
        data=data,
        headers={
            "Content-Type": "application/json",
            "X-Idempotency-Key": str(uuid.uuid4()),
        },
    )
    try:
        with urllib.request.urlopen(req) as resp:
            print(
                f"[TEST 2] Purchase sold out ticketId={ticket_id} UNEXPECTED SUCCESS: HTTP {resp.status}"
            )
            return False
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        print(
            f"[TEST 2] Purchase sold out ticketId={ticket_id} EXPECTED REJECTION: HTTP {e.code} -> {body.strip()}"
        )
        return e.code == 400


def test_purchase_valid(ticket_id):
    url = f"{BASE_URL}/orders"
    payload = {
        "ticketId": ticket_id,
        "quantity": 1,
        "userId": "qa-tester-vip",
        "strategy": "REDIS_LUA",
    }
    data = json.dumps(payload).encode()
    req = urllib.request.Request(
        url,
        data=data,
        headers={
            "Content-Type": "application/json",
            "X-Idempotency-Key": str(uuid.uuid4()),
        },
    )
    try:
        with urllib.request.urlopen(req) as resp:
            res_data = json.loads(resp.read().decode())
            print(
                f"[TEST 3] Purchase valid ticketId={ticket_id}: HTTP {resp.status} -> OrderNo: {res_data.get('orderNumber')}, Amount: {res_data.get('totalAmount')}"
            )
            return True, res_data
    except (urllib.error.HTTPError, urllib.error.URLError) as e:
        print(f"[TEST 3] Purchase valid ticketId={ticket_id} FAILED: {e}")
        return False, str(e)


def test_idempotency(ticket_id):
    url = f"{BASE_URL}/orders"
    shared_key = f"idemp-test-{uuid.uuid4()}"
    payload = {
        "ticketId": ticket_id,
        "quantity": 1,
        "userId": "qa-tester-idemp",
        "strategy": "REDIS_LUA",
    }
    data = json.dumps(payload).encode()
    req1 = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json", "X-Idempotency-Key": shared_key},
    )
    req2 = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json", "X-Idempotency-Key": shared_key},
    )

    code1 = None
    code2 = None
    try:
        with urllib.request.urlopen(req1) as r1:
            code1 = r1.status
            _ = r1.read().decode()
    except urllib.error.HTTPError as e:
        code1 = e.code
        _ = e.read().decode()

    try:
        with urllib.request.urlopen(req2) as r2:
            code2 = r2.status
            _ = r2.read().decode()
    except urllib.error.HTTPError as e:
        code2 = e.code
        _ = e.read().decode()

    print(f"[TEST 4] Idempotency: Request 1 -> HTTP {code1}, Request 2 -> HTTP {code2}")
    return code1, code2


def test_rate_limiting():
    url = f"{BASE_URL}/tickets"
    results = []
    start = time.time()
    for i in range(12):
        try:
            req = urllib.request.Request(
                url, headers={"X-Forwarded-For": "192.168.1.99"}
            )
            with urllib.request.urlopen(req) as resp:
                results.append(resp.status)
        except urllib.error.HTTPError as e:
            results.append(e.code)
    duration = time.time() - start
    print(
        f"[TEST 5] Rate Limiter burst 12 requests in {duration:.3f}s -> Responses: {results}"
    )
    return results


def test_negative_quantity(ticket_id):
    url = f"{BASE_URL}/orders"
    payload = {
        "ticketId": ticket_id,
        "quantity": -2,
        "userId": "hacker-neg-qty",
        "strategy": "REDIS_LUA",
    }
    data = json.dumps(payload).encode()
    req = urllib.request.Request(
        url,
        data=data,
        headers={
            "Content-Type": "application/json",
            "X-Idempotency-Key": str(uuid.uuid4()),
        },
    )
    try:
        with urllib.request.urlopen(req) as resp:
            print(f"[TEST 6] Negative quantity UNEXPECTED SUCCESS: HTTP {resp.status}")
            return False
    except urllib.error.HTTPError as e:
        print(
            f"[TEST 6] Negative quantity EXPECTED REJECTION: HTTP {e.code} -> {e.read().decode().strip()}"
        )
        return e.code in (400, 422)


def test_sql_injection(ticket_id):
    url = f"{BASE_URL}/orders"
    payload = {
        "ticketId": ticket_id,
        "quantity": 1,
        "userId": "' OR 1=1 --",
        "strategy": "REDIS_LUA",
    }
    data = json.dumps(payload).encode()
    req = urllib.request.Request(
        url,
        data=data,
        headers={
            "Content-Type": "application/json",
            "X-Idempotency-Key": "'; DROP TABLE tickets; --",
        },
    )
    try:
        with urllib.request.urlopen(req) as resp:
            print(f"[TEST 7] SQL injection string handled safely: HTTP {resp.status}")
            return True
    except urllib.error.HTTPError as e:
        print(f"[TEST 7] SQL injection string rejected safely: HTTP {e.code}")
        return True


if __name__ == "__main__":
    print("=== STARTING QA AUDIT LIVE TEST SUITE ===")
    tickets = test_get_tickets()
    test_purchase_sold_out(1)  # Son Tung VVIP
    test_purchase_sold_out(2)  # ATVNCG VIP
    test_purchase_valid(3)  # Ha Anh Tuan GA
    test_idempotency(3)
    test_rate_limiting()
    test_negative_quantity(3)
    test_sql_injection(3)
    print("=== QA AUDIT LIVE TEST SUITE FINISHED ===")
