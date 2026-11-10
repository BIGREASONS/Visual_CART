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
from main import (
    app,
    _simulated_orders,
    _captured_orders,
    _idempotency_records,
    format_currency_string,
    calculate_order_breakdown,
    CreateOrderRequest,
)

client = TestClient(app)


def test_health_check():
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "healthy"
    assert "has_paypal_credentials" in data


def test_paypal_configuration():
    for endpoint in ["/api/v1/payments/paypal/configuration", "/api/paypal/configuration"]:
        response = client.get(endpoint)
        assert response.status_code == 200
        data = response.json()
        assert data["provider"] == "paypal"
        assert data["mode"] == "sandbox"
        assert isinstance(data["credentials_configured"], bool)
        assert isinstance(data["oauth_ready"], bool)
        assert isinstance(data["orders_api_ready"], bool)
        assert "client_id" in data
        assert data["is_sandbox"] is True
        assert data["deep_link_scheme"] == "paypalvisualcart"
        # Verify no secret or token is leaked
        assert "client_secret" not in data
        assert "secret" not in data
        assert "access_token" not in data
        assert "token" not in data


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


def test_paypal_configuration_alias_and_fields():
    """Validates the alias endpoint /api/paypal/configuration and security headers."""
    response = client.get("/api/paypal/configuration")
    assert response.status_code == 200
    data = response.json()
    assert "client_id" in data
    assert data["mode"] == "sandbox"
    assert data["is_sandbox"] is True
    assert "default_currency" in data
    assert "brand_name" in data
    assert "is_live_configured" in data
    assert data["deep_link_scheme"] == "paypalvisualcart"
    # Verify no secret is leaked
    assert "client_secret" not in data
    assert "secret" not in data
    assert "api_key" not in data
    assert "access_token" not in data


def test_paypal_configuration_live_vs_mock_flag():
    """Verifies is_live_configured mirrors genuine credentials presence."""
    with patch("main.has_live_paypal_credentials", return_value=True):
        res = client.get("/api/v1/payments/paypal/configuration")
        assert res.json()["is_live_configured"] is True

    with patch("main.has_live_paypal_credentials", return_value=False):
        res = client.get("/api/v1/payments/paypal/configuration")
        assert res.json()["is_live_configured"] is False


def test_currency_formatting_all_symbols():
    """Tests currency string formatting for INR, USD, EUR, GBP, and custom currencies."""
    assert format_currency_string(5499.00, "INR") == "₹5,499.00"
    assert format_currency_string(64.88, "USD") == "$64.88"
    assert format_currency_string(49.99, "EUR") == "€49.99"
    assert format_currency_string(39.99, "GBP") == "£39.99"
    assert format_currency_string(1000.0, "JPY") == "¥1,000"
    assert format_currency_string(100.50, "CAD") == "CA$100.50"
    assert format_currency_string(50.00, "SGD") == "SGD 50.00"
    # Large numbers with commas
    assert format_currency_string(1234567.89, "INR") == "₹1,234,567.89"
    # Zero amount
    assert format_currency_string(0.0, "USD") == "$0.00"


def test_calculate_order_breakdown_multi_currency():
    """Validates server-side breakdown calculation with different tax rates and delivery fees."""
    # Free delivery case
    req_inr = CreateOrderRequest(
        title="Test Crucial SSD",
        amount=4660.17,
        currency="INR",
        quantity=1,
        tax_rate=0.18,
        delivery_fee=0.0
    )
    summary_inr = calculate_order_breakdown(req_inr)
    assert summary_inr.subtotal == 4660.17
    assert summary_inr.tax == 838.83
    assert summary_inr.delivery_fee == 0.0
    assert summary_inr.total == 5499.00
    assert summary_inr.formatted_delivery == "FREE"
    assert summary_inr.formatted_total == "₹5,499.00"

    # Multi-item USD case with shipping fee
    req_usd = CreateOrderRequest(
        title="Mechanical Keyboard",
        amount=75.0,
        currency="USD",
        quantity=2,
        tax_rate=0.08,
        delivery_fee=12.50
    )
    summary_usd = calculate_order_breakdown(req_usd)
    assert summary_usd.subtotal == 150.0
    assert summary_usd.tax == 12.0
    assert summary_usd.delivery_fee == 12.50
    assert summary_usd.total == 174.50
    assert summary_usd.formatted_subtotal == "$150.00"
    assert summary_usd.formatted_total == "$174.50"


