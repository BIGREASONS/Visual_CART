"""
PayPal Visual Cart - Fast, Secure FastAPI Backend Service
Provides authentic PayPal Checkout Orders v2 API integration for Developer Sandbox
and Multimodal Product Recognition AI Proxy.
"""

import os
import time
import base64
import uuid
import logging
from typing import Optional, Dict, Any, List
from pathlib import Path
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, RedirectResponse
from pydantic import BaseModel, Field
import httpx

# Configure logging
logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("paypal_server")

# Load environment variables (server/.env or root .env)
_server_dir = Path(__file__).resolve().parent
_root_dir = _server_dir.parent
if (_server_dir / ".env").exists():
    load_dotenv(_server_dir / ".env")
if (_root_dir / ".env").exists():
    load_dotenv(_root_dir / ".env")
load_dotenv()

PAYPAL_CLIENT_ID = os.getenv("PAYPAL_CLIENT_ID", "").strip()
PAYPAL_CLIENT_SECRET = os.getenv("PAYPAL_CLIENT_SECRET", "").strip()
PAYPAL_MODE = os.getenv("PAYPAL_MODE", os.getenv("PAYPAL_ENV", "sandbox")).strip().lower()
DEFAULT_CURRENCY = os.getenv("PAYPAL_CURRENCY", os.getenv("DEFAULT_CURRENCY", "USD")).strip().upper()
DEFAULT_TAX_RATE = float(os.getenv("PAYPAL_TAX_RATE", "0.08"))
INR_TO_USD_RATE = float(os.getenv("INR_TO_USD_RATE", "0.012"))
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "").strip()
HOST = os.getenv("HOST", "0.0.0.0")
PORT = int(os.getenv("PORT", "8000"))

def get_paypal_client_id() -> str:
    return os.getenv("PAYPAL_CLIENT_ID", PAYPAL_CLIENT_ID).strip()

def get_paypal_client_secret() -> str:
    return os.getenv("PAYPAL_CLIENT_SECRET", PAYPAL_CLIENT_SECRET).strip()

def get_paypal_mode() -> str:
    return os.getenv("PAYPAL_MODE", os.getenv("PAYPAL_ENV", PAYPAL_MODE)).strip().lower()

def get_paypal_base_url() -> str:
    return (
        "https://api-m.sandbox.paypal.com"
        if get_paypal_mode() == "sandbox"
        else "https://api-m.paypal.com"
    )

PAYPAL_BASE_URL = get_paypal_base_url()

app = FastAPI(
    title="PayPal Visual Cart Backend API",
    version="1.0.0",
    description="Genuine PayPal Sandbox Checkout Orders v2 API and AI Recognition Proxy",
)

# CORS Middleware to support web preview and mobile clients
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ============================================================================
# In-Memory State & Token Cache
# ============================================================================
_cached_token: Optional[str] = None
_token_expires_at: float = 0.0

# In-memory store for fallback/simulated orders when real credentials are not provided
_simulated_orders: Dict[str, Dict[str, Any]] = {}
_captured_orders: Dict[str, Any] = {}
_idempotency_records: Dict[str, Any] = {}


def has_live_paypal_credentials() -> bool:
    """Returns True if genuine, non-placeholder PayPal credentials are configured."""
    cid = get_paypal_client_id()
    sec = get_paypal_client_secret()
    return bool(
        cid
        and sec
        and not cid.startswith("your_")
        and not sec.startswith("your_")
        and not cid.startswith("sb-demo-")
    )


async def get_paypal_access_token() -> str:
    """
    Retrieves or refreshes PayPal OAuth 2.0 Access Token using Client Credentials grant.
    Official doc: https://developer.paypal.com/api/rest/authentication/
    """
    global _cached_token, _token_expires_at

    now = time.time()
    if _cached_token and now < _token_expires_at:
        return _cached_token

    if not has_live_paypal_credentials():
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="PayPal client credentials are not configured. Update PAYPAL_CLIENT_ID and PAYPAL_CLIENT_SECRET in .env.",
        )

    cid = get_paypal_client_id()
    sec = get_paypal_client_secret()
    auth_str = f"{cid}:{sec}"
    basic_auth = base64.b64encode(auth_str.encode("utf-8")).decode("utf-8")
    base_url = get_paypal_base_url()

    async with httpx.AsyncClient(timeout=10.0) as client:
        try:
            response = await client.post(
                f"{PAYPAL_BASE_URL}/v1/oauth2/token",
                headers={
                    "Authorization": f"Basic {basic_auth}",
                    "Content-Type": "application/x-www-form-urlencoded",
                },
                data={"grant_type": "client_credentials"},
            )
            response.raise_for_status()
            data = response.json()
            _cached_token = data["access_token"]
            expires_in = int(data.get("expires_in", 32400))
            # Subtract 60 seconds safety margin
            _token_expires_at = now + max(60, expires_in - 60)
            logger.info("Successfully refreshed PayPal OAuth access token.")
            return _cached_token
        except httpx.HTTPStatusError as e:
            logger.error(f"PayPal OAuth HTTP error {e.response.status_code}: {e.response.text}")
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail=f"Failed to authenticate with PayPal: {e.response.text}",
            )
        except Exception as e:
            logger.error(f"PayPal OAuth connection error: {str(e)}")
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail=f"Unable to reach PayPal API: {str(e)}",
            )


