"""Channel3 Product Discovery & Price Comparison Client.

Integrates the Channel3 API (https://api.trychannel3.com):
- POST /v1/search: Multi-merchant catalog search by query, image_url, or base64_image.
- POST /v1/lookup: Canonical product resolution from a retailer product URL.
- GET /v1/products/{product_id}: Free hydrated details, variants, and fresh offer URLs.

Security & accuracy guarantees:
- API key is read strictly from environment variables (CHANNEL3_API_KEY) and never hardcoded or logged.
- Unsupported locales (e.g. IN/INR) fall back transparently to US/USD rather than triggering HTTP 422,
  and all returned prices retain exact provider-reported currencies and provenance timestamps.
"""

from __future__ import annotations

import logging
import os
import re
import time
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import urlparse

import httpx

logger = logging.getLogger("channel3_client")

CHANNEL3_DEFAULT_BASE_URL = "https://api.trychannel3.com"

SUPPORTED_COUNTRIES = frozenset({
    "US", "GB", "EU", "AU", "CA", "IE", "DE", "AT", "FR", "BE",
    "IT", "ES", "NL", "SE", "FI", "PT", "CZ", "GR", "RO", "CH", "PL",
})
COUNTRY_TO_DEFAULT_CURRENCY: Dict[str, str] = {
    "US": "USD",
    "CA": "CAD",
    "AU": "AUD",
    "GB": "GBP",
    "EU": "EUR",
    "IE": "EUR",
    "DE": "EUR",
    "AT": "EUR",
    "FR": "EUR",
    "BE": "EUR",
    "IT": "EUR",
    "ES": "EUR",
    "NL": "EUR",
    "PT": "EUR",
    "FI": "EUR",
    "GR": "EUR",
    "SE": "SEK",
    "CZ": "CZK",
    "RO": "RON",
    "CH": "EUR",
    "PL": "EUR",
}
SUPPORTED_CURRENCIES = frozenset({"USD", "CAD", "AUD", "GBP", "EUR", "SEK", "CZK", "RON"})
ZERO_DECIMAL_CURRENCIES = frozenset({"JPY", "HUF", "TWD", "KRW", "VND"})
THREE_DECIMAL_CURRENCIES = frozenset({"BHD", "JOD", "KWD", "OMR", "TND"})

MERCHANT_DISPLAY_NAMES: Dict[str, str] = {
    "amazon.com": "Amazon",
    "amazon.co.uk": "Amazon UK",
    "amazon.ca": "Amazon Canada",
    "amazon.in": "Amazon India",
    "walmart.com": "Walmart",
    "target.com": "Target",
    "bestbuy.com": "Best Buy",
    "newegg.com": "Newegg",
    "cdw.com": "CDW",
    "officedepot.com": "Office Depot",
    "macys.com": "Macy's",
    "kohls.com": "Kohl's",
    "jcpenney.com": "JCPenney",
    "bhphotovideo.com": "B&H Photo",
    "microcenter.com": "Micro Center",
    "ebay.com": "eBay",
    "flipkart.com": "Flipkart",
    "croma.com": "Croma",
    "reliancedigital.in": "Reliance Digital",
    "tatacliq.com": "Tata CLiQ",
    "myntra.com": "Myntra",
    "backmarket.com": "Back Market",
    "beachcamera.com": "Beach Camera",
    "nike.com": "Nike",
    "nordstrom.com": "Nordstrom",
    "staples.com": "Staples",
    "adorama.com": "Adorama",
}

_STOPWORDS = frozenset({"the", "and", "for", "with", "from", "size", "color", "new", "by", "on", "in", "of", "to"})


def _get_currency_decimals(currency: str) -> int:
    curr = (currency or "USD").strip().upper()
    if curr in ZERO_DECIMAL_CURRENCIES:
        return 0
    if curr in THREE_DECIMAL_CURRENCIES:
        return 3
    return 2


def parse_price_decimal(value: Any) -> Optional[Decimal]:
    """Safely parses a numeric or string price into a positive finite Decimal."""
    if value is None or isinstance(value, bool):
        return None
    try:
        dec = Decimal(str(value).strip())
    except (InvalidOperation, ValueError):
        return None
    if not dec.is_finite() or dec <= Decimal("0"):
        return None
    return dec


