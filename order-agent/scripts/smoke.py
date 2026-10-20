#!/usr/bin/env python3
"""HTTP smoke checks for the running demo. No service or third-party Python packages.

Creates demo orders and consumes two lanterns. Use only the local demo database.
"""
import argparse
import json
from pathlib import Path
import urllib.error
import urllib.request
import uuid


def request(base, method, path, body=None, key=None):
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Idempotency-Key"] = key
    payload = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=payload, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8081/api")
    parser.add_argument("--write-state", type=Path)
    parser.add_argument("--check-state", type=Path)
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    status, health = request(base, "GET", "/health")
    check(status == 200 and health["service"] == "order-agent", "Backend readiness")
    status, catalog = request(base, "GET", "/products")
    check(status == 200, "Product API")
    stocks = {p["sku"]: p["stock_quantity"] for p in catalog["items"]}
    lantern = "CAMP-LANTERN-LED"
    if args.check_state:
        saved = json.loads(args.check_state.read_text())
        status, order = request(base, "GET", "/orders/" + saved["order_id"])
        check(status == 200 and order["status"] == "SHIPPED", "Order survives container recreation")
        check(stocks[lantern] == saved["lantern_stock"], "Stock survives recreation; seed does not reset it")
        print("PASS: order and stock preserved after container recreation")
        return
    check(stocks[lantern] >= 2, "At least two lanterns required; demo stock has been consumed")
    body = {"customer": "c_1001", "items": [{"sku": lantern, "qty": 2}],
            "payment_method": "card", "shipping": {"country": "KR", "express": False}}
    key = str(uuid.uuid4())
    status, order = request(base, "POST", "/orders", body, key)
    check(status == 201 and order["status"] == "SHIPPED", "Normal order")
    check(order["total_amount"] == 60000 and len(order["steps"]) == 5, "Amount and actual processing steps")
    status, duplicate = request(base, "POST", "/orders", body, key)
    check(status == 200 and duplicate["order_id"] == order["order_id"], "Idempotent repeat")
    changed = {**body, "items": [{"sku": lantern, "qty": 1}]}
    status, _ = request(base, "POST", "/orders", changed, key)
    check(status == 409, "Changed input with same key")
    soldout = {**body, "items": [{"sku": "CAMP-MUG-STEEL", "qty": 1}]}
    status, result = request(base, "POST", "/orders", soldout, str(uuid.uuid4()))
    check(status == 201 and result["status"] == "REJECTED", "Stock rejection")
    check(result["shipment"] is None and result["payment"]["status"] == "NOT_ATTEMPTED", "No downstream work")
    # Prepaid needs no stock beyond the remaining stock; decline reserves/releases one unit.
    if stocks[lantern] > 2:
        declined = {**changed, "customer": "c_declined"}
        status, result = request(base, "POST", "/orders", declined, str(uuid.uuid4()))
        check(status == 201 and result["status"] == "HOLD", "Payment decline")
        check(any(s["step"] == "RELEASE" and s["status"] == "SUCCESS" for s in result["steps"]), "Inventory release")
    _, catalog = request(base, "GET", "/products")
    after = {p["sku"]: p["stock_quantity"] for p in catalog["items"]}
    check(after[lantern] == stocks[lantern] - 2, "Only successful order consumes stock")
    if args.write_state:
        args.write_state.write_text(json.dumps({"order_id": order["order_id"], "lantern_stock": after[lantern]}))
    print("PASS: normal, out-of-stock, idempotency, conflict, actual steps and stock consistency")
    print("Payment decline checked when remaining lantern stock permits it")


if __name__ == "__main__":
    main()