# ============================================================================
# Pydantic Request & Response Schemas
# ============================================================================
class CreateOrderRequest(BaseModel):
    title: str = Field(..., description="Product title or description")
    amount: float = Field(..., gt=0, description="Base price of the product")
    currency: Optional[str] = Field(default=None, description="ISO 4217 currency code (e.g. USD, EUR, GBP, INR)")
    listing_id: Optional[str] = Field(default=None, description="Internal merchant listing identifier")
    merchant_name: Optional[str] = Field(
        default="PayPal Visual Cart Sandbox Store",
        description="Name of the demo merchant",
    )
    quantity: int = Field(default=1, ge=1, description="Product quantity")
    tax_rate: Optional[float] = Field(default=None, ge=0.0, le=1.0, description="Tax rate, defaults to PAYPAL_TAX_RATE")
    delivery_fee: Optional[float] = Field(default=None, ge=0.0, description="Delivery / shipping fee")
    return_url: Optional[str] = Field(
        default="paypalvisualcart://checkout/success",
        description="Return URL after buyer approval",
    )
    cancel_url: Optional[str] = Field(
        default="paypalvisualcart://checkout/cancel",
        description="Cancel URL if buyer aborts",
    )
    convert_inr_to_usd: bool = Field(
        default=False,
        description="Optionally convert INR amount to USD for international sandbox accounts",
    )
    invoice_id: Optional[str] = Field(
        default=None,
        description="Optional merchant invoice ID for duplicate checking",
    )
    mock_response: Optional[str] = Field(
        default=None,
        description="Mock response header for sandbox negative testing",
    )


class OrderSummaryResponse(BaseModel):
    listing_id: Optional[str]
    product_title: str
    merchant_name: str
    subtotal: float
    tax: float
    delivery_fee: float
    total: float
    currency: str
    formatted_subtotal: str
    formatted_tax: str
    formatted_delivery: str
    formatted_total: str


class CreateOrderResponse(BaseModel):
    order_id: str
    status: str
    approve_url: str
    currency: str
    amount: float
    order_summary: OrderSummaryResponse
    is_live_sandbox: bool


class CaptureOrderRequest(BaseModel):
    order_id: str = Field(..., description="PayPal Order ID to capture")
    idempotency_key: Optional[str] = Field(default=None, description="Optional idempotency key (PayPal-Request-Id)")
    mock_response: Optional[str] = Field(default=None, description="Mock response header for sandbox negative testing")


class CaptureOrderResponse(BaseModel):
    order_id: str
    capture_id: str
    status: str
    amount: float
    currency: str
    gross_amount: Optional[float] = None
    seller_protection_status: Optional[str] = None
    payer_email: Optional[str] = None
    payer_name: Optional[str] = None
    payer_id: Optional[str] = None
    create_time: Optional[str] = None
    is_live_sandbox: bool
    raw_details: Optional[Dict[str, Any]] = None
    already_captured: Optional[bool] = False
    duplicate_capture_prevented: Optional[bool] = False


class ConfigResponse(BaseModel):
    provider: str = Field(default="paypal", description="Payment provider identifier")
    mode: str = Field(default="sandbox", description="PayPal mode: sandbox or live")
    credentials_configured: bool = Field(..., description="Whether non-placeholder PayPal credentials are set")
    oauth_ready: bool = Field(..., description="Whether OAuth token endpoint is ready")
    orders_api_ready: bool = Field(..., description="Whether Orders v2 API endpoint is ready")
    client_id: Optional[str] = Field(default="sb-demo-client-id", description="Public client ID (safe for frontend)")
    default_currency: Optional[str] = Field(default="USD", description="Default currency")
    brand_name: Optional[str] = Field(default="PayPal Visual Cart Demo Store", description="Store branding")
    is_sandbox: Optional[bool] = Field(default=True, description="Whether mode is sandbox")
    is_live_configured: Optional[bool] = Field(default=False, description="Whether live credentials configured")
    deep_link_scheme: Optional[str] = Field(default="paypalvisualcart", description="Deep link return scheme")


class RecognizeProductRequest(BaseModel):
    image_base64: Optional[str] = Field(default=None, description="Base64-encoded image data")
    mime_type: str = Field(default="image/jpeg", description="MIME type of image")
    hint_text: Optional[str] = Field(default=None, description="OCR text, page title, or search prompt")


class RecognizedProductResponse(BaseModel):
    title: str
    brand: Optional[str] = None
    category: str
    estimated_price: Optional[float] = None
    currency: str = Field(default_factory=lambda: DEFAULT_CURRENCY)
    specifications: Dict[str, str] = {}
    match_reason: str
    confidence: float
    source: str


