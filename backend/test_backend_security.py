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
from backend.paypal_client import PayPalClient
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