def decimal_to_minor_units(amount: Optional[Decimal], currency: str) -> Optional[int]:
    if amount is None:
        return None
    decimals = _get_currency_decimals(currency)
    scale = Decimal(10) ** decimals
    return int((amount * scale).quantize(Decimal("1"), rounding=ROUND_HALF_UP))


def resolve_channel3_locale(
    country: Optional[str] = None,
    currency: Optional[str] = None,
) -> Dict[str, Any]:
    """Validates country/currency against Channel3 supported enums.

    Unsupported values (such as IN/INR) fall back to US/USD cleanly with metadata
    indicating the fallback so callers never emit HTTP 422 locale errors.
    """
    raw_country = (country or os.getenv("CHANNEL3_COUNTRY") or "US").strip().upper()
    if raw_country == "UK":
        raw_country = "GB"
    raw_currency = (currency or os.getenv("CHANNEL3_CURRENCY") or "").strip().upper()

    if raw_country in SUPPORTED_COUNTRIES:
        resolved_country = raw_country
        fallback_used = False
    else:
        resolved_country = "US"
        fallback_used = bool(raw_country)

    if raw_currency in SUPPORTED_CURRENCIES:
        resolved_currency = raw_currency
    else:
        resolved_currency = COUNTRY_TO_DEFAULT_CURRENCY.get(resolved_country, "USD")
        if raw_currency and raw_currency != resolved_currency:
            fallback_used = True

    return {
        "requested_country": raw_country or resolved_country,
        "requested_currency": raw_currency or resolved_currency,
        "country": resolved_country,
        "currency": resolved_currency,
        "language": "en",
        "locale_fallback": fallback_used,
    }


def humanize_merchant_name(domain: Optional[str], fallback_name: Optional[str] = None) -> str:
    """Converts a merchant domain (e.g. 'www.newegg.com') into a clean display label."""
    if fallback_name and fallback_name.strip():
        return fallback_name.strip()
    clean_domain = (domain or "").strip().lower()
    if clean_domain.startswith("www."):
        clean_domain = clean_domain[4:]
    if not clean_domain:
        return "Merchant"
    if clean_domain in MERCHANT_DISPLAY_NAMES:
        return MERCHANT_DISPLAY_NAMES[clean_domain]
    stem = clean_domain.split(".")[0]
    parts = [p for p in re.split(r"[-_]+", stem) if p]
    if not parts:
        return clean_domain
    return " ".join(p.capitalize() for p in parts)


def _normalize_domain(url_or_domain: Optional[str]) -> str:
    raw = (url_or_domain or "").strip().lower()
    if not raw:
        return ""
    if "://" in raw:
        parsed = urlparse(raw)
        raw = parsed.netloc.lower()
    if raw.startswith("www."):
        raw = raw[4:]
    return raw.split(":")[0]


def _tokenize(text: str) -> List[str]:
    return [
        tok for tok in re.findall(r"[a-z0-9]+", (text or "").lower())
        if len(tok) > 1 and tok not in _STOPWORDS
    ]


def compute_title_match(
    query: str,
    product_title: str,
    brand_names: List[str],
) -> Tuple[str, float]:
    """Computes match tier (EXACT / LIKELY / SIMILAR) and confidence between query and Channel3 product."""
    q_tokens = _tokenize(query)
    if not q_tokens:
        return ("LIKELY", 0.78)
    title_tokens = set(_tokenize(product_title) + [t for b in brand_names for t in _tokenize(b)])
    if not title_tokens:
        return ("SIMILAR", 0.60)
    overlap = sum(1 for t in q_tokens if t in title_tokens)
    ratio = overlap / max(1, len(q_tokens))
    if ratio >= 0.85:
        return ("EXACT", min(0.98, round(0.88 + 0.10 * ratio, 2)))
    if ratio >= 0.55:
        return ("LIKELY", round(0.75 + 0.12 * ratio, 2))
    return ("SIMILAR", max(0.58, round(0.55 + 0.25 * ratio, 2)))


def _pick_best_image(product: Dict[str, Any]) -> str:
    images = product.get("images") or []
    for img in images:
        if isinstance(img, dict) and img.get("is_main_image"):
            return str(img.get("cleaned_url") or img.get("url") or "")
    for img in images:
        if isinstance(img, dict) and (img.get("cleaned_url") or img.get("url")):
            return str(img.get("cleaned_url") or img.get("url") or "")
    return ""


