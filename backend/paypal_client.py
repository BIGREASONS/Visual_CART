"""PayPal Orders v2 API Client.

Handles OAuth 2.0 Client Credentials token exchange and order lifecycle
(create-order, get-order-details, and capture-order) against the PayPal Sandbox environment.
Provides strict COMPLETED status verification, server-authoritative financial calculation,
and ISO 4217 minor unit precision handling.
"""

import os
import json
import base64
import time
import uuid
import logging
from typing import Dict, Any, Optional, List
import httpx

logger = logging.getLogger("paypal_client")

# Currencies with 0 decimal places according to ISO 4217 and PayPal specifications
ZERO_DECIMAL_CURRENCIES = {"JPY", "HUF", "TWD", "KRW", "VND"}
# Currencies with 3 decimal places
THREE_DECIMAL_CURRENCIES = {"BHD", "JOD", "KWD", "OMR", "TND"}


def get_currency_decimals(currency: str) -> int:
    """Returns number of decimal digits required by ISO 4217 / PayPal for given currency."""
    curr = (currency or "USD").strip().upper()
    if curr in ZERO_DECIMAL_CURRENCIES:
        return 0
    if curr in THREE_DECIMAL_CURRENCIES:
        return 3
    return 2


def format_currency_amount(amount: float, currency: str = "USD") -> str:
    """Formats an amount into a PayPal ISO 4217 compliant string with correct decimal precision.
    
    Examples:
        format_currency_amount(64.88, "USD") -> "64.88"
        format_currency_amount(1000.0, "JPY") -> "1000"
        format_currency_amount(12.5, "EUR") -> "12.50"
    """
    decimals = get_currency_decimals(currency)
    if decimals == 0:
        return str(int(round(amount)))
    return f"{amount:.{decimals}f}"


def round_currency_amount(amount: float, currency: str = "USD") -> float:
    """Rounds amount to the correct decimal precision for the given currency."""
    decimals = get_currency_decimals(currency)
    if decimals == 0:
        return float(int(round(amount)))
    return round(amount, decimals)


class PayPalError(RuntimeError):
    """Custom exception for PayPal API errors.
    
    Contains parsed issue, debug_id, description, status code, and raw envelope
    while preventing sensitive credential leakage in logs and messages.
    Subclasses RuntimeError for backwards compatibility.
    """
    def __init__(
        self,
        message: str,
        issue: Optional[str] = None,
        debug_id: Optional[str] = None,
        description: Optional[str] = None,
        status_code: Optional[int] = 422,
        raw_envelope: Optional[Dict[str, Any]] = None
    ):
        super().__init__(message)
        self.issue = issue or "PAYPAL_ERROR"
        self.debug_id = debug_id or "UNKNOWN"
        self.description = description or message
        self.status_code = status_code or 422
        self.raw_envelope = raw_envelope or {}


def extract_mock_issue_code(mock_header: str) -> str:
    """Extracts mock application issue code from plain string or JSON header."""
    trimmed = (mock_header or "").strip()
    if trimmed.startswith("{"):
        try:
            parsed = json.loads(trimmed)
            return parsed.get("mock_application_codes") or "INSTRUMENT_DECLINED"
        except Exception:
            pass
    return trimmed


def parse_and_log_paypal_error(error_json: Dict[str, Any], status_code: int = 422) -> PayPalError:
    """Parses PayPal's official error envelope and logs diagnostics safely without secrets."""
    debug_id = error_json.get("debug_id") or "UNKNOWN"
    details = error_json.get("details", [])
    issue = details[0].get("issue") if details else error_json.get("name", "PAYPAL_ERROR")
    desc = details[0].get("description") if details else error_json.get("message", "PayPal transaction failed")

    # Diagnostic logging: debug_id and issue code logged safely without secrets
    logger.error(
        "PayPal API error envelope parsed: issue='%s', debug_id='%s', desc='%s'",
        issue, debug_id, desc
    )

    message = f"PayPal error [{issue}] (debug_id: {debug_id}): {desc}"
    return PayPalError(
        message=message,
        issue=issue,
        debug_id=debug_id,
        description=desc,
        status_code=status_code,
        raw_envelope=error_json
    )


