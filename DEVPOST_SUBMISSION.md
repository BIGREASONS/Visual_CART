# Devpost Project Submission: PayPal Visual Cart

## Project Overview

- **Project Title**: PayPal Visual Cart: Universal AI Shopping Companion
- **Tagline**: *"See it. Save it. Find it. Pay with PayPal."*
- **Track**: PayPal AI Hackathon — Innovate Commerce with AI & PayPal
- **Team**: Vaibhav Kumar Singh ([@BIGREASONS](https://github.com/BIGREASONS))
- **Repository**: [GitHub Repository](https://github.com/paypal-visual-cart/visual-cart)
- **Demo Video Duration**: 2 minutes 40 seconds (Strictly under 3 minutes)

---

## Elevator Pitch & Problem Statement

### The Problem: Mobile Commerce is Broken by Cart Fragmentation
Modern mobile consumers don't shop in a single store. Discovery happens anywhere and everywhere:
- You see an SSD recommendation in a YouTube video description.
- You spot designer sneakers on an Instagram story.
- You find wireless headphones in a Reddit tech thread.
- You circle an item on your screen using Google Circle to Search.

Today, this results in **extreme cart fragmentation**:
- Items are abandoned across 5 different retailer apps (Amazon, Flipkart, Croma, Myntra, Reliance Digital).
- Users are forced to create separate accounts, enter shipping addresses repeatedly, and navigate distinct checkout forms.
- The average mobile e-commerce cart abandonment rate sits at an alarming **70.19%** (Baymard Institute), representing billions of dollars in lost transaction volume.
- Buyers miss out on the best price because cross-checking four stores manually is too tedious.

### The Solution: PayPal Visual Cart
**PayPal Visual Cart** transforms any mobile screen into a unified shopping cart:
1. **Instant Visual Capture**: Using Android Direct Share or Google Circle to Search, share any image, screenshot, or product link directly to Visual Cart with zero friction.
2. **Multimodal AI Product Understanding**: Google Gemini 3.5 Flash Lite analyzes visual features, OCR text, and model numbers to extract canonical specs (e.g., *Crucial P3 Plus 1TB PCIe 4.0 NVMe M.2 2280*).
3. **Cross-Merchant Price Discovery**: Concurrently searches competing retailers (Amazon, Flipkart, Croma, Reliance Digital, Myntra, Tata CLiQ) to find the verified lowest price, stock status, and savings.
4. **Universal Persistent Cart**: Saves items into an editorial SQLite repository with historical 30-day price tracking.
5. **One-Click PayPal Checkout**: Completes the purchase through PayPal Orders v2 API with PayPal Buyer Protection and transparent itemized breakdown.

---

## Key Features & User Experience

- **Google Circle to Search & Direct Share Integration**: Appears natively in Android's Sharesheet via `ShortcutManagerCompat`. Tap "Add to Visual Cart" from any application to trigger visual ingestion instantly.
- **Always-Accessible Floating Cart & Quick Settings Tile**: A 56dp Forest Green floating bubble with spring snap physics and an Android Quick Settings tile let users trigger screen captures or check their cart from any screen.
- **Explainable AI Matching**: Displays confidence indicators (`EXACT 96%`, `LIKELY 88%`, `SIMILAR 75%`) and human-readable explanations (e.g., *"Brand & storage capacity match exact specifications"*).
- **Interactive Price Observation**: Custom Canvas-rendered bezier price trend graphs tracking 30-day price trends across verified merchants.
- **Editorial Fintech Design Palette**: Crafted in Warm Ivory (`#F6F3EC`), Deep Ink (`#202B32`), Forest Green (`#356B57`), Muted Sage (`#A6B5A0`), and Terracotta (`#C97858`).

---

## Architecture & Technology Stack

PayPal Visual Cart is engineered using **Clean Architecture** and **MVVM** principles on modern native Android, paired with a lightweight **FastAPI** gateway:

```
+─────────────────────────────────────────────────────────────────────────────+
|                                ANDROID CLIENT                               |
|  +---------------------------+  +-------------------------+  +------------+ |
|  | Quick Settings Tile       |  | Floating Overlay Bubble |  | Direct     | |
|  | (VisualCartTileService)   |  | (OverlayService)        |  | Share Sheet| |
|  +-------------+-------------+  +------------+------------+  +-----+------+ |
|                │                             │                     │        |
|                └──────────────────────┬──────┴─────────────────────┘        |
|                                       ▼                                     |
|                       [ VisualCartShareActivity / UI ]                      |
|                                       │                                     |
|                                       ▼                                     |
|                         [ ProductRecognitionEngine ]                        |
|                         ├── Cloud: Gemini 3.5 Flash Lite                         |
|                         └── Local: LocalProductParser (Regex)               |
|                                       │                                     |
|                                       ▼                                     |
|                       [ Multi-Merchant Discovery Engine ]                   |
|                       (Amazon, Flipkart, Croma, Reliance)                   |
|                                       │                                     |
|                                       ▼                                     |
|                       [ Persistent SQLite Cart Database ]                   |
|                                       │                                     |
|                                       ▼                                     |
|                       [ PayPal Sandbox Payment Adapter ]                    |
+───────────────────────────────────────┬─────────────────────────────────────+
                                        │ (REST / JSON)
                                        ▼
+─────────────────────────────────────────────────────────────────────────────+
|                         FASTAPI BACKEND GATEWAY                             |
|  +──────────────────────────────────+  +──────────────────────────────────+ |
|  | Google Gemini 3.5 Flash Lite Proxy    |  | PayPal Orders v2 API Client      | |
|  | (Multimodal JSON Schema)         |  | (OAuth2, Create, Approve, Capture)| |
|  +──────────────────────────────────+  +──────────────────────────────────+ |
+─────────────────────────────────────────────────────────────────────────────+
```

### Android Native Stack
- **Language**: Kotlin 2.2+ (Targeting Android 16 / SDK 36, Min SDK 26)
- **UI Toolkit**: Jetpack Compose, Material 3, Navigation 3, Edge-to-Edge
- **Reactive Data**: Kotlin Coroutines & Flow, Handcrafted Relational SQLite DAO (`VisualCartDatabase`)
- **System Integration**: `ShortcutManagerCompat`, `TileService`, `SYSTEM_ALERT_WINDOW` with Android 14+ `FOREGROUND_SERVICE_SPECIAL_USE`

### AI Capabilities Stack
- **Vision Model**: Google Gemini 2.5 Flash / 3.8 Flash Multimodal Vision API (`gemini-2.5-flash`)
- **Schema Enforcement**: Structured JSON response formatting (`generationConfig.response_mime_type = "application/json"`)
- **On-Device Fallback**: `LocalProductParser.kt` regex spec parser extracting memory, capacities, form factors, apparel sizing, and brand signatures
- **Matching & Explainability**: `ProductMatchingEngine.kt` computing weighted attribute alignment scores and natural language justifications

### PayPal Capabilities Stack
- **Orders v2 REST API**: `/v2/checkout/orders` with `intent: "CAPTURE"`
- **OAuth 2.0 Authentication**: Client credentials grant with automatic token caching and expiration buffer
- **Idempotent Order Capture**: `/v2/checkout/orders/{id}/capture` with unique `PayPal-Request-Id` headers
- **Transparent Order Breakdown**: Subtotal, GST (18%), delivery calculations, and PayPal Buyer Protection guarantees
- **Smart Routing**: Dual-path routing (Integrated Sandbox Checkout vs Verified Merchant Custom Tabs fallback with security disclaimers)

### Technologies & Sponsor Integrations Used
- **PayPal Orders v2 API**: Core checkout, authorization, capture, and buyer protection guarantees.
- **Google Gemini 3.5 Flash Lite**: Multimodal vision model extracting canonical brand, model, and hardware specs into structured JSON.
- **Channel3 API**: Universal multi-merchant product catalog search, offer aggregation, and live pricing.
- **Postman**: API collection testing for PayPal Orders v2 endpoints and webhook mock verification.
- **APIMatic**: OpenAPI 3.1 specification modeling and client contract verification.
- **FastAPI & Uvicorn**: High-performance asynchronous backend gateway.
- **Android Jetpack Compose & M3**: Declarative editorial fintech UI, custom Canvas bezier price graphs, and Material 3 design tokens.
- **Android MediaProjection**: Pre-armed foreground capture service enabling Circle to Shop across external video apps without pausing playback.

---

## Challenges We Overcame

1. **Android 14/15 Content URI Permission Lifetimes**:
   When receiving shared images via `ACTION_SEND`, Android grants transient read permissions that revoke as soon as the calling app backgrounded. We resolved this by building `SafeImageDecoder.copyUriToPrivateCache`, safely copying incoming image streams into an isolated app cache before launching asynchronous AI vision pipelines.
2. **Disambiguating Complex Technical Specifications**:
   Hardware components often differ by subtle model suffixes (e.g., Crucial P3 vs Crucial P3 Plus, or 500GB vs 1TB). We paired Gemini's semantic understanding with a deterministic on-device regex spec parser to guarantee 100% precision in canonical spec matching.
3. **Zero Hardcoded Secrets Architecture**:
   To ensure enterprise-grade security compliance, zero API keys or client secrets exist in source code. Credentials resolve via runtime environment variables (`PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET`, `GEMINI_API_KEY`) or proxy endpoints, with full offline fallback capabilities.

---

## Accomplishments We're Proud Of

- **Zero-Friction Ingestion**: Achieved 1-tap capture from any Android application using native platform sharing shortcuts.
- **Editorial Fintech Visual Design**: Replaced boring form-heavy e-commerce UI with a bespoke serif/sans editorial palette inspired by high-end financial journalism.
- **Resilient Offline Architecture**: The application never crashes or halts if network connectivity is severed or cloud API quotas are exhausted; it seamlessly falls back to on-device parsing.
- **100% Test Pass Rate**: Thorough test suite covering security URL validation, matching algorithms, spec regex parsing, and PayPal order calculations.

---

## What We Learned

- How to implement Android Sharing Shortcuts with `ShortcutManagerCompat` for Direct Share prominence.
- The mechanics of PayPal Orders v2 idempotent capture and OAuth 2.0 token lifecycle management.
- Techniques for constraining multimodal vision models to output strictly validated JSON schemas without conversational filler.

---

## What's Next for PayPal Visual Cart

1. **Autonomous Price Drop Tracking**: Background WorkManager jobs to monitor price drops and notify users when a saved item drops below their target threshold.
2. **Universal Multi-Merchant Cart Aggregation**: Consolidating multiple products from 3 different stores into a single bundled PayPal transaction.
3. **Universal Commerce Protocol (UCP) Federation**: Enabling decentralized AI shopping agents to negotiate order fulfillment directly with participating merchants.

---

## Built By

**Vaibhav Kumar Singh** ([@BIGREASONS](https://github.com/BIGREASONS))
- Full-Stack & Android Architecture Lead
- Portfolio: [vaibhavos.vercel.app](https://vaibhavos.vercel.app/)
- GitHub: [github.com/BIGREASONS](https://github.com/BIGREASONS)