def test_capture_order_idempotency_with_key():
    """Verifies that repeat captures with the same idempotency_key return the cached result."""
    # 1. Create order
    create_res = client.post(
        "/api/v1/payments/paypal/create-order",
        json={"title": "Idempotent Test Item", "amount": 29.99, "currency": "USD"}
    )
    order_id = create_res.json()["order_id"]
    idemp_key = "test-idemp-uuid-555"

    # 2. First capture
    cap1 = client.post(
        "/api/v1/payments/paypal/capture-order",
        json={"order_id": order_id, "idempotency_key": idemp_key}
    )
    assert cap1.status_code == 200
    data1 = cap1.json()
    assert data1["status"] == "COMPLETED"

    # 3. Second capture with same key
    cap2 = client.post(
        "/api/v1/payments/paypal/capture-order",
        json={"order_id": order_id, "idempotency_key": idemp_key}
    )
    assert cap2.status_code == 200
    data2 = cap2.json()
    assert data2["capture_id"] == data1["capture_id"]
    assert data2["amount"] == data1["amount"]


def test_capture_order_idempotency_with_header():
    """Verifies idempotency when PayPal-Request-Id header is provided."""
    create_res = client.post(
        "/api/v1/payments/paypal/create-order",
        json={"title": "Header Idemp Item", "amount": 49.00, "currency": "USD"}
    )
    order_id = create_res.json()["order_id"]
    req_id = "header-request-id-999"

    cap1 = client.post(
        "/api/v1/payments/paypal/capture-order",
        json={"order_id": order_id},
        headers={"PayPal-Request-Id": req_id}
    )
    assert cap1.status_code == 200
    data1 = cap1.json()

    cap2 = client.post(
        "/api/v1/payments/paypal/capture-order",
        json={"order_id": order_id},
        headers={"PayPal-Request-Id": req_id}
    )
    assert cap2.status_code == 200
    data2 = cap2.json()
    assert data2["capture_id"] == data1["capture_id"]


def test_capture_order_duplicate_on_same_order_id():
    """Verifies that capturing the same order twice returns the existing capture without duplication."""
    create_res = client.post(
        "/api/v1/payments/paypal/create-order",
        json={"title": "Duplicate Order Capture Test", "amount": 19.99, "currency": "USD"}
    )
    order_id = create_res.json()["order_id"]

    cap1 = client.post("/api/v1/payments/paypal/capture-order", json={"order_id": order_id})
    assert cap1.status_code == 200
    cap_id_1 = cap1.json()["capture_id"]

    cap2 = client.post("/api/v1/payments/paypal/capture-order", json={"order_id": order_id})
    assert cap2.status_code == 200
    cap_id_2 = cap2.json()["capture_id"]
    assert cap_id_1 == cap_id_2


def test_negative_mock_response_instrument_declined_simulated():
    """Tests simulated negative mock response INSTRUMENT_DECLINED returns HTTP 422 with descriptive detail."""
    create_res = client.post(
        "/api/v1/payments/paypal/create-order",
        json={"title": "Declined Card Test", "amount": 99.00, "currency": "USD"}
    )
    order_id = create_res.json()["order_id"]

    headers = {
        "PayPal-Mock-Response": '{"mock_application_codes": "INSTRUMENT_DECLINED"}'
    }
    cap_res = client.post(
        "/api/v1/payments/paypal/capture-order",
        json={"order_id": order_id},
        headers=headers
    )
    assert cap_res.status_code == 422
    err_detail = cap_res.json()["detail"]
    assert "INSTRUMENT_DECLINED" in err_detail
    assert "debug_id" in err_detail


def test_negative_mock_response_duplicate_invoice_simulated():
    """Tests simulated negative mock response DUPLICATE_INVOICE_ID returns HTTP 422."""
    create_res = client.post(
        "/api/v1/payments/paypal/create-order",
        json={"title": "Duplicate Invoice Test", "amount": 85.00, "currency": "USD"}
    )
    order_id = create_res.json()["order_id"]

    headers = {
        "PayPal-Mock-Response": '{"mock_application_codes": "DUPLICATE_INVOICE_ID"}'
    }
    cap_res = client.post(
        "/api/v1/payments/paypal/capture-order",
        json={"order_id": order_id},
        headers=headers
    )
    assert cap_res.status_code == 422
    err_detail = cap_res.json()["detail"]
    assert "DUPLICATE_INVOICE_ID" in err_detail