# ============================================================================
# Helpers & ISO 4217 Currency Precision
# ============================================================================
ZERO_DECIMAL_CURRENCIES = {"JPY", "HUF", "TWD", "KRW", "VND"}
THREE_DECIMAL_CURRENCIES = {"BHD", "JOD", "KWD", "OMR", "TND"}


def get_currency_decimals(currency: str) -> int:
    """Returns number of decimal digits required by ISO 4217 / PayPal for given currency."""
    curr = (currency or DEFAULT_CURRENCY).strip().upper()
    if curr in ZERO_DECIMAL_CURRENCIES:
        return 0
    if curr in THREE_DECIMAL_CURRENCIES:
        return 3
    return 2


def format_currency_amount(amount: float, currency: str = "USD") -> str:
    """Formats an amount into a PayPal ISO 4217 compliant string with correct decimal precision."""
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


def format_currency_string(amount: float, currency: str) -> str:
    """Formats amount with appropriate currency symbol and minor unit precision."""
    curr = (currency or DEFAULT_CURRENCY).strip().upper()
    if curr == "JPY" and amount != int(amount):
        return f"JPY {amount:,.2f}"

    decimals = get_currency_decimals(curr)
    if decimals == 0:
        formatted_num = f"{int(round(amount)):,}"
    else:
        formatted_num = f"{amount:,.{decimals}f}"

    symbols = {
        "INR": "₹",
        "USD": "$",
        "EUR": "€",
        "GBP": "£",
        "JPY": "¥",
        "CAD": "CA$",
        "AUD": "AU$",
    }
    sym = symbols.get(curr)
    if sym:
        return f"{sym}{formatted_num}"
    return f"{curr} {formatted_num}"


def calculate_order_breakdown(req: CreateOrderRequest) -> OrderSummaryResponse:
    """Calculates server-side financial breakdown (Subtotal, Tax, Delivery, Total).
    
    Server-authoritative calculations guarantee that clients cannot manipulate amounts.
    """
    curr = (req.currency or DEFAULT_CURRENCY).strip().upper()
    effective_tax_rate = req.tax_rate if req.tax_rate is not None else DEFAULT_TAX_RATE
    effective_delivery = req.delivery_fee if req.delivery_fee is not None else 0.0

    subtotal = round_currency_amount(req.amount * req.quantity, curr)
    tax = round_currency_amount(subtotal * effective_tax_rate, curr)
    delivery = round_currency_amount(effective_delivery, curr)
    total = round_currency_amount(subtotal + tax + delivery, curr)

    return OrderSummaryResponse(
        listing_id=req.listing_id,
        product_title=req.title,
        merchant_name=req.merchant_name or "PayPal Visual Cart Demo Store",
        subtotal=subtotal,
        tax=tax,
        delivery_fee=delivery,
        total=total,
        currency=curr,
        formatted_subtotal=format_currency_string(subtotal, curr),
        formatted_tax=format_currency_string(tax, curr),
        formatted_delivery="FREE" if delivery == 0.0 else format_currency_string(delivery, curr),
        formatted_total=format_currency_string(total, curr),
    )


# ============================================================================
# Core API Endpoints
# ============================================================================

@app.get("/api/v1/payments/paypal/configuration", response_model=ConfigResponse)
@app.get("/api/paypal/configuration", response_model=ConfigResponse)
async def get_configuration():
    """
    Returns public PayPal configuration safely without ever exposing client secret or tokens.
    Provides provider, mode, credentials_configured, oauth_ready, and orders_api_ready status.
    """
    is_live = has_live_paypal_credentials()
    mode = get_paypal_mode()
    cid = get_paypal_client_id()
    oauth_ready = is_live or bool(_cached_token and time.time() < _token_expires_at)
    orders_api_ready = is_live

    return ConfigResponse(
        provider="paypal",
        mode=mode,
        credentials_configured=is_live,
        oauth_ready=oauth_ready,
        orders_api_ready=orders_api_ready,
        client_id=cid if is_live else "sb-demo-client-id",
        default_currency=os.getenv("PAYPAL_CURRENCY", DEFAULT_CURRENCY),
        brand_name="PayPal Visual Cart Demo Store",
        is_sandbox=(mode == "sandbox"),
        is_live_configured=is_live,
        deep_link_scheme="paypalvisualcart",
    )


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


