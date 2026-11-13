import { check, sleep } from "k6";
import http from "k6/http";
import { Counter, Trend } from "k6/metrics";

// Custom Metrics
export const ordersCreated = new Counter("flashsale_orders_created");
export const ordersOutOfStock = new Counter("flashsale_orders_out_of_stock");
export const ordersRateLimited = new Counter("flashsale_orders_rate_limited");
export const ordersConflict = new Counter("flashsale_orders_conflict");
export const ordersUnexpectedError = new Counter("flashsale_orders_unexpected");
export const orderLatency = new Trend("flashsale_order_duration", true);

export const options = {
	scenarios: {
		flash_sale_spike: {
			executor: "ramping-vus",
			startVUs: 0,
			stages: [
				{ duration: "5s", target: 500 }, // Warm up
				{ duration: "10s", target: 2000 }, // Surge
				{ duration: "15s", target: 5000 }, // Peak 5000 concurrent VUs
				{ duration: "5s", target: 0 }, // Cooldown
			],
			gracefulRampDown: "5s",
		},
	},
	thresholds: {
		flashsale_order_duration: ["p(95)<100"], // 95% requests under 100ms
	},
};

const BASE_URL = __ENV.TARGET_URL || "http://localhost:8080";
const STRATEGY = __ENV.STRATEGY || "REDIS_LUA"; // REDIS_LUA | PESSIMISTIC | OPTIMISTIC
const TICKET_ID = __ENV.TICKET_ID || 1;

export default function () {
	const vuId = __VU;
	const iterId = __ITER;
	const idempotencyKey = `idemp-${vuId}-${iterId}-${Date.now()}`;

	const payload = JSON.stringify({
		ticketId: Number(TICKET_ID),
		userId: `user-vu-${vuId}`,
		quantity: 1,
		strategy: STRATEGY,
	});

	const params = {
		headers: {
			"Content-Type": "application/json",
			"X-Idempotency-Key": idempotencyKey,
		},
		timeout: "10s",
	};

	const res = http.post(`${BASE_URL}/api/v1/orders`, payload, params);
	orderLatency.add(res.timings.duration);

	if (res.status === 201) {
		ordersCreated.add(1);
	} else if (res.status === 400) {
		ordersOutOfStock.add(1);
	} else if (res.status === 429) {
		ordersRateLimited.add(1);
	} else if (res.status === 409) {
		ordersConflict.add(1);
	} else {
		ordersUnexpectedError.add(1);
	}

	check(res, {
		"Status is expected (201, 400, 409, 429)": (r) =>
			[201, 400, 409, 429].includes(r.status),
	});

	sleep(0.05); // Small jitter between rapid attempts
}