def test_negative_mock_response_live_paypal_error_envelope_parsing():
    """Tests official PayPal error JSON envelope parsing on live capture HTTP 422 response."""
    with patch("main.has_live_paypal_credentials", return_value=True), \
         patch("main.get_paypal_access_token", return_value="MOCK_ACCESS_TOKEN_XYZ"):

        mock_error_body = {
            "name": "UNPROCESSABLE_ENTITY",
            "message": "The requested action could not be performed, semantically incorrect, or failed business validation.",
            "debug_id": "live-debug-id-777",
            "details": [
                {
                    "issue": "INSTRUMENT_DECLINED",
                    "description": "The instrument presented was declined. Try a different payment method."
                }
            ]
        }

        with patch("httpx.AsyncClient.post") as mock_post:
            mock_res = MagicMock()
            mock_res.status_code = 422
            mock_res.json.return_value = mock_error_body
            mock_res.text = str(mock_error_body)
            # Create HTTPStatusError
            mock_res.raise_for_status.side_effect = httpx.HTTPStatusError(
                message="422 Unprocessable Entity",
                request=MagicMock(),
                response=mock_res
            )

            async def async_mock_post(*args, **kwargs):
                return mock_res
            mock_post.side_effect = async_mock_post

            response = client.post(
                "/api/v1/payments/paypal/capture-order",
                json={"order_id": "ORD-LIVE-DECLINED-999"}
            )

            assert response.status_code == 422
            detail = response.json()["detail"]
            assert "INSTRUMENT_DECLINED" in detail
            assert "live-debug-id-777" in detail
            assert "Try a different payment method" in detail


def test_negative_mock_response_duplicate_invoice_live_parsing():
    """Tests official PayPal error JSON parsing for DUPLICATE_INVOICE_ID."""
    with patch("main.has_live_paypal_credentials", return_value=True), \
         patch("main.get_paypal_access_token", return_value="MOCK_ACCESS_TOKEN_XYZ"):

        mock_error_body = {
            "name": "UNPROCESSABLE_ENTITY",
            "message": "The requested action could not be performed.",
            "debug_id": "live-debug-inv-888",
            "details": [
                {
                    "issue": "DUPLICATE_INVOICE_ID",
                    "description": "The invoice ID has been used on a previous payment."
                }
            ]
        }

        with patch("httpx.AsyncClient.post") as mock_post:
            mock_res = MagicMock()
            mock_res.status_code = 422
            mock_res.json.return_value = mock_error_body
            mock_res.text = str(mock_error_body)
            mock_res.raise_for_status.side_effect = httpx.HTTPStatusError(
                message="422 Unprocessable Entity",
                request=MagicMock(),
                response=mock_res
            )

            async def async_mock_post(*args, **kwargs):
                return mock_res
            mock_post.side_effect = async_mock_post

            response = client.post(
                "/api/v1/payments/paypal/capture-order",
                json={"order_id": "ORD-LIVE-INVOICE-888"}
            )

            assert response.status_code == 422
            detail = response.json()["detail"]
            assert "DUPLICATE_INVOICE_ID" in detail
            assert "live-debug-inv-888" in detail


def test_configurable_currency_and_iso_precision_usd():
    """Verifies configurable currency support and 2-decimal minor unit precision (USD)."""
    payload = {
        "title": "SanDisk Extreme 1TB SSD",
        "amount": 54.99,
        "currency": "USD",
        "tax_rate": 0.08,
        "delivery_fee": 9.89,
        "quantity": 1,
    }
    response = client.post("/api/v1/payments/paypal/create-order", json=payload)
    assert response.status_code == 200
    data = response.json()

    assert data["currency"] == "USD"
    summary = data["order_summary"]
    assert summary["subtotal"] == 54.99
    assert summary["tax"] == 4.40  # round(54.99 * 0.08, 2)
    assert summary["delivery_fee"] == 9.89
    assert summary["total"] == 69.28
    assert "$" in summary["formatted_total"]
    assert summary["formatted_total"] == "$69.28"


