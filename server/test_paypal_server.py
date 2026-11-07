"""
Unit and integration tests for PayPal Visual Cart FastAPI Backend.
Validates:
1. Public configuration endpoint
2. Server-side price calculation and Order creation
3. Order capture and transaction verification
4. Order status retrieval
5. Mock approval web interface
6. Multimodal/heuristic AI product recognition
7. Live PayPal Sandbox API client flow with mocked external HTTP
"""

import pytest
from fastapi.testclient import TestClient
from unittest.mock import patch, MagicMock
import httpx
from main import app, _simulated_orders

client = TestClient(app)


def test_health_check():
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "healthy"
    assert "has_paypal_credentials" in data


def test_paypal_configuration():
    response = client.get("/api/v1/payments/paypal/configuration")
    assert response.status_code == 200
    data = response.json()
    assert "client_id" in data
    assert data["mode"] == "sandbox"
    assert data["is_sandbox"] is True
    assert data["deep_link_scheme"] == "paypalvisualcart"
    # Verify no secret is leaked
    assert "client_secret" not in data
    assert "secret" not in data


def test_create_order_inr_calculation():
    payload = {
        "title": "Samsung 990 EVO 1TB NVMe SSD",
        "amount": 5999.0,
        "currency": "INR",
        "tax_rate": 0.18,
        "delivery_fee": 0.0,
        "quantity": 1,
        "listing_id": "item_samsung_ssd",
    }
    response = client.post("/api/v1/payments/paypal/create-order", json=payload)
    assert response.status_code == 200
    data = response.json()

    assert "order_id" in data
    assert data["status"] == "CREATED"
    assert data["currency"] == "INR"
    assert "approve_url" in data
    assert len(data["approve_url"]) > 0

    summary = data["order_summary"]
    assert summary["subtotal"] == 5999.0
    expected_tax = round(5999.0 * 0.18, 2)  # 1079.82
    assert summary["tax"] == expected_tax
    assert summary["delivery_fee"] == 0.0
    assert summary["total"] == round(5999.0 + expected_tax, 2)
    assert summary["formatted_delivery"] == "FREE"
    assert "₹" in summary["formatted_total"]


def test_create_order_with_delivery_fee():
    payload = {
        "title": "Sony WH-1000XM5 Headphones",
        "amount": 100.0,
        "currency": "USD",
        "tax_rate": 0.10,
        "delivery_fee": 15.0,
        "quantity": 2,
    }
    response = client.post("/api/v1/payments/paypal/create-order", json=payload)
    assert response.status_code == 200
    data = response.json()

    summary = data["order_summary"]
    assert summary["subtotal"] == 200.0
    assert summary["tax"] == 20.0
    assert summary["delivery_fee"] == 15.0
    assert summary["total"] == 235.0
    assert "$" in summary["formatted_total"]


def test_capture_order():
    # 1. Create order first
    create_payload = {
        "title": "Crucial P3 Plus 1TB SSD",
        "amount": 5499.0,
        "currency": "INR",
    }
    create_res = client.post("/api/v1/payments/paypal/create-order", json=create_payload)
    assert create_res.status_code == 200
    order_id = create_res.json()["order_id"]

    # 2. Capture the order
    capture_payload = {"order_id": order_id}
    capture_res = client.post("/api/v1/payments/paypal/capture-order", json=capture_payload)
    assert capture_res.status_code == 200
    cap_data = capture_res.json()

    assert cap_data["order_id"] == order_id
    assert cap_data["status"] == "COMPLETED"
    assert "capture_id" in cap_data
    assert cap_data["capture_id"].startswith("CAP-")
    assert "payer_email" in cap_data
    assert cap_data["payer_email"] is not None


def test_get_order_details():
    # Create order
    create_res = client.post(
        "/api/v1/payments/paypal/create-order",
        json={"title": "Apple AirPods Pro", "amount": 199.0, "currency": "USD"},
    )
    order_id = create_res.json()["order_id"]

    # Get order
    get_res = client.get(f"/api/v1/payments/paypal/order/{order_id}")
    assert get_res.status_code == 200
    order_data = get_res.json()
    assert order_data["id"] == order_id
    assert order_data["currency"] == "USD"


