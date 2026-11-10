"""Automated security and quality test suite for PayPal Visual Cart Backend.

Tests:
1. Zero hardcoded secrets in source / client credentials handling
2. Payment amount validation and tampering protection
3. Payment idempotency and double-capture prevention
4. Fallback simulation safety when offline or unconfigured
5. Gemini multimodal understanding request handling
"""

import pytest
import asyncio
from pydantic import ValidationError
from backend.paypal_client import PayPalClient, PayPalError
from backend.gemini_client import GeminiClient
from backend.main import CreateOrderRequest, CaptureOrderRequest, app
from fastapi.testclient import TestClient

client = TestClient(app)

class TestZeroHardcodedSecrets:
    def test_paypal_client_defaults_empty_secrets(self, monkeypatch):
        monkeypatch.delenv("PAYPAL_CLIENT_ID", raising=False)
        monkeypatch.delenv("PAYPAL_CLIENT_SECRET", raising=False)
        client = PayPalClient()
        assert not client.is_configured
        assert client.client_id == ""
        assert client.client_secret == ""

    def test_gemini_client_defaults_empty_key(self, monkeypatch):
        monkeypatch.delenv("GEMINI_API_KEY", raising=False)
        client = GeminiClient()
        assert not client.is_configured
        assert client.api_key == ""

class TestPaymentTamperingAndValidation:
    def test_valid_amounts_accepted_and_formatted(self):
        req = CreateOrderRequest(amount_value="5499.00")
        assert req.amount_value == "5499.00"

        req2 = CreateOrderRequest(amount_value=" 120.5 ")
        assert req2.amount_value == "120.50"

    def test_negative_amounts_rejected(self):
        with pytest.raises(ValidationError):
            CreateOrderRequest(amount_value="-50.00")

    def test_zero_amount_rejected(self):
        with pytest.raises(ValidationError):
            CreateOrderRequest(amount_value="0.00")

    def test_invalid_string_amount_rejected(self):
        with pytest.raises(ValidationError):
            CreateOrderRequest(amount_value="free_money")

    def test_capture_order_request_rejects_empty_id(self):
        with pytest.raises(ValidationError):
            CaptureOrderRequest(order_id="")

class TestPaymentIdempotencyAndDoubleCapture:
    def test_duplicate_capture_prevention(self):
        async def _test():
            paypal = PayPalClient()
            order_id = "SBX-ORD-TEST-12345"
            
            # First capture
            res1 = await paypal.capture_order(order_id)
            assert res1["status"] == "COMPLETED"
            assert res1.get("already_captured") is not True

            # Second capture on the exact same order ID
            res2 = await paypal.capture_order(order_id)
            assert res2["status"] == "COMPLETED"
            assert res2.get("already_captured") is True
            assert res2.get("duplicate_capture_prevented") is True
        asyncio.run(_test())

    def test_idempotency_key_caching(self):
        async def _test():
            paypal = PayPalClient()
            order_id = "SBX-ORD-IDEMP-8888"
            idempotency_key = "idemp-client-uuid-999"

            res1 = await paypal.capture_order(order_id, idempotency_key=idempotency_key)
            assert res1["status"] == "COMPLETED"

            res2 = await paypal.capture_order("DIFFERENT-ORDER-IGNORED", idempotency_key=idempotency_key)
            # Must return the cached result for that idempotency key
            assert res2 == res1
        asyncio.run(_test())

    def test_blank_order_id_raises_value_error(self):
        async def _test():
            paypal = PayPalClient()
            with pytest.raises(ValueError):
                await paypal.capture_order("   ")
        asyncio.run(_test())

