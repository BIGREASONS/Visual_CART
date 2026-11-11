"""SSRF-Hardened Product Page Structured Metadata Extractor (JSON-LD & OpenGraph Fallback).

Used only when a user explicitly shares a product URL and Channel3 /v1/lookup does not resolve it
(e.g., India-local retailers or newly published URLs returning HTTP 422 from Channel3).

Security safeguards:
- Allows only http/https schemes without embedded credentials.
- Rejects localhost, .local domains, and literal/resolved private, loopback, link-local,
  multicast, or reserved IPs before establishing outbound connections.
- Re-validates redirect targets (max 3 redirects), bounds timeout (5s), and caps payload (1.5 MB).
"""

from __future__ import annotations

import ipaddress
import json
import logging
import socket
import time
from decimal import Decimal
from html.parser import HTMLParser
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import urljoin, urlparse

import httpx

from .channel3_client import decimal_to_minor_units, humanize_merchant_name, parse_price_decimal

logger = logging.getLogger("page_extractor")

MAX_REDIRECTS = 3
MAX_HTML_BYTES = 1_500_000


class UnsafeTargetUrlError(ValueError):
    """Raised when a URL fails SSRF validation."""


def _is_forbidden_ip(ip_obj: ipaddress._BaseAddress) -> bool:
    return bool(
        ip_obj.is_private
        or ip_obj.is_loopback
        or ip_obj.is_link_local
        or ip_obj.is_multicast
        or ip_obj.is_reserved
        or ip_obj.is_unspecified
    )


def validate_public_http_url(url: str) -> str:
    """Validates that a URL points to a public HTTP(S) host, blocking SSRF targets."""
    cleaned = (url or "").strip()
    if not cleaned or len(cleaned) > 2048:
        raise UnsafeTargetUrlError("Invalid or empty URL")

    parsed = urlparse(cleaned)
    scheme = (parsed.scheme or "").lower()
    if scheme not in {"http", "https"}:
        raise UnsafeTargetUrlError(f"Unsupported URL scheme: {scheme}")
    if parsed.username or parsed.password:
        raise UnsafeTargetUrlError("Credentials in URL are forbidden")

    hostname = (parsed.hostname or "").strip().lower()
    if not hostname or hostname in {"localhost", "ip6-localhost"} or hostname.endswith(".local"):
        raise UnsafeTargetUrlError(f"Blocked internal hostname: {hostname}")

    # Direct IP literal check
    try:
        ip_literal = ipaddress.ip_address(hostname)
        if _is_forbidden_ip(ip_literal):
            raise UnsafeTargetUrlError("Blocked private or reserved IP address")
    except ValueError:
        # Resolve DNS hostname and ensure no record maps to private/internal space
        try:
            infos = socket.getaddrinfo(hostname, parsed.port or (443 if scheme == "https" else 80), proto=socket.IPPROTO_TCP)
        except socket.gaierror as exc:
            raise UnsafeTargetUrlError(f"DNS lookup failed for {hostname}") from exc
        if not infos:
            raise UnsafeTargetUrlError(f"No DNS records resolved for {hostname}")
        for info in infos:
            sockaddr = info[4]
            ip_str = sockaddr[0].split("%")[0]
            try:
                ip_obj = ipaddress.ip_address(ip_str)
            except ValueError as exc:
                raise UnsafeTargetUrlError("Invalid resolved IP") from exc
            if _is_forbidden_ip(ip_obj):
                raise UnsafeTargetUrlError("Blocked private/loopback resolved IP")

    return cleaned


