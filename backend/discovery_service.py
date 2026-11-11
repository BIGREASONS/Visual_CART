"""Unified Product Discovery Service (Channel3 Live Offers + JSON-LD URL Fallback + Honest Store Links)."""

from __future__ import annotations

import logging
import os
from typing import Any, Dict, List, Optional
from urllib.parse import urlparse

from pydantic import BaseModel, Field

from .channel3_client import (
    Channel3Client,
    normalize_channel3_products,
    resolve_channel3_locale,
)
from .gemini_client import build_merchant_search_response, is_generic_image_hint
from .page_extractor import (
    UnsafeTargetUrlError,
    extract_product_from_url_safe,
    validate_public_http_url,
)

logger = logging.getLogger("discovery_service")


class ProductSearchRequest(BaseModel):
    query: Optional[str] = Field(default=None, max_length=256)
    base64_image: Optional[str] = Field(default=None)
    image_url: Optional[str] = Field(default=None, max_length=1024)
    country: Optional[str] = Field(default=None, max_length=8)
    currency: Optional[str] = Field(default=None, max_length=8)
    limit: int = Field(default=8, ge=1, le=20)
    mode: str = Field(default="default")
    include_search_links: bool = Field(default=True)
    use_visual_search: bool = Field(default=False)
    user_id: Optional[str] = Field(default=None, max_length=128)


class ProductLookupRequest(BaseModel):
    url: str = Field(..., min_length=8, max_length=2048)
    country: Optional[str] = Field(default=None, max_length=8)
    currency: Optional[str] = Field(default=None, max_length=8)
    include_search_links: bool = Field(default=True)
    user_id: Optional[str] = Field(default=None, max_length=128)


def _assert_safe_url_syntax(raw_url: str) -> str:
    """Fast local SSRF pre-check blocking non-HTTP schemes, credentials, and private/loopback literals."""
    cleaned = (raw_url or "").strip()
    parsed = urlparse(cleaned)
    scheme = (parsed.scheme or "").lower()
    if scheme not in {"http", "https"}:
        raise UnsafeTargetUrlError(f"Unsupported URL scheme: {scheme}")
    if parsed.username or parsed.password:
        raise UnsafeTargetUrlError("Credentials in URL are forbidden")
    host = (parsed.hostname or "").strip().lower()
    if not host or host in {"localhost", "ip6-localhost"} or host.endswith(".local"):
        raise UnsafeTargetUrlError(f"Blocked internal hostname: {host}")

    import ipaddress

    try:
        ip_obj = ipaddress.ip_address(host)
        if (
            ip_obj.is_private
            or ip_obj.is_loopback
            or ip_obj.is_link_local
            or ip_obj.is_multicast
            or ip_obj.is_reserved
            or ip_obj.is_unspecified
        ):
            raise UnsafeTargetUrlError("Blocked private or reserved IP literal")
    except ValueError:
        pass
    return cleaned


def _summarize_offers(offers: List[Dict[str, Any]]) -> Dict[str, Any]:
    instock = [o for o in offers if o.get("status") == "IN_STOCK" and o.get("price") is not None]
    pool = instock or [o for o in offers if o.get("price") is not None]
    if not pool:
        return {"lowest_price": None, "currency": None}
    lowest = min(pool, key=lambda o: float(o["price"]))
    return {
        "lowest_price": float(lowest["price"]),
        "currency": lowest.get("currency"),
    }