class PayPalClient:
    def __init__(
        self,
        client_id: Optional[str] = None,
        client_secret: Optional[str] = None,
        base_url: Optional[str] = None,
        default_currency: Optional[str] = None,
        default_tax_rate: Optional[float] = None
    ):
        self.client_id = client_id if client_id is not None else os.getenv("PAYPAL_CLIENT_ID", "")
        self.client_secret = client_secret if client_secret is not None else os.getenv("PAYPAL_CLIENT_SECRET", "")
        self.base_url = (base_url or os.getenv("PAYPAL_BASE_URL", "https://api-m.sandbox.paypal.com")).rstrip("/")
        self.default_currency = (
            default_currency or os.getenv("PAYPAL_CURRENCY", os.getenv("DEFAULT_CURRENCY", "USD"))
        ).strip().upper()
        self.default_tax_rate = (
            default_tax_rate if default_tax_rate is not None
            else float(os.getenv("PAYPAL_TAX_RATE", "0.08"))
        )
        self._cached_token: Optional[str] = None
        self._token_expires_at: float = 0
        self._captured_orders: Dict[str, Dict[str, Any]] = {}
        self._idempotency_records: Dict[str, Dict[str, Any]] = {}
        self._orders_store: Dict[str, Dict[str, Any]] = {}

    @property
    def is_configured(self) -> bool:
        cid = (self.client_id or "").strip()
        sec = (self.client_secret or "").strip()
        return bool(
            cid
            and sec
            and not cid.startswith("your_")
            and not sec.startswith("your_")
            and not cid.startswith("sb-demo-")
        )

    async def get_access_token(self) -> str:
        """Obtains or reuses a valid OAuth 2.0 bearer token using Client Credentials grant.
        
        Official endpoint: POST https://api-m.sandbox.paypal.com/v1/oauth2/token
        """
        if not self.is_configured:
            # Fallback mock token for sandbox simulation
            return "MOCK_PAYPAL_SANDBOX_TOKEN"

        # Check token cache with 60-second safety margin
        if self._cached_token and time.time() < (self._token_expires_at - 60):
            return self._cached_token

        auth_header = base64.b64encode(f"{self.client_id}:{self.client_secret}".encode()).decode("utf-8")
        headers = {
            "Authorization": f"Basic {auth_header}",
            "Content-Type": "application/x-www-form-urlencoded"
        }
        data = {"grant_type": "client_credentials"}

        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(f"{self.base_url}/v1/oauth2/token", headers=headers, data=data)
            resp.raise_for_status()
            payload = resp.json()
            self._cached_token = payload["access_token"]
            expires_in = payload.get("expires_in", 3600)
            self._token_expires_at = time.time() + float(expires_in)
            return self._cached_token

    def calculate_totals(
        self,
        unit_price: float,
        quantity: int = 1,
        tax_rate: Optional[float] = None,
        shipping_fee: Optional[float] = None,
        currency_code: Optional[str] = None
    ) -> Dict[str, Any]:
        """Calculates server-authoritative financial totals (subtotal, tax, shipping, total).
        
        Enforces proper ISO 4217 minor unit precision so client amounts cannot be manipulated.
        """
        curr = (currency_code or self.default_currency).strip().upper()
        effective_tax_rate = tax_rate if tax_rate is not None else self.default_tax_rate
        effective_shipping = shipping_fee if shipping_fee is not None else 0.0

        subtotal = round_currency_amount(unit_price * max(1, quantity), curr)
        tax = round_currency_amount(subtotal * effective_tax_rate, curr)
        shipping = round_currency_amount(effective_shipping, curr)
        total = round_currency_amount(subtotal + tax + shipping, curr)

        return {
            "currency": curr,
            "unit_price": unit_price,
            "quantity": quantity,
            "subtotal": subtotal,
            "tax": tax,
            "tax_rate": effective_tax_rate,
            "shipping": shipping,
            "total": total,
            "formatted_unit_price": format_currency_amount(unit_price, curr),
            "formatted_subtotal": format_currency_amount(subtotal, curr),
            "formatted_tax": format_currency_amount(tax, curr),
            "formatted_shipping": format_currency_amount(shipping, curr),
            "formatted_total": format_currency_amount(total, curr),
        }

    async def create_order(
        self,
        amount_value: Optional[str] = None,
        currency_code: Optional[str] = None,
        item_name: str = "Visual Cart Item",
        return_url: str = "https://example.com/checkout/return",
        cancel_url: str = "https://example.com/checkout/cancel",
        quantity: int = 1,
        unit_price: Optional[float] = None,
        tax_rate: Optional[float] = None,
        shipping_fee: Optional[float] = None,
        custom_id: Optional[str] = None,
        reference_id: str = "default",
        invoice_id: Optional[str] = None,
        mock_response: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Creates an order using PayPal Orders v2 API (POST /v2/checkout/orders).
        
        Enforces server-authoritative totals and ISO 4217 minor unit formatting.
        Supports sandbox negative testing via PayPal-Mock-Response header.
        Strictly forbids negative testing headers in production/live environments.
        """
        effective_mock = mock_response or os.getenv("PAYPAL_MOCK_RESPONSE")
        paypal_mode = (os.getenv("PAYPAL_MODE") or os.getenv("PAYPAL_ENV") or "sandbox").strip().lower()
        if effective_mock and paypal_mode != "sandbox":
            logger.critical("Security Violation: PayPal-Mock-Response requested when PAYPAL_MODE is '%s'", paypal_mode)
            raise ValueError("PayPal-Mock-Response is strictly forbidden when PAYPAL_MODE is not 'sandbox'")

        curr = (currency_code or self.default_currency).strip().upper()

        if unit_price is not None:
            calc = self.calculate_totals(
                unit_price=unit_price,
                quantity=quantity,
                tax_rate=tax_rate,
                shipping_fee=shipping_fee,
                currency_code=curr
            )
            total_str = calc["formatted_total"]
            subtotal_str = calc["formatted_subtotal"]
            tax_str = calc["formatted_tax"]
            shipping_str = calc["formatted_shipping"]
            item_unit_str = calc["formatted_unit_price"]
            has_breakdown = True
        else:
            raw_val = float(amount_value or "64.88")
            total_str = format_currency_amount(raw_val, curr)
            subtotal_str = total_str
            tax_str = format_currency_amount(0.0, curr)
            shipping_str = format_currency_amount(0.0, curr)
            item_unit_str = format_currency_amount(raw_val / max(1, quantity), curr)
            has_breakdown = False

        purchase_unit: Dict[str, Any] = {
            "reference_id": reference_id,
            "description": item_name[:127],
            "amount": {
                "currency_code": curr,
                "value": total_str
            }
        }

        if custom_id:
            purchase_unit["custom_id"] = custom_id[:127]

        if invoice_id:
            purchase_unit["invoice_id"] = invoice_id[:127]

        if has_breakdown:
            purchase_unit["amount"]["breakdown"] = {
                "item_total": {
                    "currency_code": curr,
                    "value": subtotal_str
                },
                "tax_total": {
                    "currency_code": curr,
                    "value": tax_str
                },
                "shipping": {
                    "currency_code": curr,
                    "value": shipping_str
                }
            }
            purchase_unit["items"] = [
                {
                    "name": item_name[:127],
                    "unit_amount": {
                        "currency_code": curr,
                        "value": item_unit_str
                    },
                    "quantity": str(quantity),
                    "category": "PHYSICAL_GOODS"
                }
            ]

        if not self.is_configured:
            if effective_mock:
                sim_issue = extract_mock_issue_code(effective_mock)
                sim_debug_id = f"sbx_dbg_{uuid.uuid4().hex[:12]}"
                sim_desc = (
                    "The requested invoice ID was already used for an earlier order."
                    if sim_issue == "DUPLICATE_INVOICE_ID"
                    else f"Simulated mock failure for {sim_issue}"
                )
                sim_envelope = {
                    "name": "UNPROCESSABLE_ENTITY",
                    "details": [{"issue": sim_issue, "description": sim_desc}],
                    "message": f"Simulation error for {sim_issue}",
                    "debug_id": sim_debug_id
                }
                raise parse_and_log_paypal_error(sim_envelope, status_code=422)

            order_id = f"SBX-ORD-{uuid.uuid4().hex[:12].upper()}"
            order_record = {
                "id": order_id,
                "status": "CREATED",
                "currency": curr,
                "amount": float(total_str),
                "purchase_units": [purchase_unit],
                "links": [
                    {
                        "href": f"https://www.sandbox.paypal.com/checkoutnow?token={order_id}",
                        "rel": "approve",
                        "method": "GET"
                    }
                ],
                "mock": True
            }
            self._orders_store[order_id] = order_record
            return order_record

        token = await self.get_access_token()
        headers = {
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "Prefer": "return=representation"
        }
        if effective_mock:
            if effective_mock.startswith("{"):
                headers["PayPal-Mock-Response"] = effective_mock
            else:
                headers["PayPal-Mock-Response"] = json.dumps({"mock_application_codes": effective_mock})

        payload = {
            "intent": "CAPTURE",
            "purchase_units": [purchase_unit],
            "application_context": {
                "brand_name": "PayPal Visual Cart",
                "landing_page": "NO_PREFERENCE",
                "user_action": "PAY_NOW",
                "return_url": return_url,
                "cancel_url": cancel_url
            }
        }

        async with httpx.AsyncClient(timeout=10.0) as client:
            try:
                resp = await client.post(f"{self.base_url}/v2/checkout/orders", headers=headers, json=payload)
                resp.raise_for_status()
                return resp.json()
            except httpx.HTTPStatusError as e:
                try:
                    error_json = e.response.json()
                    raise parse_and_log_paypal_error(error_json, e.response.status_code) from e
                except (ValueError, KeyError):
                    logger.error("PayPal create_order failed with non-JSON response (status %d)", e.response.status_code)
                    raise PayPalError(f"PayPal create_order failed: {e.response.text}", status_code=e.response.status_code) from e

    async def get_order_details(self, order_id: str) -> Dict[str, Any]:
        """Retrieves PayPal order status and details (GET /v2/checkout/orders/{id})."""
        if not order_id or not order_id.strip():
            raise ValueError("Order ID cannot be blank")

        clean_order_id = order_id.strip()

        if not self.is_configured or clean_order_id.startswith("SBX-"):
            if clean_order_id in self._orders_store:
                return self._orders_store[clean_order_id]
            if clean_order_id in self._captured_orders:
                return self._captured_orders[clean_order_id]
            return {
                "id": clean_order_id,
                "status": "APPROVED",
                "intent": "CAPTURE",
                "currency": self.default_currency,
                "amount": 64.88,
                "mock": True
            }

        token = await self.get_access_token()
        headers = {
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
        }

        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(f"{self.base_url}/v2/checkout/orders/{clean_order_id}", headers=headers)
            resp.raise_for_status()
            return resp.json()

    async def capture_order(
        self,
        order_id: str,
        idempotency_key: Optional[str] = None,
        mock_response: Optional[str] = None
    ) -> Dict[str, Any]:
        """Captures an approved PayPal order (POST /v2/checkout/orders/{id}/capture).
        
        Strictly verifies that order and capture status == "COMPLETED" (never treating APPROVED as completed).
        Extracts real PayPal capture ID, gross amount, currency, timestamp, and seller protection status.
        Guarantees payment idempotency and prevents double captures on the same order.
        Supports sandbox negative testing via PayPal-Mock-Response header.
        Strictly forbids negative testing headers in production/live environments.
        """
        if not order_id or not order_id.strip():
            raise ValueError("Order ID cannot be blank")

        clean_order_id = order_id.strip()

        effective_mock = mock_response or os.getenv("PAYPAL_MOCK_RESPONSE")
        paypal_mode = (os.getenv("PAYPAL_MODE") or os.getenv("PAYPAL_ENV") or "sandbox").strip().lower()
        if effective_mock and paypal_mode != "sandbox":
            logger.critical("Security Violation: PayPal-Mock-Response requested when PAYPAL_MODE is '%s'", paypal_mode)
            raise ValueError("PayPal-Mock-Response is strictly forbidden when PAYPAL_MODE is not 'sandbox'")

        # Check idempotency record
        if idempotency_key and idempotency_key in self._idempotency_records:
            return self._idempotency_records[idempotency_key]

        # Prevent double capture on the same order
        if clean_order_id in self._captured_orders:
            cached_capture = self._captured_orders[clean_order_id]
            result = {
                **cached_capture,
                "already_captured": True,
                "duplicate_capture_prevented": True
            }
            if idempotency_key:
                self._idempotency_records[idempotency_key] = result
            return result

        if not self.is_configured or clean_order_id.startswith("SBX-"):
            if effective_mock:
                sim_issue = extract_mock_issue_code(effective_mock)
                sim_debug_id = f"sbx_dbg_{uuid.uuid4().hex[:12]}"
                if sim_issue == "INSTRUMENT_DECLINED":
                    sim_desc = "The instrument presented was declined. Please try another instrument."
                elif sim_issue == "DUPLICATE_INVOICE_ID":
                    sim_desc = "The requested invoice ID was already used for an earlier order."
                else:
                    sim_desc = f"Simulated mock failure for {sim_issue}"

                sim_envelope = {
                    "name": "UNPROCESSABLE_ENTITY",
                    "details": [{"issue": sim_issue, "description": sim_desc}],
                    "message": "The requested action could not be performed, semantically incorrect, or failed business validation.",
                    "debug_id": sim_debug_id,
                    "links": [
                        {
                            "href": f"https://developer.paypal.com/docs/api/orders/v2/#error-{sim_issue}",
                            "rel": "information_link",
                            "method": "GET"
                        }
                    ]
                }
                raise parse_and_log_paypal_error(sim_envelope, status_code=422)

            capture_id = f"CAP-{uuid.uuid4().hex[:10].upper()}"
            stored_order = self._orders_store.get(clean_order_id, {})
            currency = stored_order.get("currency", self.default_currency)
            amount = float(stored_order.get("amount", 64.88))
            now_str = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())

            result = {
                "id": clean_order_id,
                "status": "COMPLETED",
                "capture_id": capture_id,
                "gross_amount": amount,
                "amount": amount,
                "currency": currency,
                "create_time": now_str,
                "seller_protection_status": "ELIGIBLE",
                "payer": {
                    "name": {"given_name": "Sandbox", "surname": "Buyer"},
                    "email_address": "buyer-sandbox@personal.example.com",
                    "payer_id": f"PAYER-{uuid.uuid4().hex[:8].upper()}"
                },
                "purchase_units": [
                    {
                        "payments": {
                            "captures": [
                                {
                                    "id": capture_id,
                                    "status": "COMPLETED",
                                    "amount": {
                                        "currency_code": currency,
                                        "value": format_currency_amount(amount, currency)
                                    },
                                    "final_capture": True,
                                    "seller_protection": {
                                        "status": "ELIGIBLE"
                                    },
                                    "create_time": now_str
                                }
                            ]
                        }
                    }
                ],
                "mock": True
            }
            self._captured_orders[clean_order_id] = result
            if idempotency_key:
                self._idempotency_records[idempotency_key] = result
            return result

        token = await self.get_access_token()
        headers = {
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "Prefer": "return=representation"
        }
        if idempotency_key:
            headers["PayPal-Request-Id"] = idempotency_key

        if effective_mock:
            if effective_mock.startswith("{"):
                headers["PayPal-Mock-Response"] = effective_mock
            else:
                headers["PayPal-Mock-Response"] = json.dumps({"mock_application_codes": effective_mock})

        async with httpx.AsyncClient(timeout=10.0) as client:
            try:
                resp = await client.post(
                    f"{self.base_url}/v2/checkout/orders/{clean_order_id}/capture",
                    headers=headers,
                    json={}
                )
                resp.raise_for_status()
                result = resp.json()

                # STRICT STATUS CHECK: Never treat APPROVED as completed!
                order_status = result.get("status")
                if order_status != "COMPLETED":
                    logger.error(
                        f"PayPal capture order '{clean_order_id}' returned non-COMPLETED status: '{order_status}'"
                    )
                    raise RuntimeError(
                        f"PayPal capture incomplete: order status is '{order_status}', expected 'COMPLETED'. "
                        "Never treating APPROVED or pending as completed."
                    )

                # Extract captures from payments
                purchase_units = result.get("purchase_units", [])
                captures = (
                    purchase_units[0].get("payments", {}).get("captures", [])
                    if purchase_units else []
                )
                if not captures:
                    raise RuntimeError(
                        f"PayPal order '{clean_order_id}' has COMPLETED order status but missing capture record."
                    )

                capture = captures[0]
                capture_status = capture.get("status")
                if capture_status != "COMPLETED":
                    raise RuntimeError(
                        f"PayPal capture record for order '{clean_order_id}' has status '{capture_status}', expected 'COMPLETED'."
                    )

                # Extract capture details
                capture_id = capture.get("id")
                capture_amount_val = float(capture.get("amount", {}).get("value", "0.0"))
                capture_currency = capture.get("amount", {}).get("currency_code", self.default_currency)
                create_time = capture.get("create_time") or time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
                seller_protection = capture.get("seller_protection", {}).get("status", "NOT_ELIGIBLE")

                seller_breakdown = capture.get("seller_receivable_breakdown", {})
                gross_amount = (
                    float(seller_breakdown.get("gross_amount", {}).get("value"))
                    if "gross_amount" in seller_breakdown
                    else capture_amount_val
                )

                # Attach top-level convenient fields for consumers
                result["capture_id"] = capture_id
                result["gross_amount"] = gross_amount
                result["amount"] = capture_amount_val
                result["currency"] = capture_currency
                result["create_time"] = create_time
                result["seller_protection_status"] = seller_protection

                self._captured_orders[clean_order_id] = result
                if idempotency_key:
                    self._idempotency_records[idempotency_key] = result
                return result
            except httpx.HTTPStatusError as e:
                try:
                    error_json = e.response.json()
                    raise parse_and_log_paypal_error(error_json, e.response.status_code) from e
                except (ValueError, KeyError):
                    logger.error("PayPal capture failed with non-JSON response (status %d)", e.response.status_code)
                    raise PayPalError(f"PayPal capture failed: {e.response.text}", status_code=e.response.status_code) from e
