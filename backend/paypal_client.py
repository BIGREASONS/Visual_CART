"""PayPal Orders v2 API Client.

Handles OAuth 2.0 Client Credentials token exchange and order lifecycle
(create-order and capture-order) against the PayPal Sandbox environment.
"""

import os
import base64
import time
from typing import Dict, Any, Optional
import httpx

class PayPalClient:
    def __init__(
        self,
        client_id: Optional[str] = None,
        client_secret: Optional[str] = None,
        base_url: Optional[str] = None
    ):
        self.client_id = client_id or os.getenv("PAYPAL_CLIENT_ID", "")
        self.client_secret = client_secret or os.getenv("PAYPAL_CLIENT_SECRET", "")
        self.base_url = (base_url or os.getenv("PAYPAL_BASE_URL", "https://api-m.sandbox.paypal.com")).rstrip("/")
        self._cached_token: Optional[str] = None
        self._token_expires_at: float = 0
        self._captured_orders: Dict[str, Dict[str, Any]] = {}
        self._idempotency_records: Dict[str, Dict[str, Any]] = {}

    @property
    def is_configured(self) -> bool:
        return bool(self.client_id and self.client_secret)

    async def get_access_token(self) -> str:
        """Obtains or reuses a valid OAuth 2.0 bearer token using Client Credentials grant."""
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

    async def create_order(
        self,
        amount_value: str,
        currency_code: str = "USD",
        item_name: str = "Visual Cart Item",
        return_url: str = "https://example.com/checkout/return",
        cancel_url: str = "https://example.com/checkout/cancel"
    ) -> Dict[str, Any]:
        """Creates an order using PayPal Orders v2 API (POST /v2/checkout/orders)."""
        if not self.is_configured:
            import uuid
            order_id = f"SBX-ORD-{uuid.uuid4().hex[:12].upper()}"
            return {
                "id": order_id,
                "status": "CREATED",
                "links": [
                    {
                        "href": f"https://www.sandbox.paypal.com/checkoutnow?token={order_id}",
                        "rel": "approve",
                        "method": "GET"
                    }
                ],
                "mock": True
            }

        token = await self.get_access_token()
        headers = {
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "Prefer": "return=representation"
        }

        payload = {
            "intent": "CAPTURE",
            "purchase_units": [
                {
                    "reference_id": "default",
                    "description": item_name[:127],
                    "amount": {
                        "currency_code": currency_code,
                        "value": amount_value
                    }
                }
            ],
            "application_context": {
                "brand_name": "PayPal Visual Cart",
                "landing_page": "NO_PREFERENCE",
                "user_action": "PAY_NOW",
                "return_url": return_url,
                "cancel_url": cancel_url
            }
        }

        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(f"{self.base_url}/v2/checkout/orders", headers=headers, json=payload)
            resp.raise_for_status()
            return resp.json()

    async def capture_order(
        self,
        order_id: str,
        idempotency_key: Optional[str] = None
    ) -> Dict[str, Any]:
        """Captures an approved PayPal order (POST /v2/checkout/orders/{id}/capture).
        
        Guarantees payment idempotency and prevents double captures on the same order.
        """
        if not order_id or not order_id.strip():
            raise ValueError("Order ID cannot be blank")

        clean_order_id = order_id.strip()

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

        if not self.is_configured:
            import uuid
            capture_id = f"CAP-{uuid.uuid4().hex[:10].upper()}"
            result = {
                "id": clean_order_id,
                "status": "COMPLETED",
                "purchase_units": [
                    {
                        "payments": {
                            "captures": [
                                {
                                    "id": capture_id,
                                    "status": "COMPLETED",
                                    "final_capture": True
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

        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(f"{self.base_url}/v2/checkout/orders/{clean_order_id}/capture", headers=headers, json={})
            resp.raise_for_status()
            result = resp.json()
            self._captured_orders[clean_order_id] = result
            if idempotency_key:
                self._idempotency_records[idempotency_key] = result
            return result
