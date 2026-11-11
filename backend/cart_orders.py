"""Server-Authoritative Multi-Item Universal Cart Calculation & PayPal Orders v2 Builder.

Uses exact Decimal rounding to ensure line items sum identically to PayPal Orders v2
amount.breakdown.item_total (preventing ITEM_TOTAL_MISMATCH 422 errors).
"""

from __future__ import annotations

from collections import defaultdict
from decimal import Decimal, ROUND_HALF_UP
from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field

from .channel3_client import _get_currency_decimals


def round_decimal_currency(value: Decimal, currency: str) -> Decimal:
    decimals = _get_currency_decimals(currency)
    quantum = Decimal("1") if decimals == 0 else Decimal(10) ** (-decimals)
    return value.quantize(quantum, rounding=ROUND_HALF_UP)


def format_decimal_currency(value: Decimal, currency: str) -> str:
    rounded = round_decimal_currency(value, currency)
    decimals = _get_currency_decimals(currency)
    if decimals == 0:
        return str(int(rounded))
    return f"{rounded:.{decimals}f}"


class CartOrderItem(BaseModel):
    title: str = Field(..., min_length=1, max_length=256)
    unit_price: float = Field(..., gt=0)
    quantity: int = Field(default=1, ge=1, le=99)
    currency: str = Field(default="USD", min_length=3, max_length=6)
    merchant_name: str = Field(default="Visual Cart Sandbox Store", max_length=128)
    listing_id: Optional[str] = Field(default=None, max_length=128)
    product_id: Optional[str] = Field(default=None, max_length=128)
    product_url: Optional[str] = Field(default=None, max_length=1024)
    payee_merchant_id: Optional[str] = Field(default=None, max_length=64)
    offer_source: Optional[str] = Field(default=None, max_length=64)


class CreateCartOrderRequest(BaseModel):
    items: List[CartOrderItem] = Field(..., min_length=1, max_length=25)
    currency: Optional[str] = Field(default=None, description="Target settlement currency (e.g. USD)")
    tax_rate: Optional[float] = Field(default=None, ge=0.0, le=1.0)
    delivery_fee: Optional[float] = Field(default=None, ge=0.0)
    convert_inr_to_usd: bool = Field(default=False)
    inr_to_usd_rate: Optional[float] = Field(default=None, gt=0.0, le=1.0)
    return_url: str = Field(default="paypalvisualcart://checkout/success")
    cancel_url: str = Field(default="paypalvisualcart://checkout/cancel")
    invoice_id: Optional[str] = Field(default=None, max_length=127)
    mock_response: Optional[str] = Field(default=None)