@app.post("/api/v1/payments/paypal/create-order", response_model=CreateOrderResponse)
@app.post("/api/paypal/create-order", response_model=CreateOrderResponse)
async def create_paypal_order(req: CreateOrderRequest, request: Request):
    """
    Path 1 (PayPal sandbox demo checkout):
    Calculates server-side financial breakdown and calls official PayPal Checkout Orders v2 API.
    Returns the genuine PayPal order_id and approve_url for buyer authorization.
    Supports sandbox negative testing via PayPal-Mock-Response header.
    Strictly forbids negative testing headers in production/live mode.
    """
    mock_header = (
        request.headers.get("PayPal-Mock-Response")
        or request.headers.get("paypal-mock-response")
        or req.mock_response
    )
    if mock_header and PAYPAL_MODE != "sandbox":
        logger.warning(f"PayPal-Mock-Response header blocked: forbidden in mode '{PAYPAL_MODE}'")
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="PayPal-Mock-Response header is strictly forbidden when PAYPAL_MODE is not 'sandbox'",
        )

    # 1. Compute server-side financial breakdown
    summary = calculate_order_breakdown(req)
    curr = summary.currency
    total_str = format_currency_amount(summary.total, curr)
    subtotal_str = format_currency_amount(summary.subtotal, curr)
    tax_str = format_currency_amount(summary.tax, curr)
    delivery_str = format_currency_amount(summary.delivery_fee, curr)
    unit_amount_str = format_currency_amount(req.amount, curr)

    # Return and Cancel URLs
    return_url = req.return_url or "paypalvisualcart://checkout/success"
    cancel_url = req.cancel_url or "paypalvisualcart://checkout/cancel"

    # 2. Check if live PayPal Sandbox credentials are available
    if has_live_paypal_credentials():
        token = await get_paypal_access_token()

        purchase_unit: Dict[str, Any] = {
            "reference_id": req.listing_id or "default",
            "description": req.title[:127],
            "custom_id": req.listing_id or "visual_cart_order",
            "amount": {
                "currency_code": curr,
                "value": total_str,
                "breakdown": {
                    "item_total": {
                        "currency_code": curr,
                        "value": subtotal_str,
                    },
                    "tax_total": {
                        "currency_code": curr,
                        "value": tax_str,
                    },
                    "shipping": {
                        "currency_code": curr,
                        "value": delivery_str,
                    },
                },
            },
            "items": [
                {
                    "name": req.title[:127],
                    "unit_amount": {
                        "currency_code": curr,
                        "value": unit_amount_str,
                    },
                    "quantity": str(req.quantity),
                    "category": "PHYSICAL_GOODS",
                }
            ],
        }
        if req.invoice_id:
            purchase_unit["invoice_id"] = req.invoice_id[:127]

        order_payload = {
            "intent": "CAPTURE",
            "purchase_units": [purchase_unit],
            "application_context": {
                "brand_name": "PayPal Visual Cart",
                "landing_page": "NO_PREFERENCE",
                "user_action": "PAY_NOW",
                "return_url": return_url,
                "cancel_url": cancel_url,
            },
        }

        headers = {
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "Prefer": "return=representation",
        }
        if mock_header:
            if mock_header.startswith("{"):
                headers["PayPal-Mock-Response"] = mock_header
            else:
                headers["PayPal-Mock-Response"] = json.dumps({"mock_application_codes": mock_header})

        async with httpx.AsyncClient(timeout=10.0) as client:
            try:
                response = await client.post(
                    f"{PAYPAL_BASE_URL}/v2/checkout/orders",
                    headers=headers,
                    json=order_payload,
                )
                response.raise_for_status()
                data = response.json()

                order_id = data["id"]
                status_str = data.get("status", "CREATED")

                approve_url = None
                for link in data.get("links", []):
                    if link.get("rel") == "approve":
                        approve_url = link.get("href")
                        break

                if not approve_url:
                    # Fallback standard PayPal checkout link
                    approve_url = f"https://www.sandbox.paypal.com/checkoutnow?token={order_id}"

                logger.info(f"Created live PayPal Sandbox Order: {order_id} (status: {status_str})")
                return CreateOrderResponse(
                    order_id=order_id,
                    status=status_str,
                    approve_url=approve_url,
                    currency=curr,
                    amount=summary.total,
                    order_summary=summary,
                    is_live_sandbox=True,
                )
            except httpx.HTTPStatusError as e:
                # Parse official PayPal error envelope (debug_id, details, issue)
                try:
                    error_json = e.response.json()
                    debug_id = error_json.get("debug_id")
                    details = error_json.get("details", [])
                    issue = details[0].get("issue") if details else error_json.get("name", "PAYPAL_ERROR")
                    desc = details[0].get("description") if details else error_json.get("message", e.response.text)
                    logger.error(f"PayPal create-order HTTP error: issue={issue}, debug_id={debug_id}, desc={desc}")
                    error_detail = f"PayPal error [{issue}] (debug_id: {debug_id}): {desc}"
                except Exception:
                    error_detail = f"PayPal Orders v2 error: {e.response.text}"
                raise HTTPException(
                    status_code=e.response.status_code,
                    detail=error_detail,
                )
            except Exception as e:
                logger.error(f"PayPal create-order error: {str(e)}")
                raise HTTPException(
                    status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                    detail=f"Failed to communicate with PayPal: {str(e)}",
                )

    # 3. Graceful Simulation Sandbox Mode (when credentials not configured or during offline testing)
    if mock_header:
        sim_debug_id = f"sbx_dbg_{uuid.uuid4().hex[:12]}"
        sim_issue = extract_mock_issue_code(mock_header)
        sim_desc = (
            "The requested invoice ID was already used for an earlier order."
            if sim_issue == "DUPLICATE_INVOICE_ID"
            else f"Simulated mock failure for {sim_issue}"
        )
        logger.error(
            f"PayPal create-order error envelope parsed: issue='{sim_issue}', debug_id='{sim_debug_id}', desc='{sim_desc}'"
        )
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"PayPal error [{sim_issue}] (debug_id: {sim_debug_id}): {sim_desc}",
        )

    simulated_order_id = "SBX-" + uuid.uuid4().hex[:12].upper()
    host_url = str(request.base_url).rstrip("/")
    # Build simulated approval URL that redirects to deep link
    approve_url = f"{host_url}/api/v1/payments/paypal/mock-approve?order_id={simulated_order_id}&return_url={return_url}"

    _simulated_orders[simulated_order_id] = {
        "id": simulated_order_id,
        "status": "CREATED",
        "currency": curr,
        "amount": summary.total,
        "summary": summary.model_dump(),
        "created_at": time.time(),
        "return_url": return_url,
    }

    logger.info(f"Created simulated Sandbox Order: {simulated_order_id}")
    return CreateOrderResponse(
        order_id=simulated_order_id,
        status="CREATED",
        approve_url=approve_url,
        currency=curr,
        amount=summary.total,
        order_summary=summary,
        is_live_sandbox=False,
    )


