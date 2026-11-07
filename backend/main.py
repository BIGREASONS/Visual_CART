"""FastAPI Backend Server for PayPal Visual Cart.

Provides unified gateway endpoints for:
1. Multimodal AI Product Understanding (Gemini 1.5 Flash / Pro proxy)
2. PayPal Orders v2 Sandbox Integration (create-order, capture-order)
3. Multi-Merchant discovery aggregator
"""

import os
from typing import Optional, Dict, Any, List
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field, field_validator
from dotenv import load_dotenv

from .paypal_client import PayPalClient
from .gemini_client import GeminiClient

load_dotenv()

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
    amount_value: str = Field(default="5499.00", description="Total order amount")
    currency_code: str = Field(default="USD", description="Currency (USD/INR/EUR)")
    item_name: str = Field(default="Visual Cart Item", description="Item description")
    return_url: str = Field(default="https://visualcart.paypal.com/checkout/return")
    cancel_url: str = Field(default="https://visualcart.paypal.com/checkout/cancel")

    @field_validator("amount_value")
    @classmethod
    def validate_amount(cls, v: str) -> str:
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
    return {
        "client_id": os.getenv("PAYPAL_CLIENT_ID", "sb-demo-client-id"),
        "mode": os.getenv("PAYPAL_MODE", "sandbox"),
        "default_currency": os.getenv("DEFAULT_CURRENCY", "INR"),
        "brand_name": "PayPal Visual Cart Demo Store",
        "is_sandbox": os.getenv("PAYPAL_MODE", "sandbox") == "sandbox",
        "is_live_configured": bool(os.getenv("PAYPAL_CLIENT_ID")),
        "deep_link_scheme": "paypalvisualcart"
    }

@app.post("/api/v1/payments/paypal/create-order")
@app.post("/api/paypal/create-order")
async def create_paypal_order(req: CreateOrderRequest):
    try:
        order = await paypal_client.create_order(
            amount_value=req.amount_value,
            currency_code=req.currency_code,
            item_name=req.item_name,
            return_url=req.return_url,
            cancel_url=req.cancel_url
        )
        return {
            "status": "success",
            "order": order
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"PayPal order creation failed: {str(e)}")

@app.post("/api/v1/payments/paypal/capture-order")
@app.post("/api/paypal/capture-order")
async def capture_paypal_order(req: CaptureOrderRequest):
    try:
        capture = await paypal_client.capture_order(
            order_id=req.order_id,
            idempotency_key=req.idempotency_key
        )
        return {
            "status": "success",
            "capture": capture
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"PayPal order capture failed: {str(e)}")

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
