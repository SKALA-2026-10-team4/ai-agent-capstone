#!/usr/bin/env python3
# 실행 중인 주문 Agent 데모를 HTTP 로만 확인하는 smoke 스크립트다. Python 표준 라이브러리만 쓴다.
# 기동 상태(GET /api/health)와 상품 목록을 본 뒤 정상 주문, 같은 키 재요청(멱등), 같은 키의 다른
# 입력(409), 품절 상품 거절, 남은 재고가 넉넉할 때의 결제 거절과 재고 해제를 확인하고, 성공한
# 주문만 재고를 줄였는지 본다.
# --write-state 로 주문 id 와 랜턴 재고를 저장하고, 컨테이너를 다시 만든 뒤 --check-state 로
# 그대로 남았는지 확인한다.
# 데모 DB 의 랜턴 재고를 실제로 2개 쓰므로 로컬 데모 DB 에서만 돌린다. 사용법은
# order-agent/README.md 와 order-agent/docs/verification.md 에 있다. 부르는 API 는
# order-agent/backend/src/main/java/com/capstone4/orderagent/controller/ 의 HealthController,
# ProductController, OrderController 다.
# 아래 모듈 docstring 은 argparse 의 --help 설명(description=__doc__)으로 쓰이므로
# 문구를 바꾸지 않는다.
"""HTTP smoke checks for the running demo. No service or third-party Python packages.

Creates demo orders and consumes two lanterns. Use only the local demo database.
"""
import argparse
import json
from pathlib import Path
import urllib.error
import urllib.request
import uuid

# 로컬 데모(order-agent/compose.yml)가 주문 Agent backend 를 여는 주소다. --base-url 로 바꾼다.
DEFAULT_BASE_URL = "http://127.0.0.1:8081/api"
# 재고를 소비하는 시드 상품 SKU 다. order-agent/backend/src/main/resources/data.sql 이
# 단가 30000, 재고 10 으로 넣는다.
LANTERN = "CAMP-LANTERN-LED"


def request(base, method, path, body=None, key=None):
    """주문 Agent API 에 JSON 요청 하나를 보내고 (HTTP 상태, JSON 본문)을 돌려준다.

    key 를 주면 Idempotency-Key 헤더로 싣는다. 4xx 와 5xx 도 예외 대신 상태와 오류
    본문(ErrorResponse)으로 돌려줘 호출자가 상태를 비교할 수 있게 한다. 시간 제한은 15초다.
    """
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
    """조건이 거짓이면 message 를 담은 AssertionError 로 멈춘다.

    assert 문과 달리 python -O 로 돌려도 검사가 빠지지 않는다.
    """
    if not condition:
        raise AssertionError(message)


def stock_levels(catalog):
    """상품 목록 응답(GET /api/products)을 SKU 별 재고 수량으로 바꾼다."""
    return {p["sku"]: p["stock_quantity"] for p in catalog["items"]}


def check_recreated_state(base, saved_path, stocks):
    """컨테이너 재생성 뒤 저장해 둔 주문과 랜턴 재고가 그대로인지 확인한다.

    주문은 GET /api/orders/{id} 로 다시 읽어 SHIPPED 인지 보고, 재고는 시드 SQL 이 기존 행을
    덮지 않았는지 본다. --check-state 를 주면 main 이 주문 시나리오 대신 이 함수만 부른다.
    """
    saved = json.loads(saved_path.read_text())
    status, order = request(base, "GET", "/orders/" + saved["order_id"])
    check(status == 200 and order["status"] == "SHIPPED", "Order survives container recreation")
    check(stocks[LANTERN] == saved["lantern_stock"], "Stock survives recreation; seed does not reset it")
    print("PASS: order and stock preserved after container recreation")