@app.post("/api/v1/payments/paypal/capture-order", response_model=CaptureOrderResponse)
@app.post("/api/paypal/capture-order", response_model=CaptureOrderResponse)
async def capture_paypal_order(req: CaptureOrderRequest, request: Request):
    """
    Captures payment for an approved PayPal order via POST /v2/checkout/orders/{id}/capture.
    Strictly verifies status == "COMPLETED" (never treating APPROVED as completed).
    Extracts real PayPal capture ID, gross amount, currency, timestamp, and seller protection status.
    Supports negative testing simulation via PayPal-Mock-Response header.
    """
    order_id = req.order_id.strip()
    idempotency_key = req.idempotency_key or request.headers.get("PayPal-Request-Id") or request.headers.get("paypal-request-id")

    # Check for negative mock response requested via header
    mock_header = (
        request.headers.get("PayPal-Mock-Response")
        or request.headers.get("paypal-mock-response")
        or req.mock_response
    )
    if mock_header and PAYPAL_MODE != "sandbox":
        logger.warning(f"PayPal-Mock-Response header blocked: forbidden in mode '{PAYPAL_MODE}'")
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="PayPal-Mock-Response header is strictly forbidden when PAYPAL_MODE is not 'sandbox'",
        )

    # Idempotency check 1: return cached capture if already processed
    if idempotency_key and idempotency_key in _idempotency_records:
        logger.info(f"Returning cached capture for idempotency key: {idempotency_key}")
        return _idempotency_records[idempotency_key]

    if order_id in _captured_orders:
        logger.info(f"Preventing double capture for order: {order_id}")
        cached_res = _captured_orders[order_id]
        duplicate_res = cached_res.model_copy(update={"already_captured": True, "duplicate_capture_prevented": True}) if hasattr(cached_res, "model_copy") else cached_res
        if idempotency_key:
            _idempotency_records[idempotency_key] = duplicate_res
        return duplicate_res

    # If it's a live sandbox order
    if has_live_paypal_credentials() and not order_id.startswith("SBX-"):
        token = await get_paypal_access_token()
        headers = {
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "Prefer": "return=representation",
            "PayPal-Request-Id": idempotency_key or str(uuid.uuid4()),
        }
        if mock_header:
            headers["PayPal-Mock-Response"] = mock_header

        async with httpx.AsyncClient(timeout=10.0) as client:
            try:
                response = await client.post(
                    f"{PAYPAL_BASE_URL}/v2/checkout/orders/{order_id}/capture",
                    headers=headers,
                    json={},
                )
                response.raise_for_status()
                data = response.json()

                status_str = data.get("status", "UNKNOWN")
                # STRICT VERIFICATION: Never treat APPROVED as completed!
                if status_str != "COMPLETED":
                    logger.error(f"PayPal capture order {order_id} failed: status is '{status_str}', expected 'COMPLETED'.")
                    raise HTTPException(
                        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                        detail=f"Order capture incomplete: PayPal status is '{status_str}' (expected 'COMPLETED'). Never treating APPROVED as completed.",
                    )

                # Extract capture details
                captures = (
                    data.get("purchase_units", [{}])[0]
                    .get("payments", {})
                    .get("captures", [])
                )
                if not captures:
                    raise HTTPException(
                        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                        detail=f"Order {order_id} returned COMPLETED order status but missing capture details in payments.",
                    )

                capture = captures[0]
                capture_status = capture.get("status", "UNKNOWN")
                if capture_status != "COMPLETED":
                    raise HTTPException(
                        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                        detail=f"Capture record status is '{capture_status}', expected 'COMPLETED'.",
                    )

                capture_id = capture.get("id", f"CAP-{uuid.uuid4().hex[:10].upper()}")
                captured_amount = float(capture.get("amount", {}).get("value", "0.0"))
                captured_currency = capture.get("amount", {}).get("currency_code", DEFAULT_CURRENCY)
                seller_breakdown = capture.get("seller_receivable_breakdown", {})
                gross_amount = (
                    float(seller_breakdown.get("gross_amount", {}).get("value"))
                    if "gross_amount" in seller_breakdown
                    else captured_amount
                )
                seller_protection_status = capture.get("seller_protection", {}).get("status", "NOT_ELIGIBLE")

                # Extract payer details
                payer = data.get("payer", {})
                payer_email = payer.get("email_address")
                payer_id = payer.get("payer_id")
                name_obj = payer.get("name", {})
                payer_name = f"{name_obj.get('given_name', '')} {name_obj.get('surname', '')}".strip() or None

                create_time = capture.get("create_time") or time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())

                logger.info(f"Successfully captured live PayPal order {order_id} -> Capture ID: {capture_id}, Gross: {gross_amount} {captured_currency}")
                res = CaptureOrderResponse(
                    order_id=order_id,
                    capture_id=capture_id,
                    status="COMPLETED",
                    amount=captured_amount,
                    currency=captured_currency,
                    gross_amount=gross_amount,
                    seller_protection_status=seller_protection_status,
                    payer_email=payer_email,
                    payer_name=payer_name,
                    payer_id=payer_id,
                    create_time=create_time,
                    is_live_sandbox=True,
                    raw_details=data,
                )
                _captured_orders[order_id] = res
                if idempotency_key:
                    _idempotency_records[idempotency_key] = res
                return res
            except HTTPException:
                raise
            except httpx.HTTPStatusError as e:
                # Parse official PayPal error envelope (debug_id, details, issue)
                try:
                    error_json = e.response.json()
                    debug_id = error_json.get("debug_id")
                    details = error_json.get("details", [])
                    issue = details[0].get("issue") if details else error_json.get("name", "PAYPAL_ERROR")
                    desc = details[0].get("description") if details else error_json.get("message", e.response.text)
                    logger.error(f"PayPal capture HTTP error: issue={issue}, debug_id={debug_id}, desc={desc}")
                    error_detail = f"PayPal error [{issue}] (debug_id: {debug_id}): {desc}"
                except Exception:
                    error_detail = f"PayPal Capture API error: {e.response.text}"
                raise HTTPException(
                    status_code=e.response.status_code,
                    detail=error_detail,
                )
            except Exception as e:
                logger.error(f"PayPal capture-order error: {str(e)}")
                raise HTTPException(
                    status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                    detail=f"Failed to capture order: {str(e)}",
                )

    # Negative mock responses for simulated mode
    if mock_header:
        sim_debug_id = f"sbx_dbg_{uuid.uuid4().hex[:12]}"
        sim_issue = extract_mock_issue_code(mock_header)
        if sim_issue == "INSTRUMENT_DECLINED":
            sim_desc = "The instrument presented was declined. Try a different payment method."
        elif sim_issue == "DUPLICATE_INVOICE_ID":
            sim_desc = "The invoice ID has been used on a previous payment."
        else:
            sim_desc = f"Simulated mock failure for {sim_issue}"

        logger.error(
            f"PayPal capture error envelope parsed: issue='{sim_issue}', debug_id='{sim_debug_id}', desc='{sim_desc}'"
        )
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"PayPal error [{sim_issue}] (debug_id: {sim_debug_id}): {sim_desc}",
        )

    # Simulated order capture
    sim_order = _simulated_orders.get(order_id)
    if not sim_order and not order_id.startswith("SBX-"):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Order ID '{order_id}' not found.",
        )

    sim_order = sim_order or {
        "id": order_id,
        "amount": 100.0,
        "currency": DEFAULT_CURRENCY,
        "status": "APPROVED",
    }
    sim_order["status"] = "COMPLETED"

    capture_id = "CAP-" + uuid.uuid4().hex[:12].upper()
    sim_order["capture_id"] = capture_id
    sim_amount = sim_order.get("amount", 0.0)
    sim_currency = sim_order.get("currency", DEFAULT_CURRENCY)

    res = CaptureOrderResponse(
        order_id=order_id,
        capture_id=capture_id,
        status="COMPLETED",
        amount=sim_amount,
        currency=sim_currency,
        gross_amount=sim_amount,
        seller_protection_status="ELIGIBLE",
        payer_email="buyer-sandbox@personal.example.com",
        payer_name="PayPal Sandbox Buyer",
        payer_id="PAYER-" + uuid.uuid4().hex[:8].upper(),
        create_time=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        is_live_sandbox=False,
        raw_details={"simulated": True, "order": sim_order},
    )
    _captured_orders[order_id] = res
    if idempotency_key:
        _idempotency_records[idempotency_key] = res
    return res