def test_zero_decimal_currency_jpy():
    """Verifies ISO 4217 zero-decimal currency support (JPY) with integer minor units."""
    payload = {
        "title": "Sony WH-1000XM5 Wireless Headphones",
        "amount": 42000.0,
        "currency": "JPY",
        "tax_rate": 0.10,
        "delivery_fee": 500.0,
        "quantity": 1,
    }
    response = client.post("/api/v1/payments/paypal/create-order", json=payload)
    assert response.status_code == 200
    data = response.json()

    assert data["currency"] == "JPY"
    summary = data["order_summary"]
    # JPY must be formatted with zero decimal places
    assert summary["subtotal"] == 42000.0
    assert summary["tax"] == 4200.0
    assert summary["delivery_fee"] == 500.0
    assert summary["total"] == 46700.0
    assert summary["formatted_total"] == "¥46,700"
    assert "." not in summary["formatted_total"]


def test_server_authoritative_calculations():
    """Verifies that subtotal, tax, shipping, and totals are computed strictly server-side."""
    payload = {
        "title": "Crucial P3 Plus 2TB NVMe SSD",
        "amount": 99.99,
        "quantity": 2,
        "currency": "USD",
        "tax_rate": 0.07,
        "delivery_fee": 12.50,
    }
    response = client.post("/api/v1/payments/paypal/create-order", json=payload)
    assert response.status_code == 200
    data = response.json()

    summary = data["order_summary"]
    expected_subtotal = round(99.99 * 2, 2)  # 199.98
    expected_tax = round(expected_subtotal * 0.07, 2)  # 14.00
    expected_delivery = 12.50
    expected_total = round(expected_subtotal + expected_tax + expected_delivery, 2)  # 226.48

    assert summary["subtotal"] == expected_subtotal
    assert summary["tax"] == expected_tax
    assert summary["delivery_fee"] == expected_delivery
    assert summary["total"] == expected_total
    assert data["amount"] == expected_total


def test_capture_strictly_rejects_approved_order():
    """Verifies that order capture strictly rejects status == 'APPROVED' (never treating APPROVED as completed)."""
    with patch("main.has_live_paypal_credentials", return_value=True), \
         patch("main.get_paypal_access_token", return_value="MOCK_ACCESS_TOKEN_XYZ"):

        mock_approved_response = {
            "id": "ORD-APPROVED-NOT-COMPLETED",
            "status": "APPROVED",  # Not COMPLETED!
            "purchase_units": [
                {
                    "reference_id": "default",
                    "payments": {
                        "captures": []
                    }
                }
            ],
            "payer": {
                "name": {"given_name": "Jane", "surname": "Smith"},
                "email_address": "buyer@example.com"
            }
        }

        with patch("httpx.AsyncClient.post") as mock_post:
            mock_res = MagicMock()
            mock_res.status_code = 200
            mock_res.json.return_value = mock_approved_response
            mock_res.raise_for_status = MagicMock()
            async def async_mock_post(*args, **kwargs):
                return mock_res
            mock_post.side_effect = async_mock_post

            response = client.post(
                "/api/v1/payments/paypal/capture-order",
                json={"order_id": "ORD-APPROVED-NOT-COMPLETED"}
            )

            assert response.status_code == 422
            detail = response.json()["detail"]
            assert "expected 'COMPLETED'" in detail
            assert "Never treating APPROVED as completed" in detail