def run_live_checks(base, stocks, write_state):
    """정상, 멱등, 충돌, 품절, 결제 거절 주문을 보내 업무 결과와 재고 일관성을 확인한다.

    정상 주문(랜턴 2개)은 201, SHIPPED, 합계 60000, 처리 단계 5개여야 하고, 같은 키 재요청은
    200 으로 같은 주문을, 같은 키의 다른 입력은 409 를 돌려줘야 한다. 품절 상품(CAMP-MUG-STEEL,
    시드 재고 0)은 REJECTED 이고 결제와 배송이 일어나지 않아야 한다. 마지막에 랜턴 재고가 정상
    주문의 2개만 줄었는지 보고, write_state 가 있으면 주문 id 와 남은 랜턴 재고를 저장한다.
    """
    check(stocks[LANTERN] >= 2, "At least two lanterns required; demo stock has been consumed")
    body = {"customer": "c_1001", "items": [{"sku": LANTERN, "qty": 2}],
            "payment_method": "card", "shipping": {"country": "KR", "express": False}}
    key = str(uuid.uuid4())
    status, order = request(base, "POST", "/orders", body, key)
    check(status == 201 and order["status"] == "SHIPPED", "Normal order")
    check(order["total_amount"] == 60000 and len(order["steps"]) == 5, "Amount and actual processing steps")
    status, duplicate = request(base, "POST", "/orders", body, key)
    check(status == 200 and duplicate["order_id"] == order["order_id"], "Idempotent repeat")
    changed = {**body, "items": [{"sku": LANTERN, "qty": 1}]}
    status, _ = request(base, "POST", "/orders", changed, key)
    check(status == 409, "Changed input with same key")
    soldout = {**body, "items": [{"sku": "CAMP-MUG-STEEL", "qty": 1}]}
    status, result = request(base, "POST", "/orders", soldout, str(uuid.uuid4()))
    check(status == 201 and result["status"] == "REJECTED", "Stock rejection")
    check(result["shipment"] is None and result["payment"]["status"] == "NOT_ATTEMPTED", "No downstream work")
    # 결제 거절 주문(c_declined, card, 랜턴 1개)은 재고 1개를 예약했다가 되돌린다. 정상 주문이
    # 2개를 쓴 뒤에도 1개 이상 남을 때만 확인한다.
    if stocks[LANTERN] > 2:
        declined = {**changed, "customer": "c_declined"}
        status, result = request(base, "POST", "/orders", declined, str(uuid.uuid4()))
        check(status == 201 and result["status"] == "HOLD", "Payment decline")
        check(any(s["step"] == "RELEASE" and s["status"] == "SUCCESS" for s in result["steps"]), "Inventory release")
    _, catalog = request(base, "GET", "/products")
    after = stock_levels(catalog)
    check(after[LANTERN] == stocks[LANTERN] - 2, "Only successful order consumes stock")
    if write_state:
        write_state.write_text(json.dumps({"order_id": order["order_id"], "lantern_stock": after[LANTERN]}))
    print("PASS: normal, out-of-stock, idempotency, conflict, actual steps and stock consistency")
    print("Payment decline checked when remaining lantern stock permits it")


def main(argv=None):
    """명령행 인자를 읽고 기동 상태와 상품 API 를 확인한 뒤 확인할 시나리오를 고른다.

    --check-state 면 재생성 뒤 보존만, 아니면 주문 시나리오를 확인한다. 기동 확인은
    GET /api/health 가 200 이고 service 가 order-agent 인지로 다른 서버에 붙지 않았는지까지 본다.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--write-state", type=Path)
    parser.add_argument("--check-state", type=Path)
    args = parser.parse_args(argv)
    base = args.base_url.rstrip("/")
    status, health = request(base, "GET", "/health")
    check(status == 200 and health["service"] == "order-agent", "Backend readiness")
    status, catalog = request(base, "GET", "/products")
    check(status == 200, "Product API")
    stocks = stock_levels(catalog)
    if args.check_state:
        check_recreated_state(base, args.check_state, stocks)
        return
    run_live_checks(base, stocks, args.write_state)


if __name__ == "__main__":
    main()