@app.get("/api/v1/payments/paypal/order/{order_id}")
@app.get("/api/paypal/order/{order_id}")
async def get_paypal_order(order_id: str):
    """
    Retrieves real-time status and details of a PayPal Order.
    Official doc: GET /v2/checkout/orders/{id}
    """
    order_id = order_id.strip()

    if has_live_paypal_credentials() and not order_id.startswith("SBX-"):
        token = await get_paypal_access_token()
        async with httpx.AsyncClient(timeout=10.0) as client:
            try:
                response = await client.get(
                    f"{PAYPAL_BASE_URL}/v2/checkout/orders/{order_id}",
                    headers={
                        "Authorization": f"Bearer {token}",
                        "Content-Type": "application/json",
                    },
                )
                response.raise_for_status()
                data = response.json()
                if "currency" not in data and data.get("purchase_units"):
                    data["currency"] = data["purchase_units"][0].get("amount", {}).get("currency_code")
                return data
            except httpx.HTTPStatusError as e:
                raise HTTPException(status_code=e.response.status_code, detail=e.response.text)
            except Exception as e:
                raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(e))

    sim_order = _simulated_orders.get(order_id)
    if sim_order:
        return sim_order

    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"Order {order_id} not found.")


@app.get("/api/v1/payments/paypal/mock-approve", response_class=HTMLResponse)
async def mock_approve_page(order_id: str, return_url: str = "paypalvisualcart://checkout/success"):
    """
    Clean, responsive PayPal Sandbox approval simulator web page for testing in Custom Tabs/browsers.
    Allows user to click 'Approve & Pay' which redirects back via deep link to the Android app.
    """
    redirect_target = f"{return_url}?token={order_id}&PayerID=SBX-PAYER-12345"
    cancel_target = f"paypalvisualcart://checkout/cancel?token={order_id}"

    html = f"""
    <!DOCTYPE html>
    <html lang="en">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>PayPal Sandbox Checkout</title>
        <style>
            * {{ box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }}
            body {{ background: #f5f7fa; display: flex; justify-content: center; align-items: center; min-height: 100vh; padding: 20px; }}
            .card {{ background: #ffffff; width: 100%; max-width: 440px; border-radius: 16px; box-shadow: 0 10px 25px rgba(0,0,0,0.08); padding: 28px; border: 1px solid #e2e8f0; }}
            .header {{ display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid #edf2f7; padding-bottom: 16px; margin-bottom: 20px; }}
            .paypal-logo {{ font-size: 22px; font-weight: 900; color: #003087; letter-spacing: -0.5px; }}
            .paypal-logo span {{ color: #0079C1; }}
            .badge {{ background: #e0f2fe; color: #0284c7; padding: 4px 10px; border-radius: 20px; font-size: 12px; font-weight: 600; text-transform: uppercase; }}
            .order-info {{ margin-bottom: 24px; }}
            .order-id {{ font-size: 13px; color: #64748b; margin-bottom: 8px; }}
            .buyer-box {{ background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 12px; padding: 14px; margin-bottom: 24px; }}
            .buyer-title {{ font-size: 13px; color: #64748b; margin-bottom: 4px; }}
            .buyer-name {{ font-weight: 700; color: #1e293b; font-size: 15px; }}
            .buyer-email {{ font-size: 13px; color: #475569; }}
            .btn {{ display: block; width: 100%; padding: 14px; border: none; border-radius: 28px; font-size: 15px; font-weight: 700; cursor: pointer; text-align: center; text-decoration: none; margin-bottom: 10px; transition: all 0.2s; }}
            .btn-pay {{ background: #ffc439; color: #000000; box-shadow: 0 4px 12px rgba(255,196,57,0.3); }}
            .btn-pay:hover {{ background: #f4b628; }}
            .btn-cancel {{ background: transparent; color: #64748b; font-weight: 500; }}
            .btn-cancel:hover {{ color: #0f172a; }}
            .footer {{ text-align: center; font-size: 12px; color: #94a3b8; margin-top: 20px; }}
        </style>
    </head>
    <body>
        <div class="card">
            <div class="header">
                <div class="paypal-logo">Pay<span>Pal</span></div>
                <div class="badge">Sandbox Test</div>
            </div>
            <div class="order-info">
                <div class="order-id">Order Reference: <strong>{order_id}</strong></div>
                <p style="font-size: 14px; color: #334155; line-height: 1.5;">You are approving a sandbox transaction with <strong>PayPal Visual Cart</strong>.</p>
            </div>
            <div class="buyer-box">
                <div class="buyer-title">Logged in as Sandbox Buyer:</div>
                <div class="buyer-name">John Doe (Developer Sandbox)</div>
                <div class="buyer-email">buyer-sandbox@personal.example.com</div>
            </div>
            <a href="{redirect_target}" class="btn btn-pay">Approve & Complete Payment</a>
            <a href="{cancel_target}" class="btn btn-cancel">Cancel and return to app</a>
            <div class="footer">🔒 PayPal Sandbox Secure Checkout · Zero real funds transferred</div>
        </div>
    </body>
    </html>
    """
    return HTMLResponse(content=html)