def test_mock_approve_page():
    response = client.get("/api/v1/payments/paypal/mock-approve?order_id=SBX-TEST1234")
    assert response.status_code == 200
    assert "text/html" in response.headers["content-type"]
    html = response.text
    assert "PayPal Sandbox Checkout" in html
    assert "SBX-TEST1234" in html
    assert "paypalvisualcart://checkout/success" in html


def test_ai_recognize_product_local_fallback():
    payload = {
        "hint_text": "Samsung 990 EVO 1TB NVMe M.2 Solid State Drive PCIe 4.0 x4",
    }
    response = client.post("/api/v1/ai/recognize-product", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert data["brand"] == "Samsung"
    assert data["category"] == "Electronics"
    assert data["specifications"]["Capacity"] == "1TB"
    assert data["specifications"]["Brand"] == "Samsung"
    assert data["confidence"] > 0.8


def test_live_paypal_create_order_flow_mocked():
    """Verifies the OAuth + Orders v2 creation flow when PayPal credentials are mock-injected."""
    with patch("main.has_live_paypal_credentials", return_value=True), \
         patch("main.get_paypal_access_token", return_value="MOCK_ACCESS_TOKEN_XYZ"):

        mock_paypal_order_response = {
            "id": "5O190127TN364715T",
            "status": "CREATED",
            "links": [
                {
                    "href": "https://www.sandbox.paypal.com/checkoutnow?token=5O190127TN364715T",
                    "rel": "approve",
                    "method": "GET"
                }
            ]
        }

        with patch("httpx.AsyncClient.post") as mock_post:
            mock_res = MagicMock()
            mock_res.status_code = 201
            mock_res.json.return_value = mock_paypal_order_response
            mock_res.raise_for_status = MagicMock()
            # async context manager mock
            async def async_mock_post(*args, **kwargs):
                return mock_res
            mock_post.side_effect = async_mock_post

            response = client.post(
                "/api/v1/payments/paypal/create-order",
                json={
                    "title": "SanDisk Extreme 1TB SSD",
                    "amount": 89.99,
                    "currency": "USD"
                }
            )

            assert response.status_code == 200
            data = response.json()
            assert data["order_id"] == "5O190127TN364715T"
            assert data["is_live_sandbox"] is True
            assert "https://www.sandbox.paypal.com/checkoutnow?token=5O190127TN364715T" in data["approve_url"]


def test_live_paypal_capture_flow_mocked():
    """Verifies the Orders v2 capture flow and response parsing."""
    with patch("main.has_live_paypal_credentials", return_value=True), \
         patch("main.get_paypal_access_token", return_value="MOCK_ACCESS_TOKEN_XYZ"):

        mock_capture_response = {
            "id": "5O190127TN364715T",
            "status": "COMPLETED",
            "purchase_units": [
                {
                    "reference_id": "default",
                    "payments": {
                        "captures": [
                            {
                                "id": "3C679366HH2525463",
                                "status": "COMPLETED",
                                "amount": {
                                    "currency_code": "USD",
                                    "value": "106.19"
                                },
                                "create_time": "2026-11-07T09:40:00Z"
                            }
                        ]
                    }
                }
            ],
            "payer": {
                "name": {"given_name": "Jane", "surname": "Smith"},
                "email_address": "buyer@example.com",
                "payer_id": "WXYZ12345"
            }
        }

        with patch("httpx.AsyncClient.post") as mock_post:
            mock_res = MagicMock()
            mock_res.status_code = 201
            mock_res.json.return_value = mock_capture_response
            mock_res.raise_for_status = MagicMock()
            async def async_mock_post(*args, **kwargs):
                return mock_res
            mock_post.side_effect = async_mock_post

            response = client.post(
                "/api/v1/payments/paypal/capture-order",
                json={"order_id": "5O190127TN364715T"}
            )

            assert response.status_code == 200
            data = response.json()
            assert data["order_id"] == "5O190127TN364715T"
            assert data["capture_id"] == "3C679366HH2525463"
            assert data["status"] == "COMPLETED"
            assert data["amount"] == 106.19
            assert data["payer_email"] == "buyer@example.com"
            assert data["payer_name"] == "Jane Smith"
            assert data["payer_id"] == "WXYZ12345"
            assert data["is_live_sandbox"] is True