class _StructuredHtmlParser(HTMLParser):
    """Lightweight HTML parser extracting JSON-LD scripts, OpenGraph meta tags, and page title."""

    def __init__(self):
        super().__init__()
        self._in_jsonld = False
        self._in_title = False
        self._jsonld_buf: List[str] = []
        self._title_buf: List[str] = []
        self.jsonld_blocks: List[str] = []
        self.meta_tags: Dict[str, str] = {}
        self.title: str = ""

    def handle_starttag(self, tag: str, attrs: List[Tuple[str, Optional[str]]]) -> None:
        t = tag.lower()
        attr_map = {k.lower(): (v or "") for k, v in attrs if k}
        if t == "script":
            stype = attr_map.get("type", "").lower()
            if "ld+json" in stype:
                self._in_jsonld = True
                self._jsonld_buf = []
        elif t == "title" and not self.title:
            self._in_title = True
            self._title_buf = []
        elif t == "meta":
            key = (attr_map.get("property") or attr_map.get("name") or "").strip().lower()
            content = (attr_map.get("content") or "").strip()
            if key and content and key not in self.meta_tags:
                self.meta_tags[key] = content

    def handle_endtag(self, tag: str) -> None:
        t = tag.lower()
        if t == "script" and self._in_jsonld:
            raw = "".join(self._jsonld_buf).strip()
            if raw:
                self.jsonld_blocks.append(raw)
            self._in_jsonld = False
            self._jsonld_buf = []
        elif t == "title" and self._in_title:
            self.title = " ".join("".join(self._title_buf).split())
            self._in_title = False
            self._title_buf = []

    def handle_data(self, data: str) -> None:
        if self._in_jsonld:
            self._jsonld_buf.append(data)
        elif self._in_title:
            self._title_buf.append(data)


def _iter_jsonld_objects(node: Any):
    if isinstance(node, dict):
        yield node
        graph = node.get("@graph")
        if isinstance(graph, list):
            for child in graph:
                yield from _iter_jsonld_objects(child)
    elif isinstance(node, list):
        for item in node:
            yield from _iter_jsonld_objects(item)


def _normalize_availability(raw: Optional[str]) -> str:
    txt = (raw or "").strip().lower()
    if "outofstock" in txt or "soldout" in txt or "discontinued" in txt:
        return "OUT_OF_STOCK"
    if "instock" in txt or "limitedavailability" in txt or "onlineonly" in txt:
        return "IN_STOCK"
    return "IN_STOCK" if txt == "" else "IN_STOCK"