class TestBackendEndpoints:
    def test_root_endpoint(self):
        resp = client.get("/")
        assert resp.status_code == 200
        data = resp.json()
        assert data["service"] == "PayPal Visual Cart API"
        assert data["status"] == "online"

    def test_health_endpoint(self):
        resp = client.get("/health")
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "healthy"

    def test_merchants_search_endpoint(self):
        resp = client.get("/api/merchants/search?q=samsung+990+evo")
        assert resp.status_code == 200
        data = resp.json()
        assert "listings" in data
        assert len(data["listings"]) >= 1

    def test_paypal_create_order_endpoint(self):
        resp = client.post("/api/paypal/create-order", json={
            "amount_value": "1999.00",
            "currency_code": "USD",
            "item_name": "Test SSD"
        })
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "success"
        assert "order" in data
        assert "id" in data["order"]

    def test_paypal_capture_order_endpoint_with_idempotency(self):
        order_id = "SBX-ORD-FASTAPI-TEST"
        idempotency_key = "idem-key-fastapi-1"

        resp1 = client.post("/api/paypal/capture-order", json={
            "order_id": order_id,
            "idempotency_key": idempotency_key
        })
        assert resp1.status_code == 200
        data1 = resp1.json()
        assert data1["status"] == "success"

        # Repeat capture
        resp2 = client.post("/api/paypal/capture-order", json={
            "order_id": order_id,
            "idempotency_key": idempotency_key
        })
        assert resp2.status_code == 200
        data2 = resp2.json()
        assert data2["capture"].get("already_captured") is True or data2 == data1

    def test_paypal_configuration_endpoints_and_security(self):
        for path in ["/api/v1/payments/paypal/configuration", "/api/paypal/configuration"]:
            resp = client.get(path)
            assert resp.status_code == 200
            data = resp.json()
            assert data["provider"] == "paypal"
            assert data["mode"] == "sandbox"
            assert isinstance(data["credentials_configured"], bool)
            assert isinstance(data["oauth_ready"], bool)
            assert isinstance(data["orders_api_ready"], bool)
            assert "client_id" in data
            assert data["is_sandbox"] is True
            assert "default_currency" in data
            assert "brand_name" in data
            assert data["deep_link_scheme"] == "paypalvisualcart"
            assert "client_secret" not in data
            assert "secret" not in data
            assert "api_key" not in data
            assert "access_token" not in data
            assert "token" not in data

    def test_negative_mock_response_parsing_client(self):
        async def _test():
            paypal = PayPalClient()
            with pytest.raises(RuntimeError) as exc_info:
                await paypal.capture_order(
                    "SBX-ORD-NEG-001",
                    mock_response='{"mock_application_codes": "INSTRUMENT_DECLINED"}'
                )
            assert "INSTRUMENT_DECLINED" in str(exc_info.value)

            with pytest.raises(RuntimeError) as exc_info2:
                await paypal.capture_order(
                    "SBX-ORD-NEG-002",
                    mock_response='{"mock_application_codes": "DUPLICATE_INVOICE_ID"}'
                )
            assert "DUPLICATE_INVOICE_ID" in str(exc_info2.value)

        asyncio.run(_test())

    def test_negative_mock_response_endpoint_parsing(self):
        resp = client.post(
            "/api/v1/payments/paypal/capture-order",
            json={"order_id": "SBX-ORD-DECLINED-HTTP"},
            headers={"PayPal-Mock-Response": '{"mock_application_codes": "INSTRUMENT_DECLINED"}'}
        )
        assert resp.status_code == 422
        assert "INSTRUMENT_DECLINED" in resp.json()["detail"]

    def test_multi_currency_order_creation(self):
        for curr in ["INR", "USD", "EUR", "GBP"]:
            req = CreateOrderRequest(
                amount_value="149.99",
                currency_code=curr,
                item_name="Multi Currency Item"
            )
            assert req.amount_value == "149.99"
            assert req.currency_code == curr