def build_cart_paypal_order_payload(
    req: CreateCartOrderRequest,
    default_currency: str = "USD",
    default_tax_rate: float = 0.08,
    default_inr_to_usd_rate: float = 0.012,
) -> Dict[str, Any]:
    """Builds exact PayPal Orders v2 purchase_units and summary breakdown for a multi-item cart.

    - Supports explicit INR -> USD conversion for US Sandbox checkout when convert_inr_to_usd=True.
    - Rejects silently mixing incompatible currencies (e.g. USD + EUR) in one PayPal order.
    - Separates distinct PayPal payee merchant IDs into multiple purchase_units when onboarded,
      or consolidates mixed un-onboarded external retailers into one Sandbox Marketplace purchase unit
      carrying every line item in PayPal's `items` list.
    """
    raw_target = (req.currency or "").strip().upper()
    item_currencies = {item.currency.strip().upper() for item in req.items}

    if not raw_target:
        if req.convert_inr_to_usd and "INR" in item_currencies:
            target_currency = "USD"
        elif len(item_currencies) == 1:
            target_currency = next(iter(item_currencies))
        else:
            target_currency = default_currency.strip().upper() or "USD"
    else:
        target_currency = raw_target

    rate = Decimal(str(req.inr_to_usd_rate or default_inr_to_usd_rate))
    tax_rate = Decimal(str(req.tax_rate if req.tax_rate is not None else default_tax_rate))
    total_delivery = round_decimal_currency(Decimal(str(req.delivery_fee or 0.0)), target_currency)

    normalized_lines: List[Dict[str, Any]] = []
    for item in req.items:
        src_curr = item.currency.strip().upper()
        unit_dec = Decimal(str(item.unit_price))
        if src_curr != target_currency:
            if src_curr == "INR" and target_currency == "USD" and req.convert_inr_to_usd:
                unit_dec = max(Decimal("0.01"), round_decimal_currency(unit_dec * rate, target_currency))
            else:
                raise ValueError(
                    f"Cannot combine item in '{src_curr}' into '{target_currency}' PayPal checkout "
                    f"without explicit currency matching or conversion."
                )
        else:
            unit_dec = round_decimal_currency(unit_dec, target_currency)

        line_subtotal = round_decimal_currency(unit_dec * Decimal(item.quantity), target_currency)
        normalized_lines.append({
            "item": item,
            "unit_dec": unit_dec,
            "line_subtotal": line_subtotal,
        })

    # Group purchase units by PayPal payee merchant_id when present; otherwise single consolidated cart unit
    distinct_payees = {
        (line["item"].payee_merchant_id or "").strip()
        for line in normalized_lines
        if (line["item"].payee_merchant_id or "").strip()
    }
    if len(distinct_payees) >= 2 and all((line["item"].payee_merchant_id or "").strip() for line in normalized_lines):
        grouped: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
        for line in normalized_lines:
            grouped[line["item"].payee_merchant_id.strip()].append(line)
        unit_groups = list(grouped.items())
    else:
        unit_groups = [("visual_cart_universal", normalized_lines)]

    purchase_units: List[Dict[str, Any]] = []
    grand_subtotal = Decimal("0")
    grand_tax = Decimal("0")
    grand_shipping = Decimal("0")
    grand_total = Decimal("0")

    for idx, (group_key, lines) in enumerate(unit_groups):
        unit_sub = sum((l["line_subtotal"] for l in lines), Decimal("0"))
        unit_sub = round_decimal_currency(unit_sub, target_currency)
        unit_tax = round_decimal_currency(unit_sub * tax_rate, target_currency)
        unit_ship = total_delivery if idx == 0 else Decimal("0")
        unit_total = round_decimal_currency(unit_sub + unit_tax + unit_ship, target_currency)

        grand_subtotal += unit_sub
        grand_tax += unit_tax
        grand_shipping += unit_ship
        grand_total += unit_total

        paypal_items = []
        merchant_names: List[str] = []
        for l in lines:
            it: CartOrderItem = l["item"]
            if it.merchant_name and it.merchant_name not in merchant_names:
                merchant_names.append(it.merchant_name)
            label = f"[{it.merchant_name}] {it.title}" if it.merchant_name else it.title
            paypal_item: Dict[str, Any] = {
                "name": label[:127],
                "unit_amount": {
                    "currency_code": target_currency,
                    "value": format_decimal_currency(l["unit_dec"], target_currency),
                },
                "quantity": str(it.quantity),
                "category": "PHYSICAL_GOODS",
            }
            if it.listing_id:
                paypal_item["sku"] = it.listing_id[:64]
            paypal_items.append(paypal_item)

        merchants_summary = ", ".join(merchant_names[:3])
        if len(merchant_names) > 3:
            merchants_summary += f" +{len(merchant_names) - 3}"

        pu: Dict[str, Any] = {
            "reference_id": f"cart_{group_key}"[:64] if len(unit_groups) > 1 else "visual_cart_order",
            "description": (
                f"{len(lines)} item(s) via {merchants_summary}" if merchants_summary else "Visual Cart Order"
            )[:127],
            "custom_id": f"cart_{len(lines)}_items"[:127],
            "amount": {
                "currency_code": target_currency,
                "value": format_decimal_currency(unit_total, target_currency),
                "breakdown": {
                    "item_total": {
                        "currency_code": target_currency,
                        "value": format_decimal_currency(unit_sub, target_currency),
                    },
                    "tax_total": {
                        "currency_code": target_currency,
                        "value": format_decimal_currency(unit_tax, target_currency),
                    },
                    "shipping": {
                        "currency_code": target_currency,
                        "value": format_decimal_currency(unit_ship, target_currency),
                    },
                },
            },
            "items": paypal_items,
        }
        if group_key != "visual_cart_universal" and len(unit_groups) > 1:
            pu["payee"] = {"merchant_id": group_key}
        if req.invoice_id and idx == 0:
            pu["invoice_id"] = req.invoice_id[:127]
        purchase_units.append(pu)

    order_payload = {
        "intent": "CAPTURE",
        "purchase_units": purchase_units,
        "application_context": {
            "brand_name": "PayPal Visual Cart",
            "landing_page": "NO_PREFERENCE",
            "user_action": "PAY_NOW",
            "return_url": req.return_url or "paypalvisualcart://checkout/success",
            "cancel_url": req.cancel_url or "paypalvisualcart://checkout/cancel",
        },
    }

    return {
        "order_payload": order_payload,
        "currency": target_currency,
        "subtotal": float(grand_subtotal),
        "tax": float(grand_tax),
        "delivery_fee": float(grand_shipping),
        "total": float(grand_total),
        "item_count": sum(l["item"].quantity for l in normalized_lines),
        "merchant_count": len({l["item"].merchant_name for l in normalized_lines}),
        "merchants": sorted({l["item"].merchant_name for l in normalized_lines}),
        "is_multi_seller": len(purchase_units) > 1,
    }