def test_capture_extracts_real_capture_id_gross_amount_and_seller_protection():
    """Verifies that order capture extracts real capture ID, gross amount, currency, timestamp, and seller protection."""
    with patch("main.has_live_paypal_credentials", return_value=True), \
         patch("main.get_paypal_access_token", return_value="MOCK_ACCESS_TOKEN_XYZ"):

        mock_capture_response = {
            "id": "8B743912KL893412P",
            "status": "COMPLETED",
            "purchase_units": [
                {
                    "reference_id": "default",
                    "payments": {
                        "captures": [
                            {
                                "id": "CAP-9A127845KL",
                                "status": "COMPLETED",
                                "amount": {
                                    "currency_code": "USD",
                                    "value": "64.88"
                                },
                                "seller_protection": {
                                    "status": "ELIGIBLE",
                                    "dispute_categories": ["ITEM_NOT_RECEIVED", "UNAUTHORIZED_TRANSACTION"]
                                },
                                "seller_receivable_breakdown": {
                                    "gross_amount": {
                                        "currency_code": "USD",
                                        "value": "64.88"
                                    },
                                    "paypal_fee": {
                                        "currency_code": "USD",
                                        "value": "2.18"
                                    },
                                    "net_amount": {
                                        "currency_code": "USD",
                                        "value": "62.70"
                                    }
                                },
                                "create_time": "2026-11-10T03:33:32Z"
                            }
                        ]
                    }
                }
            ],
            "payer": {
                "name": {"given_name": "John", "surname": "Doe"},
                "email_address": "john.doe@example.com",
                "payer_id": "PAYER-JOHN-99"
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
                json={"order_id": "8B743912KL893412P"}
            )

            assert response.status_code == 200
            data = response.json()
            assert data["order_id"] == "8B743912KL893412P"
            assert data["capture_id"] == "CAP-9A127845KL"
            assert data["status"] == "COMPLETED"
            assert data["amount"] == 64.88
            assert data["gross_amount"] == 64.88
            assert data["currency"] == "USD"
            assert data["seller_protection_status"] == "ELIGIBLE"
            assert data["create_time"] == "2026-11-10T03:33:32Z"
            assert data["payer_email"] == "john.doe@example.com"
            assert data["payer_name"] == "John Doe"
            assert data["payer_id"] == "PAYER-JOHN-99"


def test_mock_response_strictly_forbidden_in_production(monkeypatch):
    """Enforces that PayPal-Mock-Response header is ONLY permitted when PAYPAL_MODE == 'sandbox'."""
    monkeypatch.setattr("main.PAYPAL_MODE", "live")

    # 1. Capture order with PayPal-Mock-Response in live mode must be rejected with HTTP 400
    res_capture = client.post(
        "/api/v1/payments/paypal/capture-order",
        json={"order_id": "ORD-LIVE-PROD-TEST"},
        headers={"PayPal-Mock-Response": '{"mock_application_codes": "INSTRUMENT_DECLINED"}'}
    )
    assert res_capture.status_code == 400
    assert "strictly forbidden" in res_capture.json()["detail"]

    # 2. Create order with PayPal-Mock-Response in live mode must be rejected with HTTP 400
    res_create = client.post(
        "/api/v1/payments/paypal/create-order",
        json={"title": "Prod Item", "amount": 100.0, "currency": "USD"},
        headers={"PayPal-Mock-Response": '{"mock_application_codes": "DUPLICATE_INVOICE_ID"}'}
    )
    assert res_create.status_code == 400
    assert "strictly forbidden" in res_create.json()["detail"]


def test_create_order_duplicate_invoice_negative_simulation():
    """Verifies that DUPLICATE_INVOICE_ID on create-order simulates PayPal's error envelope."""
    res = client.post(
        "/api/v1/payments/paypal/create-order",
        json={"title": "Duplicate Item", "amount": 50.0, "currency": "USD", "invoice_id": "INV-DUP-123"},
        headers={"PayPal-Mock-Response": '{"mock_application_codes": "DUPLICATE_INVOICE_ID"}'}
    )
    assert res.status_code == 422
    data = res.json()
    assert "DUPLICATE_INVOICE_ID" in data["detail"]
    assert "debug_id" in data["detail"]


def test_debug_id_logging_no_secrets_in_server(caplog):
    """Verifies PayPal's debug_id and issue codes are logged for diagnostics without exposing sensitive data."""
    import logging
    with caplog.at_level(logging.ERROR):
        res = client.post(
            "/api/v1/payments/paypal/capture-order",
            json={"order_id": "SBX-ORD-LOG-TEST"},
            headers={"PayPal-Mock-Response": '{"mock_application_codes": "INSTRUMENT_DECLINED"}'}
        )
        assert res.status_code == 422
        # Check logs
        log_text = caplog.text
        assert "INSTRUMENT_DECLINED" in log_text
        assert "sbx_dbg_" in log_text
        # Security: ensure no sensitive secrets or bearer tokens leaked in logs
        assert "Bearer" not in log_text
        assert "PAYPAL_CLIENT_SECRET" not in log_text
        assert "client_secret" not in log_text