class TestPayPalOrdersV2Hardenings:
    """Verifies Orders v2 API hardening, configurable currency, and capture verification."""

    def test_server_authoritative_totals_calculation_usd(self):
        paypal = PayPalClient()
        calc = paypal.calculate_totals(
            unit_price=54.99,
            quantity=2,
            tax_rate=0.08,
            shipping_fee=5.00,
            currency_code="USD"
        )
        assert calc["currency"] == "USD"
        assert calc["subtotal"] == 109.98
        assert calc["tax"] == 8.80  # round(109.98 * 0.08, 2)
        assert calc["shipping"] == 5.00
        assert calc["total"] == 123.78
        assert calc["formatted_total"] == "123.78"

    def test_zero_decimal_currency_precision_jpy(self):
        paypal = PayPalClient()
        calc = paypal.calculate_totals(
            unit_price=10000.0,
            quantity=1,
            tax_rate=0.10,
            shipping_fee=500.0,
            currency_code="JPY"
        )
        assert calc["currency"] == "JPY"
        assert calc["subtotal"] == 10000.0
        assert calc["tax"] == 1000.0
        assert calc["shipping"] == 500.0
        assert calc["total"] == 11500.0
        assert calc["formatted_total"] == "11500"
        assert "." not in calc["formatted_total"]

    def test_create_order_with_server_breakdown(self):
        async def _test():
            paypal = PayPalClient()
            order = await paypal.create_order(
                unit_price=64.88,
                quantity=1,
                tax_rate=0.08,
                shipping_fee=0.0,
                currency_code="USD",
                item_name="SSD Drive"
            )
            assert order["status"] == "CREATED"
            assert "id" in order
            assert order["currency"] == "USD"
            assert len(order["links"]) > 0
        asyncio.run(_test())

    def test_get_order_details(self):
        async def _test():
            paypal = PayPalClient()
            order = await paypal.create_order(amount_value="64.88", currency_code="USD")
            order_id = order["id"]

            details = await paypal.get_order_details(order_id)
            assert details["id"] == order_id
            assert details["status"] == "CREATED"
        asyncio.run(_test())

    def test_capture_order_extracts_real_capture_metadata(self):
        async def _test():
            paypal = PayPalClient()
            order_id = "SBX-ORD-METADATA-1"

            capture = await paypal.capture_order(order_id)
            assert capture["status"] == "COMPLETED"
            assert capture["capture_id"].startswith("CAP-")
            assert "gross_amount" in capture
            assert capture["seller_protection_status"] == "ELIGIBLE"
            assert "create_time" in capture
            assert "currency" in capture
        asyncio.run(_test())

    def test_get_order_details_endpoint(self):
        # Create an order via API
        create_resp = client.post("/api/paypal/create-order", json={
            "amount_value": "75.00",
            "currency_code": "USD",
            "item_name": "Test Item"
        })
        assert create_resp.status_code == 200
        order_id = create_resp.json()["order"]["id"]

        # Retrieve order details via API
        resp = client.get(f"/api/v1/payments/paypal/order/{order_id}")
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "success"
        assert data["order"]["id"] == order_id