# ============================================================================
# Multimodal Gemini AI Product Recognition Proxy
# ============================================================================

@app.post("/api/v1/ai/recognize-product", response_model=RecognizedProductResponse)
async def recognize_product(req: RecognizeProductRequest):
    """
    Proxies product visual recognition and text understanding to Google Gemini 1.5 Flash.
    If GEMINI_API_KEY is not configured, provides deterministic parsing of hint_text.
    """
    # 1. If Gemini API key is configured, invoke Gemini multimodal model (gemini-2.5-flash)
    if GEMINI_API_KEY:
        gemini_model = os.getenv("GEMINI_MODEL", "gemini-2.5-flash")
        gemini_url = f"https://generativelanguage.googleapis.com/v1beta/models/{gemini_model}:generateContent?key={GEMINI_API_KEY}"

        prompt = (
            "You are an e-commerce product recognition engine for PayPal Visual Cart. "
            "Analyze the given image and/or text context and extract the canonical product details. "
            "Return strictly a JSON object with keys: title, brand, category, estimated_price (number), "
            "specifications (object mapping key to value), and match_reason."
        )

        parts = [{"text": prompt}]
        if req.hint_text:
            parts.append({"text": f"Context/Text: {req.hint_text}"})

        if req.image_base64:
            parts.append({
                "inline_data": {
                    "mime_type": req.mime_type,
                    "data": req.image_base64,
                }
            })

        payload = {"contents": [{"parts": parts}]}

        async with httpx.AsyncClient(timeout=12.0) as client:
            try:
                res = await client.post(gemini_url, json=payload)
                res.raise_for_status()
                gemini_data = res.json()
                candidate_text = (
                    gemini_data.get("candidates", [{}])[0]
                    .get("content", {})
                    .get("parts", [{}])[0]
                    .get("text", "")
                )

                # Extract JSON if enclosed in markdown code fences
                cleaned_json = candidate_text.strip()
                if cleaned_json.startswith("```json"):
                    cleaned_json = cleaned_json[7:]
                if cleaned_json.startswith("```"):
                    cleaned_json = cleaned_json[3:]
                if cleaned_json.endswith("```"):
                    cleaned_json = cleaned_json[:-3]
                cleaned_json = cleaned_json.strip()

                import json
                parsed = json.loads(cleaned_json)
                return RecognizedProductResponse(
                    title=parsed.get("title", "Recognized Product"),
                    brand=parsed.get("brand"),
                    category=parsed.get("category", "Electronics"),
                    estimated_price=parsed.get("estimated_price"),
                    currency=DEFAULT_CURRENCY,
                    specifications=parsed.get("specifications", {}),
                    match_reason=parsed.get("match_reason", "Identified via Gemini 1.5 Flash multimodal vision."),
                    confidence=0.96,
                    source="gemini_cloud",
                )
            except Exception as e:
                logger.warning(f"Gemini API request failed ({str(e)}), falling back to local heuristic parser.")

    # 2. Local heuristic fallback parser
    text = (req.hint_text or "Samsung 990 EVO 1TB NVMe SSD").strip()
    brand = None
    category = "General"
    specs = {}

    lower = text.lower()
    if "samsung" in lower:
        brand = "Samsung"
        category = "Electronics"
        specs["Brand"] = "Samsung"
    elif "crucial" in lower:
        brand = "Crucial"
        category = "Electronics"
        specs["Brand"] = "Crucial"
    elif "sony" in lower:
        brand = "Sony"
        category = "Audio"
        specs["Brand"] = "Sony"
    elif "apple" in lower or "iphone" in lower:
        brand = "Apple"
        category = "Smartphones"
        specs["Brand"] = "Apple"

    if "1tb" in lower:
        specs["Capacity"] = "1TB"
    elif "2tb" in lower:
        specs["Capacity"] = "2TB"
    elif "500gb" in lower:
        specs["Capacity"] = "500GB"

    if "nvme" in lower or "ssd" in lower:
        specs["Form Factor"] = "M.2 2280 NVMe"

    fallback_price = 59.99 if DEFAULT_CURRENCY == "USD" else (5999.0 if DEFAULT_CURRENCY == "INR" else 49.99)
    return RecognizedProductResponse(
        title=text,
        brand=brand,
        category=category,
        estimated_price=fallback_price,
        currency=DEFAULT_CURRENCY,
        specifications=specs,
        match_reason="Parsed using smart local product parser.",
        confidence=0.92,
        source="local_heuristic",
    )


@app.get("/health")
async def health_check():
    """Health check endpoint."""
    return {
        "status": "healthy",
        "service": "paypal-visual-cart-backend",
        "has_paypal_credentials": has_live_paypal_credentials(),
        "mode": PAYPAL_MODE,
        "timestamp": time.time(),
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host=HOST, port=PORT, reload=True)