async def discover_products(
    req: ProductSearchRequest,
    client: Optional[Channel3Client] = None,
) -> Dict[str, Any]:
    """Searches Channel3 for live multi-merchant offers and merges honest store search links."""
    raw_query = (req.query or "").strip()
    is_noise_query = bool(raw_query) and is_generic_image_hint(raw_query)
    usable_query = "" if is_noise_query else raw_query

    has_image = bool((req.base64_image or "").strip() or (req.image_url or "").strip())
    if not usable_query and not has_image:
        return {
            "query": None,
            "rejected": True,
            "channel3_configured": (client or Channel3Client()).is_configured,
            "channel3_used": False,
            "products": [],
            "offers": [],
            "search_links": [],
            "listings": [],
            "offer_count": 0,
            "lowest_price": None,
            "currency": None,
            "locale": resolve_channel3_locale(req.country, req.currency),
        }

    c3 = client or Channel3Client()
    locale_info = resolve_channel3_locale(req.country, req.currency)
    offers: List[Dict[str, Any]] = []
    products_out: List[Dict[str, Any]] = []
    channel3_used = False
    provider_error: Optional[str] = None

    if c3.is_configured:
        send_visual_first = req.use_visual_search or not usable_query
        search_res = await c3.search_products(
            query=usable_query or None,
            base64_image=req.base64_image if send_visual_first else None,
            image_url=req.image_url if send_visual_first else None,
            country=req.country,
            currency=req.currency,
            limit=req.limit,
            mode=req.mode,
            user_id=req.user_id,
        )
        locale_info = search_res.get("locale") or locale_info
        if search_res.get("ok"):
            channel3_used = True
            norm = normalize_channel3_products(
                query=usable_query or "product",
                products=search_res.get("products") or [],
                locale_info=locale_info,
            )
            products_out = norm["products"]
            offers = norm["offers"]
        else:
            provider_error = search_res.get("error")

        # Fallback retry with visual query only if text-only returned zero offers
        if not offers and has_image and usable_query and not send_visual_first:
            visual_res = await c3.search_products(
                query=usable_query,
                base64_image=req.base64_image,
                image_url=req.image_url,
                country=req.country,
                currency=req.currency,
                limit=req.limit,
                mode=req.mode,
                user_id=req.user_id,
            )
            if visual_res.get("ok"):
                channel3_used = True
                norm_vis = normalize_channel3_products(
                    query=usable_query,
                    products=visual_res.get("products") or [],
                    locale_info=visual_res.get("locale") or locale_info,
                )
                if norm_vis["offers"]:
                    products_out = norm_vis["products"]
                    offers = norm_vis["offers"]

    link_query = usable_query or (products_out[0]["title"] if products_out else "")
    search_links: List[Dict[str, Any]] = []
    if req.include_search_links and link_query:
        links_resp = build_merchant_search_response(link_query)
        if not links_resp.get("rejected"):
            search_links = links_resp.get("listings") or []

    combined_listings = [*offers, *search_links]
    summary = _summarize_offers(offers)
    return {
        "query": link_query or None,
        "rejected": False,
        "channel3_configured": c3.is_configured,
        "channel3_used": channel3_used,
        "error": provider_error,
        "locale": locale_info,
        "products": products_out,
        "offers": offers,
        "search_links": search_links,
        "listings": combined_listings,
        "offer_count": len(offers),
        "lowest_price": summary["lowest_price"],
        "currency": summary["currency"],
    }