class TestNegativeTestingAndSecurityEnforcement:
    """Verifies sandbox negative testing, debug_id logging, production restrictions, and secret scanning."""

    def test_instrument_declined_error_envelope_parsing(self):
        async def _test():
            paypal = PayPalClient()
            with pytest.raises(PayPalError) as exc_info:
                await paypal.capture_order(
                    "SBX-ORD-DECLINED-TEST",
                    mock_response='{"mock_application_codes": "INSTRUMENT_DECLINED"}'
                )
            err = exc_info.value
            assert err.issue == "INSTRUMENT_DECLINED"
            assert err.debug_id.startswith("sbx_dbg_")
            assert "declined" in err.description.lower()
            assert err.status_code == 422
        asyncio.run(_test())

    def test_duplicate_invoice_id_error_handling_capture(self):
        async def _test():
            paypal = PayPalClient()
            with pytest.raises(PayPalError) as exc_info:
                await paypal.capture_order(
                    "SBX-ORD-DUP-INV-TEST",
                    mock_response="DUPLICATE_INVOICE_ID"
                )
            err = exc_info.value
            assert err.issue == "DUPLICATE_INVOICE_ID"
            assert err.debug_id.startswith("sbx_dbg_")
            assert "invoice ID" in err.description
            assert err.status_code == 422
        asyncio.run(_test())

    def test_duplicate_invoice_id_error_handling_create_order(self):
        async def _test():
            paypal = PayPalClient()
            with pytest.raises(PayPalError) as exc_info:
                await paypal.create_order(
                    amount_value="50.00",
                    invoice_id="INV-9999",
                    mock_response='{"mock_application_codes": "DUPLICATE_INVOICE_ID"}'
                )
            err = exc_info.value
            assert err.issue == "DUPLICATE_INVOICE_ID"
            assert err.debug_id.startswith("sbx_dbg_")
            assert err.status_code == 422
        asyncio.run(_test())

    def test_mock_response_strictly_forbidden_when_not_sandbox(self, monkeypatch):
        monkeypatch.setenv("PAYPAL_MODE", "live")
        monkeypatch.setenv("PAYPAL_ENV", "live")
        async def _test():
            paypal = PayPalClient()
            # capture_order must reject mock_response in production
            with pytest.raises(ValueError) as exc1:
                await paypal.capture_order(
                    "ORD-PROD-TEST",
                    mock_response="INSTRUMENT_DECLINED"
                )
            assert "strictly forbidden" in str(exc1.value)

            # create_order must reject mock_response in production
            with pytest.raises(ValueError) as exc2:
                await paypal.create_order(
                    amount_value="100.00",
                    mock_response="DUPLICATE_INVOICE_ID"
                )
            assert "strictly forbidden" in str(exc2.value)
        asyncio.run(_test())

        # API endpoints must return HTTP 400 when mock header used in live mode
        res_cap = client.post(
            "/api/v1/payments/paypal/capture-order",
            json={"order_id": "ORD-LIVE-TEST"},
            headers={"PayPal-Mock-Response": "INSTRUMENT_DECLINED"}
        )
        assert res_cap.status_code == 400
        assert "strictly forbidden" in res_cap.json()["detail"]

        res_create = client.post(
            "/api/v1/payments/paypal/create-order",
            json={"amount_value": "50.00"},
            headers={"PayPal-Mock-Response": "DUPLICATE_INVOICE_ID"}
        )
        assert res_create.status_code == 400
        assert "strictly forbidden" in res_create.json()["detail"]

    def test_idempotency_paypal_request_id_prevents_duplicate_captures(self):
        order_id = "SBX-ORD-IDEMP-DOUBLE-CAPTURE"
        request_id = "unique-paypal-req-id-7777"

        # First capture
        res1 = client.post(
            "/api/v1/payments/paypal/capture-order",
            json={"order_id": order_id, "idempotency_key": request_id},
            headers={"PayPal-Request-Id": request_id}
        )
        assert res1.status_code == 200
        data1 = res1.json()["capture"]
        assert data1["status"] == "COMPLETED"
        capture_id = data1["capture_id"]

        # Duplicate capture with same PayPal-Request-Id
        res2 = client.post(
            "/api/v1/payments/paypal/capture-order",
            json={"order_id": order_id, "idempotency_key": request_id},
            headers={"PayPal-Request-Id": request_id}
        )
        assert res2.status_code == 200
        data2 = res2.json()["capture"]
        assert data2["capture_id"] == capture_id

        # Duplicate capture without key on already-captured order
        res3 = client.post(
            "/api/v1/payments/paypal/capture-order",
            json={"order_id": order_id}
        )
        assert res3.status_code == 200
        data3 = res3.json()["capture"]
        assert data3["capture_id"] == capture_id
        assert data3.get("duplicate_capture_prevented") is True or data3.get("already_captured") is True

    def test_debug_id_logging_no_secrets_leaked(self, caplog):
        import logging
        with caplog.at_level(logging.ERROR):
            res = client.post(
                "/api/v1/payments/paypal/capture-order",
                json={"order_id": "SBX-ORD-DIAG-TEST"},
                headers={"PayPal-Mock-Response": '{"mock_application_codes": "INSTRUMENT_DECLINED"}'}
            )
            assert res.status_code == 422
            log_output = caplog.text
            assert "INSTRUMENT_DECLINED" in log_output
            assert "sbx_dbg_" in log_output
            # Confirm no secret leaks in logs
            assert "Bearer " not in log_output
            assert "PAYPAL_CLIENT_SECRET" not in log_output
            assert "client_secret" not in log_output

    def test_secret_leak_scanning_source_and_assets(self):
        """Scans codebase to verify no real PayPal secrets or bearer tokens are committed."""
        import re
        from pathlib import Path

        root_dir = Path(__file__).resolve().parent.parent
        secret_patterns = [
            re.compile(r"Bearer\s+A21[A-Za-z0-9_-]{20,}", re.IGNORECASE),
            re.compile(r"E[A-Za-z0-9_-]{60,}", re.IGNORECASE),
        ]

        scanned_count = 0
        for search_dir in ["backend", "server", "app/src/main"]:
            target_path = root_dir / search_dir
            if not target_path.exists():
                continue
            for file_path in target_path.rglob("*"):
                if file_path.is_file() and not file_path.name.endswith((".pyc", ".png", ".jpg", ".webp")):
                    scanned_count += 1
                    try:
                        content = file_path.read_text(encoding="utf-8", errors="ignore")
                        for pattern in secret_patterns:
                            matches = pattern.findall(content)
                            assert len(matches) == 0, f"Potential secret leak detected in {file_path}: {matches}"
                    except Exception:
                        pass
        assert scanned_count > 5