def normalize_channel3_products(
    products: List[Dict[str, Any]],
    query: str = "",
    locale_info: Optional[Dict[str, Any]] = None,
    observed_at_ms: Optional[int] = None,
    max_offers: int = 12,
) -> Dict[str, Any]:
    """Normalizes Channel3 products & nested offers into flat, deduplicated Visual Cart listings."""
    obs_ms = observed_at_ms if observed_at_ms is not None else int(time.time() * 1000)
    resolved_locale = locale_info or resolve_channel3_locale()
    offers_by_domain: Dict[str, Dict[str, Any]] = {}
    product_summaries: List[Dict[str, Any]] = []

    for prod in products or []:
        if not isinstance(prod, dict):
            continue
        prod_id = str(prod.get("id") or "").strip()
        title = str(prod.get("title") or "").strip()
        if not title:
            continue

        raw_brands = prod.get("brands") or []
        brand_names = [
            str(b.get("name")).strip()
            for b in raw_brands
            if isinstance(b, dict) and b.get("name")
        ]
        image_url = _pick_best_image(prod)
        category_obj = prod.get("category") if isinstance(prod.get("category"), dict) else {}
        category_title = category_obj.get("title") or "General"
        match_type, confidence = compute_title_match(query, title, brand_names)

        product_summaries.append({
            "id": prod_id,
            "title": title,
            "brands": brand_names,
            "category": category_title,
            "image_url": image_url,
            "variants": prod.get("variants"),
            "match_type": match_type,
            "confidence": confidence,
        })

        for offer in prod.get("offers") or []:
            if not isinstance(offer, dict):
                continue
            price_obj = offer.get("price") if isinstance(offer.get("price"), dict) else {}
            price_dec = parse_price_decimal(price_obj.get("price"))
            if price_dec is None:
                continue

            currency = str(
                price_obj.get("currency") or resolved_locale.get("currency") or "USD"
            ).strip().upper()
            compare_dec = parse_price_decimal(price_obj.get("compare_at_price"))
            if compare_dec is not None and compare_dec <= price_dec:
                compare_dec = None

            domain = _normalize_domain(offer.get("domain") or offer.get("url"))
            if not domain:
                continue

            merchant_name = humanize_merchant_name(domain)
            availability_raw = str(offer.get("availability") or "InStock").strip()
            if availability_raw.lower() == "outofstock":
                status = "OUT_OF_STOCK"
            else:
                status = "IN_STOCK"

            buy_url = str(offer.get("url") or "").strip()
            if not buy_url:
                continue

            price_minor = decimal_to_minor_units(price_dec, currency)
            compare_minor = decimal_to_minor_units(compare_dec, currency)
            commission_rate = offer.get("max_commission_rate")

            normalized_offer = {
                "id": f"c3_{prod_id}_{re.sub(r'[^a-z0-9]', '', domain)[:18]}",
                "provider_product_id": prod_id,
                "product_title": title,
                "title": title,
                "brand": brand_names[0] if brand_names else None,
                "category": category_title,
                "merchant": merchant_name,
                "merchant_id": domain,
                "merchant_domain": domain,
                "price": float(price_dec),
                "price_minor": price_minor,
                "originalPrice": float(compare_dec) if compare_dec is not None else None,
                "compare_at_price_minor": compare_minor,
                "currency": currency,
                "status": status,
                "availability": status,
                "condition": offer.get("condition") or "new",
                "delivery": "See merchant checkout",
                "isLowest": False,
                "match_type": match_type,
                "confidence": confidence,
                "source_type": "LIVE",
                "listing_origin": "LIVE_API",
                "offer_source": "CHANNEL3_API",
                "max_commission_rate": float(commission_rate) if isinstance(commission_rate, (int, float)) else None,
                "url": buy_url,
                "image_url": image_url,
                "observed_at": obs_ms,
                "is_reference_price_verified": compare_dec is not None,
            }

            existing = offers_by_domain.get(domain)
            if existing is None:
                offers_by_domain[domain] = normalized_offer
            else:
                # Prefer InStock, better match tier, then lower price for identical domain
                existing_rank = (
                    0 if existing["status"] == "IN_STOCK" else 1,
                    {"EXACT": 0, "LIKELY": 1, "SIMILAR": 2}.get(existing["match_type"], 2),
                    existing["price"],
                )
                candidate_rank = (
                    0 if normalized_offer["status"] == "IN_STOCK" else 1,
                    {"EXACT": 0, "LIKELY": 1, "SIMILAR": 2}.get(normalized_offer["match_type"], 2),
                    normalized_offer["price"],
                )
                if candidate_rank < existing_rank:
                    offers_by_domain[domain] = normalized_offer

    sorted_offers = sorted(
        offers_by_domain.values(),
        key=lambda o: (
            0 if o["status"] == "IN_STOCK" else 1,
            {"EXACT": 0, "LIKELY": 1, "SIMILAR": 2}.get(o["match_type"], 2),
            o["price"],
            -o["confidence"],
        ),
    )[:max_offers]

    # Mark lowest InStock price within the dominant currency pool
    instock = [o for o in sorted_offers if o["status"] == "IN_STOCK"]
    if instock:
        dom_curr = instock[0]["currency"]
        same_curr = [o for o in instock if o["currency"] == dom_curr]
        lowest_offer = min(same_curr, key=lambda o: o["price"])
        lowest_offer["isLowest"] = True

    return {
        "products": product_summaries,
        "offers": sorted_offers,
        "locale": resolved_locale,
    }