def parse_structured_product_html(html_text: str, page_url: str) -> Optional[Dict[str, Any]]:
    """Extracts Schema.org Product/Offer JSON-LD or OpenGraph product price metadata from HTML."""
    parser = _StructuredHtmlParser()
    parser.feed(html_text[:MAX_HTML_BYTES])

    parsed_url = urlparse(page_url)
    domain = (parsed_url.hostname or "").lower()
    if domain.startswith("www."):
        domain = domain[4:]

    best_title: Optional[str] = None
    best_brand: Optional[str] = None
    best_image: Optional[str] = None
    best_price: Optional[Decimal] = None
    best_currency: Optional[str] = None
    best_status: str = "IN_STOCK"

    # 1. JSON-LD Product / Offer priority
    for block in parser.jsonld_blocks:
        try:
            loaded = json.loads(block)
        except Exception:
            continue
        for obj in _iter_jsonld_objects(loaded):
            raw_type = obj.get("@type")
            types = {str(t).lower() for t in raw_type} if isinstance(raw_type, list) else {str(raw_type or "").lower()}
            if not any("product" in t for t in types):
                continue

            if not best_title and isinstance(obj.get("name"), str):
                best_title = obj["name"].strip()
            brand_node = obj.get("brand")
            if not best_brand:
                if isinstance(brand_node, dict) and isinstance(brand_node.get("name"), str):
                    best_brand = brand_node["name"].strip()
                elif isinstance(brand_node, str):
                    best_brand = brand_node.strip()
            img_node = obj.get("image")
            if not best_image:
                if isinstance(img_node, str):
                    best_image = img_node.strip()
                elif isinstance(img_node, list) and img_node and isinstance(img_node[0], str):
                    best_image = img_node[0].strip()

            offers_raw = obj.get("offers")
            offer_candidates = offers_raw if isinstance(offers_raw, list) else ([offers_raw] if isinstance(offers_raw, dict) else [])
            for offer in offer_candidates:
                if not isinstance(offer, dict):
                    continue
                price_candidate = (
                    parse_price_decimal(offer.get("price"))
                    or parse_price_decimal(offer.get("lowPrice"))
                    or parse_price_decimal((offer.get("priceSpecification") or {}).get("price") if isinstance(offer.get("priceSpecification"), dict) else None)
                )
                if price_candidate is None:
                    continue
                curr = str(offer.get("priceCurrency") or "").strip().upper() or "USD"
                avail = _normalize_availability(offer.get("availability"))
                if best_price is None or price_candidate < best_price:
                    best_price = price_candidate
                    best_currency = curr
                    best_status = avail

    # 2. OpenGraph product metadata fallback
    if best_price is None:
        og_price = (
            parse_price_decimal(parser.meta_tags.get("product:price:amount"))
            or parse_price_decimal(parser.meta_tags.get("og:price:amount"))
        )
        if og_price is not None:
            best_price = og_price
            best_currency = (
                parser.meta_tags.get("product:price:currency")
                or parser.meta_tags.get("og:price:currency")
                or "USD"
            ).strip().upper()
            best_status = _normalize_availability(parser.meta_tags.get("product:availability"))

    best_title = (
        best_title
        or parser.meta_tags.get("og:title")
        or parser.title
        or None
    )
    best_image = best_image or parser.meta_tags.get("og:image") or ""

    if not best_title or best_price is None or not best_currency:
        return None

    merchant_name = humanize_merchant_name(domain)
    return {
        "id": f"jsonld_{abs(hash(page_url)) % 1000000}",
        "provider_product_id": None,
        "product_title": best_title,
        "title": best_title,
        "brand": best_brand,
        "category": "General",
        "merchant": merchant_name,
        "merchant_id": domain,
        "merchant_domain": domain,
        "price": float(best_price),
        "price_minor": decimal_to_minor_units(best_price, best_currency),
        "originalPrice": None,
        "compare_at_price_minor": None,
        "currency": best_currency,
        "status": best_status,
        "availability": best_status,
        "condition": "new",
        "delivery": "See merchant page",
        "isLowest": True,
        "match_type": "EXACT",
        "confidence": 0.90,
        "source_type": "LIVE",
        "listing_origin": "LIVE_API",
        "offer_source": "PRODUCT_PAGE_JSONLD",
        "verification_status": "OBSERVED_PAGE_METADATA",
        "url": page_url,
        "image_url": best_image,
        "observed_at": int(time.time() * 1000),
        "is_reference_price_verified": False,
    }


async def extract_product_from_url_safe(url: str, timeout: float = 5.0) -> Optional[Dict[str, Any]]:
    """Safely fetches a public product page URL and parses structured JSON-LD / OpenGraph pricing."""
    current_url = validate_public_http_url(url)
    headers = {
        "User-Agent": "VisualCartMetadataBot/1.0 (+https://visualcart.local)",
        "Accept": "text/html,application/xhtml+xml",
    }

    async with httpx.AsyncClient(timeout=timeout, follow_redirects=False) as client:
        for _ in range(MAX_REDIRECTS + 1):
            validate_public_http_url(current_url)
            resp = await client.get(current_url, headers=headers)
            if resp.status_code in {301, 302, 303, 307, 308}:
                location = resp.headers.get("location")
                if not location:
                    return None
                current_url = urljoin(current_url, location)
                continue
            if resp.status_code != 200:
                return None
            content_type = (resp.headers.get("content-type") or "").lower()
            if "html" not in content_type and "xml" not in content_type and content_type:
                return None
            html_body = resp.content[:MAX_HTML_BYTES].decode("utf-8", errors="ignore")
            return parse_structured_product_html(html_body, current_url)

    return None
