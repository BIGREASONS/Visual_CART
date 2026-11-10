"""FastAPI Backend Server for PayPal Visual Cart.

Provides unified gateway endpoints for:
1. Multimodal AI Product Understanding (Gemini 1.5 Flash / Pro proxy)
2. PayPal Orders v2 Sandbox Integration (create-order, capture-order)
3. Multi-Merchant discovery aggregator
"""

import os
from typing import Optional, Dict, Any, List
from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field, field_validator
from pathlib import Path
from dotenv import load_dotenv

# Load local backend/.env or root .env
_backend_dir = Path(__file__).resolve().parent
_root_dir = _backend_dir.parent
if (_backend_dir / ".env").exists():
    load_dotenv(_backend_dir / ".env")
if (_root_dir / ".env").exists():
    load_dotenv(_root_dir / ".env")
load_dotenv()

from .paypal_client import PayPalClient, PayPalError
from .gemini_client import GeminiClient

app = FastAPI(
    title="PayPal Visual Cart Gateway API",
    description="Multimodal AI shopping backend integrating Gemini 1.5 and PayPal Orders v2 API",
    version="1.0.0"
)

# CORS configuration
origins = os.getenv("CORS_ORIGINS", "*").split(",")
app.add_middleware(
    CORSMiddleware,
    allow_origins=origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

paypal_client = PayPalClient()
gemini_client = GeminiClient()

# Request & Response Models
class RecognizeRequest(BaseModel):
    text: Optional[str] = Field(default=None, description="Raw OCR or text snippet")
    context_text: Optional[str] = Field(default=None, description="Contextual text")
    image_base64: Optional[str] = Field(default=None, description="Base64-encoded image")
    image_url: Optional[str] = Field(default=None, description="Image URL or URI")
    mime_type: str = Field(default="image/jpeg", description="MIME type of base64 image")
    model: Optional[str] = Field(default=None, description="Requested Gemini model")

class CreateOrderRequest(BaseModel):
    amount_value: Optional[str] = Field(default="64.88", description="Total order amount")
    currency_code: str = Field(default="USD", description="Currency (USD/EUR/GBP/etc.)")
    item_name: str = Field(default="Visual Cart Item", description="Item description")
    quantity: int = Field(default=1, ge=1, description="Product quantity")
    unit_price: Optional[float] = Field(default=None, gt=0, description="Unit base price")
    tax_rate: Optional[float] = Field(default=None, ge=0.0, le=1.0, description="Configurable tax rate")
    shipping_fee: Optional[float] = Field(default=None, ge=0.0, description="Shipping fee")
    return_url: str = Field(default="https://visualcart.paypal.com/checkout/return")
    cancel_url: str = Field(default="https://visualcart.paypal.com/checkout/cancel")
    invoice_id: Optional[str] = Field(default=None, description="Optional merchant invoice ID")
    mock_response: Optional[str] = Field(default=None, description="Mock response header for sandbox negative testing")

    @field_validator("amount_value")
    @classmethod
    def validate_amount(cls, v: Optional[str]) -> Optional[str]:
        if v is None:
            return None
        clean = v.strip()
        try:
            val = float(clean)
            if val <= 0.0 or not (0.01 <= val <= 10000000.0):
                raise ValueError("Amount must be a positive number greater than 0")
        except ValueError:
            raise ValueError("Invalid decimal amount value")
        return f"{val:.2f}"

class CaptureOrderRequest(BaseModel):
    order_id: str = Field(min_length=3, max_length=128, description="PayPal order ID returned from create-order")
    idempotency_key: Optional[str] = Field(default=None, max_length=128, description="Client idempotency key")
    mock_response: Optional[str] = Field(default=None, description="Mock response header for sandbox negative testing")

@app.get("/")
def read_root():
    return {
        "service": "PayPal Visual Cart API",
        "status": "online",
        "paypal_configured": paypal_client.is_configured,
        "gemini_configured": gemini_client.is_configured,
        "version": "1.0.0"
    }

@app.get("/health")
def health_check():
    return {
        "status": "healthy",
        "environment": os.getenv("PAYPAL_ENV", "sandbox"),
        "paypal_base_url": paypal_client.base_url,
        "gemini_model": gemini_client.model_name
    }

@app.post("/api/v1/ai/recognize-product")
async def recognize_product_v1(req: RecognizeRequest):
    """Primary multimodal recognition gateway keeping Gemini API keys secure server-side.
    Returns structured JSON with brand, product_name, category, colour, model, specs, and confidence.
    """
    try:
        effective_text = req.text or req.context_text
        result = await gemini_client.understand_product(
            text=effective_text,
            image_base64=req.image_base64,
            mime_type=req.mime_type
        )
        return {
            "status": "success",
            "data": result,
            **result
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Multimodal recognition failed: {str(e)}")

@app.post("/api/ai/recognize")
async def recognize_product(req: RecognizeRequest):
    try:
        effective_text = req.text or req.context_text
        result = await gemini_client.understand_product(
            text=effective_text,
            image_base64=req.image_base64,
            mime_type=req.mime_type
        )
        return {
            "status": "success",
            "data": result,
            **result
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Multimodal recognition failed: {str(e)}")

@app.get("/api/v1/payments/paypal/configuration")
@app.get("/api/paypal/configuration")
async def get_configuration():
    configured = paypal_client.is_configured
    mode = os.getenv("PAYPAL_MODE", os.getenv("PAYPAL_ENV", "sandbox")).strip().lower()
    cid = (paypal_client.client_id or os.getenv("PAYPAL_CLIENT_ID", "")).strip()
    currency = os.getenv("PAYPAL_CURRENCY", os.getenv("DEFAULT_CURRENCY", "USD")).strip().upper()

    return {
        "provider": "paypal",
        "mode": mode,
        "credentials_configured": configured,
        "oauth_ready": configured,
        "orders_api_ready": configured,
        "client_id": cid if configured else "sb-demo-client-id",
        "default_currency": currency,
        "brand_name": "PayPal Visual Cart Demo Store",
        "is_sandbox": mode == "sandbox",
        "is_live_configured": configured,
        "deep_link_scheme": "paypalvisualcart",
    }

@app.post("/api/v1/payments/paypal/create-order")
@app.post("/api/paypal/create-order")
async def create_paypal_order(req: CreateOrderRequest, request: Request):
    mock_header = (
        request.headers.get("PayPal-Mock-Response")
        or request.headers.get("paypal-mock-response")
        or req.mock_response
    )
    mode = os.getenv("PAYPAL_MODE", os.getenv("PAYPAL_ENV", "sandbox")).strip().lower()
    if mock_header and mode != "sandbox":
        raise HTTPException(
            status_code=400,
            detail="PayPal-Mock-Response header is strictly forbidden when PAYPAL_MODE is not 'sandbox'"
        )

    try:
        order = await paypal_client.create_order(
            amount_value=req.amount_value,
            currency_code=req.currency_code,
            item_name=req.item_name,
            return_url=req.return_url,
            cancel_url=req.cancel_url,
            quantity=req.quantity,
            unit_price=req.unit_price,
            tax_rate=req.tax_rate,
            shipping_fee=req.shipping_fee,
            invoice_id=req.invoice_id,
            mock_response=mock_header,
        )
        return {
            "status": "success",
            "order": order
        }
    except PayPalError as e:
        raise HTTPException(
            status_code=e.status_code,
            detail=f"PayPal error [{e.issue}] (debug_id: {e.debug_id}): {e.description}"
        )
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"PayPal order creation failed: {str(e)}")

@app.post("/api/v1/payments/paypal/capture-order")
@app.post("/api/paypal/capture-order")
async def capture_paypal_order(req: CaptureOrderRequest, request: Request):
    mock_header = (
        request.headers.get("PayPal-Mock-Response")
        or request.headers.get("paypal-mock-response")
        or req.mock_response
    )
    mode = os.getenv("PAYPAL_MODE", os.getenv("PAYPAL_ENV", "sandbox")).strip().lower()
    if mock_header and mode != "sandbox":
        raise HTTPException(
            status_code=400,
            detail="PayPal-Mock-Response header is strictly forbidden when PAYPAL_MODE is not 'sandbox'"
        )

    idempotency_key = (
        req.idempotency_key
        or request.headers.get("PayPal-Request-Id")
        or request.headers.get("paypal-request-id")
    )

    try:
        capture = await paypal_client.capture_order(
            order_id=req.order_id,
            idempotency_key=idempotency_key,
            mock_response=mock_header
        )
        return {
            "status": "success",
            "capture": capture
        }
    except PayPalError as e:
        raise HTTPException(
            status_code=e.status_code,
            detail=f"PayPal error [{e.issue}] (debug_id: {e.debug_id}): {e.description}"
        )
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        status_code = 422 if "INSTRUMENT_DECLINED" in str(e) or "DUPLICATE_INVOICE_ID" in str(e) or "expected 'COMPLETED'" in str(e) else 500
        raise HTTPException(status_code=status_code, detail=f"PayPal order capture failed: {str(e)}")

@app.get("/api/v1/payments/paypal/order/{order_id}")
@app.get("/api/paypal/order/{order_id}")
async def get_paypal_order_endpoint(order_id: str):
    """Retrieves PayPal order status and details (GET /v2/checkout/orders/{id})."""
    try:
        order = await paypal_client.get_order_details(order_id=order_id)
        return {
            "status": "success",
            "order": order
        }
    except Exception as e:
        raise HTTPException(status_code=404, detail=f"PayPal order retrieval failed: {str(e)}")

@app.get("/api/merchants/search")
async def search_merchants(q: str):
    """Aggregates simulated listings across supported merchants."""
    query = q.lower()
    base_price = 5499.0 if "ssd" in query or "crucial" in query else 26990.0
    return {
        "query": q,
        "listings": [
            {
                "merchant": "Flipkart",
                "price": base_price,
                "originalPrice": base_price + 600,
                "status": "In Stock",
                "delivery": "Tomorrow",
                "isLowest": True,
                "url": f"https://www.flipkart.com/search?q={q}"
            },
            {
                "merchant": "Amazon India",
                "price": base_price + 300,
                "originalPrice": base_price + 600,
                "status": "In Stock",
                "delivery": "2 Days",
                "isLowest": False,
                "url": f"https://www.amazon.in/s?k={q}"
            },
            {
                "merchant": "Croma",
                "price": base_price + 300,
                "originalPrice": base_price + 500,
                "status": "In Stock",
                "delivery": "3 Days",
                "isLowest": False,
                "url": f"https://www.croma.com/searchB?q={q}"
            }
        ]
    }
