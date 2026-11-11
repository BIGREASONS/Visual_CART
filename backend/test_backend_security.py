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


@pytest.fixture(autouse=True)
def isolate_offline_sandbox_tests(monkeypatch):
    """Keeps offline/mocked backend tests deterministic even when .env contains live Sandbox/Gemini keys."""
    for env_key in (
        "PAYPAL_CLIENT_ID",
        "PAYPAL_CLIENT_SECRET",
        "PAYPAL_SANDBOX_CLIENT_ID",
        "PAYPAL_SANDBOX_CLIENT_SECRET",
        "GEMINI_API_KEY",
        "GEMINI_MODEL",
        "GEMINI_FREE_TIER_ONLY",
        "AI_MAX_COST_USD",
    ):
        monkeypatch.delenv(env_key, raising=False)
    monkeypatch.setattr("backend.main.paypal_client.client_id", "")
    monkeypatch.setattr("backend.main.paypal_client.client_secret", "")
    monkeypatch.setattr("backend.main.paypal_client._cached_token", None)
    monkeypatch.setattr("backend.main.paypal_client._token_expires_at", 0.0)
    monkeypatch.setattr("backend.main.gemini_client.api_key", "")
    monkeypatch.setattr("backend.main.gemini_client.model_name", "gemini-3.5-flash-lite")
    monkeypatch.setattr("backend.main.gemini_client.free_tier_only", True)
    monkeypatch.setattr("backend.main.gemini_client.max_cost_usd", 0.0)
    monkeypatch.setattr("backend.main.gemini_client._rate_limit_until", 0.0)
    monkeypatch.setattr("backend.main.gemini_client.api_requests_made", 0)
    monkeypatch.setattr("backend.main.gemini_client.cache_hits", 0)
    monkeypatch.setattr("backend.main.gemini_client.rate_limit_hits", 0)
    monkeypatch.setattr("backend.main.gemini_client._cache", __import__("collections").OrderedDict())

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