class Channel3Client:
    """Async client for Channel3 v1 product search, URL lookup, and free product retrieval."""

    def __init__(
        self,
        api_key: Optional[str] = None,
        base_url: Optional[str] = None,
        timeout: float = 15.0,
    ):
        self.api_key = (
            api_key if api_key is not None else os.getenv("CHANNEL3_API_KEY", "")
        ).strip()
        self.base_url = (
            base_url or os.getenv("CHANNEL3_BASE_URL") or CHANNEL3_DEFAULT_BASE_URL
        ).strip().rstrip("/")
        self.timeout = timeout

    @property
    def is_configured(self) -> bool:
        key = (self.api_key or "").strip()
        if not key or len(key) < 12:
            return False
        lower = key.lower()
        return not (
            lower.startswith("your_")
            or lower in {"placeholder", "changeme", "none", "null"}
        )

    def _headers(self, user_id: Optional[str] = None) -> Dict[str, str]:
        headers = {
            "x-api-key": self.api_key,
            "Content-Type": "application/json",
            "Accept": "application/json",
        }
        if user_id:
            clean_uid = re.sub(r"[^a-zA-Z0-9._@-]", "", user_id.strip())[:128]
            if clean_uid:
                headers["x-user-id"] = clean_uid
        return headers

    async def search_products(
        self,
        query: Optional[str] = None,
        base64_image: Optional[str] = None,
        image_url: Optional[str] = None,
        country: Optional[str] = None,
        currency: Optional[str] = None,
        limit: int = 8,
        mode: str = "default",
        filters: Optional[Dict[str, Any]] = None,
        user_id: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Executes POST /v1/search against Channel3."""
        locale_info = resolve_channel3_locale(country=country, currency=currency)
        if not self.is_configured:
            return {
                "ok": False,
                "configured": False,
                "status_code": 0,
                "error": "CHANNEL3_API_KEY is not configured.",
                "products": [],
                "locale": locale_info,
            }

        payload: Dict[str, Any] = {
            "limit": max(1, min(int(limit or 8), 30)),
            "config": {
                "mode": mode if mode in {"default", "keyword", "agentic"} else "default",
                "country": locale_info["country"],
                "currency": locale_info["currency"],
                "language": locale_info["language"],
            },
        }
        if query and query.strip():
            payload["query"] = query.strip()
        if base64_image and base64_image.strip():
            raw_b64 = base64_image.strip()
            if ";base64," in raw_b64:
                raw_b64 = raw_b64.split(";base64,", 1)[1]
            payload["base64_image"] = raw_b64
        if image_url and image_url.strip():
            payload["image_url"] = image_url.strip()
        if filters:
            payload["filters"] = filters

        if not any(k in payload for k in ("query", "base64_image", "image_url")):
            return {
                "ok": False,
                "configured": True,
                "status_code": 400,
                "error": "At least one of query, base64_image, or image_url is required.",
                "products": [],
                "locale": locale_info,
            }

        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                resp = await client.post(
                    f"{self.base_url}/v1/search",
                    headers=self._headers(user_id=user_id),
                    json=payload,
                )
            if resp.status_code == 200:
                body = resp.json()
                return {
                    "ok": True,
                    "configured": True,
                    "status_code": 200,
                    "products": body.get("products") or [],
                    "next_page_token": body.get("next_page_token"),
                    "locale": locale_info,
                }
            logger.warning("Channel3 /v1/search HTTP %s", resp.status_code)
            return {
                "ok": False,
                "configured": True,
                "status_code": resp.status_code,
                "error": f"Channel3 search returned HTTP {resp.status_code}",
                "products": [],
                "locale": locale_info,
            }
        except Exception as exc:
            logger.warning("Channel3 /v1/search error: %s", type(exc).__name__)
            return {
                "ok": False,
                "configured": True,
                "status_code": 503,
                "error": f"Channel3 request failed: {type(exc).__name__}",
                "products": [],
                "locale": locale_info,
            }

    async def lookup_url(
        self,
        url: str,
        max_staleness_hours: int = 3,
        country: Optional[str] = None,
        currency: Optional[str] = None,
        user_id: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Executes POST /v1/lookup to resolve a retailer product URL into canonical offers."""
        locale_info = resolve_channel3_locale(country=country, currency=currency)
        if not self.is_configured:
            return {
                "ok": False,
                "configured": False,
                "status_code": 0,
                "error": "CHANNEL3_API_KEY is not configured.",
                "product": None,
                "locale": locale_info,
            }

        try:
            async with httpx.AsyncClient(timeout=max(self.timeout, 15.0)) as client:
                resp = await client.post(
                    f"{self.base_url}/v1/lookup",
                    headers=self._headers(user_id=user_id),
                    json={
                        "url": url.strip(),
                        "max_staleness_hours": max(1, int(max_staleness_hours)),
                    },
                )
            if resp.status_code == 200:
                body = resp.json()
                product = body.get("product") if isinstance(body, dict) and "product" in body else body
                return {
                    "ok": True,
                    "configured": True,
                    "status_code": 200,
                    "product": product,
                    "locale": locale_info,
                }
            return {
                "ok": False,
                "configured": True,
                "status_code": resp.status_code,
                "error": f"Channel3 lookup returned HTTP {resp.status_code}",
                "product": None,
                "locale": locale_info,
            }
        except Exception as exc:
            logger.warning("Channel3 /v1/lookup error: %s", type(exc).__name__)
            return {
                "ok": False,
                "configured": True,
                "status_code": 504,
                "error": f"Channel3 lookup failed: {type(exc).__name__}",
                "product": None,
                "locale": locale_info,
            }

    async def get_product(
        self,
        product_id: str,
        country: Optional[str] = None,
        currency: Optional[str] = None,
        selected_options: Optional[Dict[str, str]] = None,
        user_id: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Executes FREE GET /v1/products/{product_id} to fetch refreshed live prices and affiliate URLs."""
        locale_info = resolve_channel3_locale(country=country, currency=currency)
        if not self.is_configured:
            return {
                "ok": False,
                "configured": False,
                "status_code": 0,
                "error": "CHANNEL3_API_KEY is not configured.",
                "product": None,
                "locale": locale_info,
            }

        params: List[Tuple[str, str]] = [
            ("country", locale_info["country"]),
            ("currency", locale_info["currency"]),
            ("language", locale_info["language"]),
        ]
        if selected_options:
            for dim, val in selected_options.items():
                if dim and val:
                    params.append((f"option_{dim}", str(val)))

        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                resp = await client.get(
                    f"{self.base_url}/v1/products/{product_id.strip()}",
                    headers=self._headers(user_id=user_id),
                    params=params,
                )
            if resp.status_code == 200:
                return {
                    "ok": True,
                    "configured": True,
                    "status_code": 200,
                    "product": resp.json(),
                    "locale": locale_info,
                }
            return {
                "ok": False,
                "configured": True,
                "status_code": resp.status_code,
                "error": f"Channel3 product fetch returned HTTP {resp.status_code}",
                "product": None,
                "locale": locale_info,
            }
        except Exception as exc:
            logger.warning("Channel3 /v1/products error: %s", type(exc).__name__)
            return {
                "ok": False,
                "configured": True,
                "status_code": 503,
                "error": f"Channel3 product fetch failed: {type(exc).__name__}",
                "product": None,
                "locale": locale_info,
            }