async def lookup_product_by_url(
    req: ProductLookupRequest,
    client: Optional[Channel3Client] = None,
) -> Dict[str, Any]:
    """Resolves a shared product URL via Channel3 /v1/lookup, falling back to safe JSON-LD extraction."""
    cleaned_url = _assert_safe_url_syntax(req.url)
    c3 = client or Channel3Client()
    locale_info = resolve_channel3_locale(req.country, req.currency)

    offers: List[Dict[str, Any]] = []
    products_out: List[Dict[str, Any]] = []
    offer_source = "NONE"
    resolved_title: Optional[str] = None
    channel3_used = False

    if c3.is_configured:
        lookup_res = await c3.lookup_url(
            url=cleaned_url,
            country=req.country,
            currency=req.currency,
            user_id=req.user_id,
        )
        locale_info = lookup_res.get("locale") or locale_info
        prod = lookup_res.get("product")
        if lookup_res.get("ok") and isinstance(prod, dict):
            channel3_used = True
            resolved_title = str(prod.get("title") or "").strip() or None
            norm = normalize_channel3_products(
                query=resolved_title or cleaned_url,
                products=[prod],
                locale_info=locale_info,
            )
            products_out = norm["products"]
            offers = norm["offers"]
            if offers:
                offer_source = "CHANNEL3_API"

    # Fallback: SSRF-hardened structured page metadata extraction (JSON-LD / OpenGraph)
    if not offers:
        extracted = await extract_product_from_url_safe(cleaned_url)
        if extracted:
            resolved_title = extracted.get("title") or resolved_title
            offers = [extracted]
            offer_source = extracted.get("offer_source", "PRODUCT_PAGE_JSONLD")

    search_links: List[Dict[str, Any]] = []
    if req.include_search_links and resolved_title and not is_generic_image_hint(resolved_title):
        links_resp = build_merchant_search_response(resolved_title)
        if not links_resp.get("rejected"):
            search_links = links_resp.get("listings") or []

    combined_listings = [*offers, *search_links]
    summary = _summarize_offers(offers)
    return {
        "url": cleaned_url,
        "resolved": bool(offers or resolved_title),
        "title": resolved_title,
        "channel3_configured": c3.is_configured,
        "channel3_used": channel3_used,
        "offer_source": offer_source,
        "locale": locale_info,
        "products": products_out,
        "offers": offers,
        "search_links": search_links,
        "listings": combined_listings,
        "offer_count": len(offers),
        "lowest_price": summary["lowest_price"],
        "currency": summary["currency"],
    }


async def refresh_product_by_id(
    product_id: str,
    country: Optional[str] = None,
    currency: Optional[str] = None,
    user_id: Optional[str] = None,
    client: Optional[Channel3Client] = None,
) -> Dict[str, Any]:
    """Executes free Channel3 GET /v1/products/{product_id} to refresh offers and checkout URLs."""
    c3 = client or Channel3Client()
    res = await c3.get_product(
        product_id=product_id,
        country=country,
        currency=currency,
        user_id=user_id,
    )
    if not res.get("ok") or not isinstance(res.get("product"), dict):
        return {
            "ok": False,
            "product_id": product_id,
            "status_code": res.get("status_code", 404),
            "error": res.get("error") or "Product refresh failed",
            "products": [],
            "offers": [],
            "listings": [],
        }

    prod = res["product"]
    title = str(prod.get("title") or product_id).strip()
    norm = normalize_channel3_products(
        query=title,
        products=[prod],
        locale_info=res.get("locale"),
    )
    summary = _summarize_offers(norm["offers"])
    return {
        "ok": True,
        "product_id": product_id,
        "title": title,
        "locale": res.get("locale"),
        "products": norm["products"],
        "offers": norm["offers"],
        "listings": norm["offers"],
        "offer_count": len(norm["offers"]),
        "lowest_price": summary["lowest_price"],
        "currency": summary["currency"],
    }


async def search_merchants_unified(
    q: str,
    country: Optional[str] = None,
    currency: Optional[str] = None,
    include_search_links: bool = True,
    user_id: Optional[str] = None,
    client: Optional[Channel3Client] = None,
) -> Dict[str, Any]:
    """Upgrades GET /api/merchants/search to return live Channel3 offers when configured
    alongside honest DIRECT_SEARCH_URL links without breaking offline direct-link tests."""
    cleaned = (q or "").strip()
    if is_generic_image_hint(cleaned):
        return {
            "query": None,
            "rejected": True,
            "channel3_used": False,
            "listings": [],
        }

    c3 = client or Channel3Client()
    if not c3.is_configured:
        resp = build_merchant_search_response(cleaned)
        resp["channel3_used"] = False
        return resp

    search_res = await discover_products(
        ProductSearchRequest(
            query=cleaned,
            country=country,
            currency=currency,
            limit=8,
            include_search_links=include_search_links,
            user_id=user_id,
        ),
        client=c3,
    )
    return {
        "query": search_res.get("query") or cleaned,
        "rejected": False,
        "channel3_used": search_res.get("channel3_used", False),
        "locale": search_res.get("locale"),
        "offers": search_res.get("offers", []),
        "listings": search_res.get("listings", []),
        "lowest_price": search_res.get("lowest_price"),
        "currency": search_res.get("currency"),
    }