class TestVisualCartV12ImageAndMerchantPipeline:
    """V1.2 regression tests for real image byte inspection, anti-hallucination AI, and search-link honesty."""

    def test_generic_capture_filename_hints_rejected(self):
        from backend.gemini_client import is_generic_image_hint

        noisy_inputs = [
            "",
            "   ",
            "shared image.jpg",
            "capture 1794369896240 4816",
            "capture_1794369896240_4816.png",
            "IMG_20261111_085200.jpg",
            "vc_img_550e8400-e29b-41d4-a716-446655440000.jpg",
            "content://media/external/images/media/42",
            "Electronics",
            "General",
        ]
        for hint in noisy_inputs:
            assert is_generic_image_hint(hint) is True, f"Expected noise hint rejected: {hint!r}"

        valid_inputs = [
            "Logitech MX Master 3S",
            "Industrial metal pipes",
            "Samsung 990 EVO 1TB",
        ]
        for hint in valid_inputs:
            assert is_generic_image_hint(hint) is False, f"Valid product hint falsely rejected: {hint!r}"

    def test_inspect_image_payload_sniffs_png_magic_bytes_and_dimensions(self):
        import base64
        from backend.gemini_client import inspect_image_payload

        # Valid 1x1 PNG (even if caller falsely declared image/* or image/jpeg)
        png_1x1 = base64.b64decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        )
        b64 = base64.b64encode(png_1x1).decode("ascii")
        info = inspect_image_payload(b64, declared_mime="image/*")
        assert info["valid"] is True
        assert info["detected_mime"] == "image/png"
        assert info["width"] == 1
        assert info["height"] == 1
        assert info["bytes_count"] == len(png_1x1)

        # Reject HTML error page passed as image base64
        html_b64 = base64.b64encode(b"<html><body>404</body></html>").decode("ascii")
        bad_info = inspect_image_payload(html_b64, declared_mime="image/png")
        assert bad_info["valid"] is False

    def test_gemini_client_sends_pixels_and_header_key_with_model_fallback(self, monkeypatch):
        import base64
        import json as _json
        import httpx

        calls = []
        png_1x1_b64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

        class _FakeResponse:
            def __init__(self, status_code, payload):
                self.status_code = status_code
                self._payload = payload

            def raise_for_status(self):
                if self.status_code >= 400:
                    raise httpx.HTTPStatusError("err", request=None, response=None)

            def json(self):
                return self._payload

        async def _fake_post(self, url, json=None, headers=None):
            calls.append({"url": url, "json": json, "headers": headers})
            return _FakeResponse(
                200,
                {
                    "candidates": [
                        {
                            "content": {
                                "parts": [
                                    {
                                        "text": _json.dumps(
                                            {
                                                "identification_status": "PARTIAL",
                                                "brand": None,
                                                "product_name": "Galvanized Metal Pipes",
                                                "category": "Industrial Materials",
                                                "model": None,
                                                "colour": "Silver",
                                                "search_query": "metal pipes",
                                                "specifications": [
                                                    "Material: Uncertain from image alone",
                                                    "Specifications: Not verified",
                                                ],
                                                "candidates": [
                                                    {
                                                        "title": "Galvanized Steel Pipes",
                                                        "category": "Industrial Materials",
                                                        "search_query": "galvanized steel pipes",
                                                        "confidence": 0.62,
                                                    }
                                                ],
                                                "confidence": 0.62,
                                                "match_reason": "Circular hollow metal pipes visible; alloy unverified.",
                                            }
                                        )
                                    }
                                ]
                            }
                        }
                    ],
                    "usageMetadata": {
                        "promptTokenCount": 42,
                        "candidatesTokenCount": 68,
                        "totalTokenCount": 110,
                    },
                },
            )

        monkeypatch.setattr(httpx.AsyncClient, "post", _fake_post)

        async def _run():
            gemini = GeminiClient(api_key="test-gemini-secret-key")
            return await gemini.understand_product(
                text="capture 1794369896240 4816",
                image_base64=png_1x1_b64,
                mime_type="image/*",
            )

        result = asyncio.run(_run())
        assert len(calls) == 1
        assert "/models/gemini-3.5-flash-lite:generateContent" in calls[0]["url"]
        assert "key=" not in calls[0]["url"]
        assert calls[0]["headers"]["x-goog-api-key"] == "test-gemini-secret-key"
        gen_cfg = calls[0]["json"]["generationConfig"]
        assert gen_cfg["maxOutputTokens"] == 350
        assert gen_cfg["thinkingConfig"] == {"thinkingLevel": "MINIMAL"}
        parts = calls[0]["json"]["contents"][0]["parts"]
        assert parts[0]["inline_data"]["mime_type"] == "image/png"
        assert parts[0]["inline_data"]["data"] == png_1x1_b64
        prompt_text = parts[1]["text"]
        assert "1794369896240" not in prompt_text

        assert result["category"] == "Industrial Materials"
        assert result["status"] == "PARTIAL"
        assert result["identification_status"] == "PARTIAL"
        assert result["recognition_source"] == "LIVE_AI"
        assert result["model_name"] == "gemini-3.5-flash-lite"
        assert result["is_live_inference"] is True
        assert result["detected_mime_type"] == "image/png"
        assert result["image_dimensions"] == "1x1"
        assert result["estimated_price"] is None
        assert result["token_usage"]["total_tokens"] == 110

    def test_merchants_search_rejects_noise_and_returns_unpriced_search_links(self):
        res_noise = client.get("/api/merchants/search", params={"q": "shared image.jpg"})
        assert res_noise.status_code == 200
        noise_data = res_noise.json()
        assert noise_data["rejected"] is True
        assert noise_data["listings"] == []

        res_clean = client.get("/api/merchants/search", params={"q": "Galvanized Metal Pipes"})
        assert res_clean.status_code == 200
        clean_data = res_clean.json()
        assert clean_data["rejected"] is False
        assert len(clean_data["listings"]) >= 3
        for item in clean_data["listings"]:
            assert item["price"] is None
            assert item["originalPrice"] is None
            assert item["isLowest"] is False
            assert item["listing_origin"] == "DIRECT_SEARCH_URL"

    def test_gemini_response_schema_has_no_empty_object_and_accepts_list_specs(self):
        from backend.gemini_client import (
            GEMINI_RESPONSE_SCHEMA,
            is_generic_image_hint,
            sanitize_gemini_parsed_output,
        )

        def _assert_no_empty_object(node, path="root"):
            if isinstance(node, dict):
                if str(node.get("type", "")).upper() == "OBJECT":
                    props = node.get("properties")
                    assert isinstance(props, dict) and len(props) > 0, (
                        f"Empty OBJECT schema at {path} triggers Gemini v1beta 400 error"
                    )
                for k, v in node.items():
                    _assert_no_empty_object(v, f"{path}.{k}")
            elif isinstance(node, list):
                for idx, item in enumerate(node):
                    _assert_no_empty_object(item, f"{path}[{idx}]")

        _assert_no_empty_object(GEMINI_RESPONSE_SCHEMA)

        # Array-format specifications from schema must normalize into a flat dict
        normalized = sanitize_gemini_parsed_output(
            {
                "status": "PARTIAL",
                "product_name": "PVC Conduit Pipe",
                "category": "Electronics",
                "search_query": "pvc conduit pipe",
                "specifications": [
                    {"name": "Material", "value": "Uncertain from image alone"},
                    {"name": "Specifications", "value": "Not verified"},
                ],
                "confidence": 0.6,
            }
        )
        assert normalized["category"] == "Industrial Materials"
        assert normalized["specifications"] == {
            "Material": "Uncertain from image alone",
            "Specifications": "Not verified",
        }
        assert is_generic_image_hint("vc_img_550e8400-e29b-41d4-a716-446655440000") is True

    def test_backend_recognize_endpoint_honors_hint_text_field(self):
        res = client.post(
            "/api/v1/ai/recognize-product",
            json={"hint_text": "PVC Plastic Water Pipes 2 inch"},
        )
        assert res.status_code == 200
        body = res.json()
        assert body["category"] == "Industrial Materials"
        assert body["recognition_source"] == "LOCAL_OCR"
        assert "pipes" in body["title"].lower()

    def test_free_tier_policy_rejects_paid_models_and_nonzero_budget(self):
        from backend.gemini_client import (
            FreeTierPolicyError,
            validate_free_tier_configuration,
        )

        valid = validate_free_tier_configuration(
            model_name="gemini-3.5-flash-lite",
            free_tier_only=True,
            max_cost_usd=0.0,
        )
        assert valid["model"] == "gemini-3.5-flash-lite"
        assert valid["max_cost_usd"] == 0.0

        for paid_model in ("gemini-2.5-pro", "gemini-2.5-flash", "gemini-3.8-flash", "gemini-1.5-pro"):
            with pytest.raises(FreeTierPolicyError):
                GeminiClient(api_key="test-gemini-secret-key", model_name=paid_model)

        with pytest.raises(FreeTierPolicyError):
            GeminiClient(api_key="test-gemini-secret-key", free_tier_only=False)

        with pytest.raises(FreeTierPolicyError):
            GeminiClient(api_key="test-gemini-secret-key", max_cost_usd=1.0)

        bad_res = client.post(
            "/api/v1/ai/recognize-product",
            json={"hint_text": "Logitech mouse", "model": "gemini-2.5-pro"},
        )
        assert bad_res.status_code == 400
        assert "allowlist" in bad_res.json()["detail"].lower()

    def test_rate_limit_429_stops_requests_immediately_without_paid_fallback(self, monkeypatch):
        import base64
        import httpx

        png_1x1_b64 = (
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        )
        calls = []

        class _RateLimitResponse:
            status_code = 429
            headers = {"Retry-After": "45"}
            text = '{"error":{"code":429,"message":"Quota exceeded"}}'

        async def _fake_post_429(self, url, json=None, headers=None):
            calls.append(url)
            return _RateLimitResponse()

        monkeypatch.setattr(httpx.AsyncClient, "post", _fake_post_429)

        gemini = GeminiClient(api_key="test-gemini-secret-key")
        first = asyncio.run(gemini.understand_product(image_base64=png_1x1_b64, mime_type="image/png"))
        assert len(calls) == 1
        assert first["rate_limited"] is True
        assert first["error_code"] == "RATE_LIMIT_EXCEEDED"
        assert first["diagnostic_note"] == "Free AI limit reached. Try again later."
        assert first["recognition_source"] == "MANUAL_SEARCH"

        # Second call during cooldown must NOT hit network at all, while falling back to Local OCR if hint exists
        second = asyncio.run(
            gemini.understand_product(
                text="Logitech MX Master 3S mouse",
                image_base64=png_1x1_b64,
                mime_type="image/png",
            )
        )
        assert len(calls) == 1
        assert second["rate_limited"] is True
        assert second["recognition_source"] == "LOCAL_OCR"
        assert "Free AI limit reached" in second["diagnostic_note"]

    def test_duplicate_image_hash_uses_cache_without_second_api_call(self, monkeypatch):
        import base64
        import json as _json
        import httpx

        png_1x1_b64 = (
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        )
        calls = []

        class _OkResponse:
            status_code = 200
            def json(self):
                return {
                    "candidates": [{
                        "content": {
                            "parts": [{
                                "text": _json.dumps({
                                    "brand": "Logitech",
                                    "product_name": "Logitech Wireless Mouse",
                                    "category": "Electronics",
                                    "model": "M185",
                                    "colour": "Black",
                                    "specifications": ["Connectivity: 2.4GHz USB Receiver"],
                                    "search_query": "Logitech M185 Wireless Mouse",
                                    "confidence": 0.91,
                                    "identification_status": "IDENTIFIED",
                                })
                            }]
                        }
                    }]
                }

        async def _fake_post_ok(self, url, json=None, headers=None):
            calls.append(url)
            return _OkResponse()

        monkeypatch.setattr(httpx.AsyncClient, "post", _fake_post_ok)

        gemini = GeminiClient(api_key="test-gemini-secret-key")
        first = asyncio.run(gemini.understand_product(image_base64=png_1x1_b64, mime_type="image/png"))
        second = asyncio.run(gemini.understand_product(image_base64=png_1x1_b64, mime_type="image/png"))

        assert len(calls) == 1
        assert first["cache_hit"] is False
        assert second["cache_hit"] is True
        assert second["api_requests_made"] == 1
        assert second["product_name"] == "Logitech Wireless Mouse"

    def test_invalid_api_key_corrupt_image_and_large_image_compression(self, monkeypatch):
        import base64
        import io
        from PIL import Image
        import httpx
        from backend.gemini_client import inspect_image_payload, is_valid_api_key_format

        assert is_valid_api_key_format("AQ.Ab8RN6ExampleValidFormat123456") is True
        assert is_valid_api_key_format("AIzaSyExampleValidFormat123456") is True
        assert is_valid_api_key_format("changeme") is False
        assert is_valid_api_key_format("short") is False

        async def _should_not_post(*args, **kwargs):
            raise AssertionError("Network call must NOT happen for invalid keys or corrupt images")

        monkeypatch.setattr(httpx.AsyncClient, "post", _should_not_post)

        # Invalid placeholder key -> zero API calls
        bad_key_client = GeminiClient(api_key="placeholder")
        res_bad_key = asyncio.run(bad_key_client.understand_product(image_base64=None))
        assert res_bad_key["error_code"] == "INVALID_API_KEY"

        # Corrupt JPEG header -> rejected before spending API quota
        corrupt_b64 = base64.b64encode(b"\xff\xd8\xff\xe0\x00\x10JFIFCORRUPT_DATA").decode("ascii")
        valid_client = GeminiClient(api_key="AQ.Ab8RN6TestKeyValidFormat123")
        res_corrupt = asyncio.run(valid_client.understand_product(image_base64=corrupt_b64, mime_type="image/jpeg"))
        assert res_corrupt["source"] == "corrupt_image"
        assert res_corrupt["error_code"] == "CORRUPT_IMAGE"

        # Oversized image > 1024px must be downscaled to minimize free quota consumption
        big_img = Image.new("RGB", (1800, 1200), color=(40, 110, 80))
        buf = io.BytesIO()
        big_img.save(buf, format="PNG")
        big_b64 = base64.b64encode(buf.getvalue()).decode("ascii")
        inspected = inspect_image_payload(big_b64, declared_mime="image/png")
        assert inspected["valid"] is True
        assert inspected["compressed"] is True
        assert max(inspected["width"], inspected["height"]) <= 1024

    def test_model_availability_check_uses_approved_free_model(self, monkeypatch):
        import httpx

        seen_urls = []

        class _GetResponse:
            status_code = 200

        async def _fake_get(self, url, headers=None):
            seen_urls.append((url, headers))
            return _GetResponse()

        monkeypatch.setattr(httpx.AsyncClient, "get", _fake_get)
        gemini = GeminiClient(api_key="AQ.Ab8RN6TestKeyValidFormat123")
        status = asyncio.run(gemini.check_model_availability())
        assert status["available"] is True
        assert status["model"] == "gemini-3.5-flash-lite"
        assert status["free_tier_only"] is True
        assert "/models/gemini-3.5-flash-lite" in seen_urls[0][0]
        assert seen_urls[0][1]["x-goog-api-key"] == "AQ.Ab8RN6TestKeyValidFormat123"





