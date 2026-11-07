# PayPal Visual Cart - System Design & Technical Architecture

> **Document Version**: 1.0.0  
> **Date**: November 2026  
> **Project**: PayPal Visual Cart  
> **Visual Palette**: Warm Ivory (`#F6F3EC`), Deep Ink (`#202B32`), Forest Green (`#356B57`), Muted Sage (`#A6B5A0`), Terracotta (`#C97858`)

---

## 1. High-Level System Architecture

PayPal Visual Cart is designed as a distributed, privacy-first mobile commerce architecture. It pairs an Android native client with a lightweight FastAPI gateway connecting to **Google Gemini Multimodal AI** and the **PayPal Orders v2 REST API**:

```mermaid
flowchart TD
    subgraph External_Apps["External Android Applications"]
        IG["Instagram"]
        YT["YouTube"]
        Chrome["Google Chrome"]
        CTS["Google Circle to Search"]
    end

    subgraph Android_System["Android System OS Layer"]
        ShareSheet["Android Sharesheet\n(Direct Share via ShortcutManagerCompat)"]
        TileSvc["Quick Settings Tile\n(VisualCartTileService)"]
        OverlaySvc["Floating Draggable Bubble\n(VisualCartOverlayService)"]
    end

    subgraph Visual_Cart_App["PayPal Visual Cart (Native Android App)"]
        UI["Jetpack Compose UI Layer\n(HomeScreen, CartScreen, DetailScreen)"]
        Parser["Spec Parser Engine\n(LocalProductParser & MatchingEngine)"]
        DB[(Local SQLite Database\nRoom-compatible DAO)]
        PaymentAdapter["PayPal Payment Adapter\n(Smart Routing & GST Calculation)"]
    end

    subgraph Backend_Gateway["Visual Cart Gateway API (FastAPI)"]
        ProxyAI["Multimodal AI Endpoint\n(/api/ai/recognize)"]
        PayPalGateway["PayPal Orders v2 Client\n(/api/paypal/create-order & capture)"]
        MerchantAgg["Merchant Aggregator\n(/api/merchants/search)"]
    end

    subgraph Cloud_Services["Third-Party APIs & Retailers"]
        Gemini["Google Gemini 1.5 Flash\n(Multimodal Vision Model)"]
        PayPal["PayPal REST Sandbox\n(api-m.sandbox.paypal.com)"]
        Merchants["Retailers\n(Flipkart, Amazon, Croma, Reliance)"]
    end

    IG --> ShareSheet
    YT --> ShareSheet
    Chrome --> ShareSheet
    CTS --> ShareSheet

    ShareSheet --> UI
    TileSvc --> OverlaySvc
    OverlaySvc --> UI

    UI --> Parser
    Parser --> DB
    UI --> DB
    UI --> PaymentAdapter

    Parser -.->|Optional Cloud Vision| ProxyAI
    ProxyAI --> Gemini

    PaymentAdapter --> PayPalGateway
    PayPalGateway --> PayPal

    UI -.-> MerchantAgg
    MerchantAgg --> Merchants
```

---

## 2. Android Client Layered Architecture (Clean Architecture & MVVM)

The Android application strictly adheres to Clean Architecture separation of concerns:

```
+─────────────────────────────────────────────────────────────────────────────+
|                            PRESENTATION LAYER                               |
|                                                                             |
|  - Jetpack Compose Screens: HomeScreen, CartScreen, ProductDetailScreen     |
|  - System Ingestion UI: VisualCartShareActivity, ShareCaptureScreen         |
|  - Floating Overlay Modal: overlay_capture_popup.xml                        |
|  - Design System Components: VisualCartButton, ConfidenceIndicator,         |
|    MerchantLogoBadge, SpecTag, PriceHistoryGraph                            |
+──────────────────────────────────────┬──────────────────────────────────────+
                                       │
                                       ▼
+─────────────────────────────────────────────────────────────────────────────+
|                              DOMAIN LAYER                                   |
|                                                                             |
|  - Core Models: RecognizedProduct, MerchantListing, MatchResult,            |
|    OrderSummary, ParsedProductMetadata                                      |
|  - Repository Interfaces: CartRepository                                    |
|  - Business Use Cases: Match & Compare, Price Aggregation, GST Calculation  |
+──────────────────────────────────────┬──────────────────────────────────────+
                                       │
                                       ▼
+─────────────────────────────────────────────────────────────────────────────+
|                            FEATURE ENGINES                                  |
|                                                                             |
|  - AI & Vision: CloudProductUnderstandingProvider, LocalProductParser,       |
|    ProductMatchingEngine, NaturalLanguageCartFilter                         |
|  - Merchant Adapters: AmazonIndiaAdapter, FlipkartAdapter, CromaAdapter,    |
|    RelianceDigitalAdapter, MyntraAdapter, TataCliqAdapter                   |
|  - Payment Engine: PayPalSandboxPaymentAdapter, PaymentProvider             |
+──────────────────────────────────────┬──────────────────────────────────────+
                                       │
                                       ▼
+─────────────────────────────────────────────────────────────────────────────+
|                         DATA & LOCAL STORAGE LAYER                          |
|                                                                             |
|  - Relational SQLite Engine: VisualCartDatabase                             |
|  - Data Access Object: CartDao                                              |
|  - Entities: SavedProductEntity, MerchantListingEntity, CollectionEntity,   |
|    PriceObservationEntity                                                   |
|  - Reactive Streams: Kotlin Flow & StateFlow                                |
+─────────────────────────────────────────────────────────────────────────────+
```

---

## 3. Sequence Diagrams

### 3.1 Visual Capture & Multi-Merchant Discovery Flow

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant App as External App (Chrome / Instagram)
    participant Share as Android Sharesheet
    participant VC as VisualCartShareActivity
    participant AI as Gemini 1.5 Flash / LocalParser
    participant Engine as MerchantDiscoveryEngine
    participant DB as SQLite Database

    User->>App: Finds product & taps Share
    App->>Share: Launches ACTION_SEND (Image / URL)
    Share->>User: Displays "Add to Visual Cart" Shortcut
    User->>Share: Taps Visual Cart Shortcut
    Share->>VC: Ingests Intent & Content URI
    VC->>VC: Copies stream to safe private cache
    VC->>AI: analyzeVisualProduct(image, text)
    AI-->>VC: Returns Canonical Product (Crucial P3 Plus 1TB SSD)
    VC->>Engine: searchAll(canonicalTitle, specs)
    par Parallel Merchant Queries
        Engine->>Engine: Query Flipkart (₹5,499)
        Engine->>Engine: Query Amazon India (₹5,799)
        Engine->>Engine: Query Croma (₹5,799)
    end
    Engine-->>VC: Returns sorted listings (Best Deal highlighted)
    VC->>User: Displays Instant Match Modal with ₹600 Savings
    User->>VC: Taps "Save to Cart"
    VC->>DB: Persists SavedProduct & MerchantListings
    DB-->>VC: Emits updated cart count
    VC->>User: Displays confirmation badge
```

---

### 3.2 PayPal Orders v2 Checkout Sequence

```mermaid
sequenceDiagram
    autonumber
    actor Buyer
    participant Client as Visual Cart App (Android)
    participant Adapter as PayPalSandboxPaymentAdapter
    participant Server as Visual Cart Gateway (FastAPI)
    participant PP as PayPal Sandbox REST API

    Buyer->>Client: Taps "Checkout with PayPal · ₹5,499"
    Client->>Adapter: initiateCheckout(listing)
    Adapter->>Adapter: calculateOrderSummary(Base: ₹4,660.17, GST: ₹838.83)
    Adapter->>Buyer: Prompts Order Confirmation Modal
    Buyer->>Adapter: Confirms Payment
    Adapter->>Server: POST /api/paypal/create-order
    Server->>PP: POST /v1/oauth2/token (Client Credentials)
    PP-->>Server: Bearer Access Token
    Server->>PP: POST /v2/checkout/orders (intent="CAPTURE", amount)
    PP-->>Server: Returns Order ID (e.g. 8B743912KL)
    Server-->>Adapter: Order Created
    Adapter->>Server: POST /api/paypal/capture-order (order_id)
    Server->>PP: POST /v2/checkout/orders/{id}/capture (PayPal-Request-Id header)
    PP-->>Server: Status: COMPLETED, Capture ID: CAP-9A127845KL
    Server-->>Adapter: Returns PaymentResult.Success
    Adapter-->>Client: Displays Authorized Transaction Badge
    Client->>Client: Updates Cart Item Status to PURCHASED
```

---

## 4. Relational Data Model & Entity-Relationship Diagram (ERD)

The persistent storage architecture implements Room-compatible relational tables with foreign key cascades:

```mermaid
erDiagram
    SAVED_PRODUCTS ||--o{ MERCHANT_LISTINGS : contains
    SAVED_PRODUCTS ||--o{ PRICE_OBSERVATIONS : tracks
    COLLECTIONS ||--o{ SAVED_PRODUCTS : categorizes

    SAVED_PRODUCTS {
        string id PK
        string title
        string brand
        string category
        string image_uri
        string original_source
        string canonical_product_id
        long created_at
        long updated_at
        boolean is_favorite
        boolean is_purchased
    }

    MERCHANT_LISTINGS {
        string id PK
        string saved_product_id FK
        string merchant
        string title
        float price
        string currency
        string product_url
        string image_url
        string match_type
        float confidence
        string availability
        string delivery_estimate
        boolean is_selected
        long last_verified_at
    }

    PRICE_OBSERVATIONS {
        string id PK
        string saved_product_id FK
        string merchant
        float price
        long observed_at
    }

    COLLECTIONS {
        string id PK
        string name
        string description
        string color_hex
        long created_at
    }
```

---

## 5. Security, Privacy & Network Boundaries

### 5.1 Content URI Security & Sandboxed Image Decoding
- Incoming image streams received via `ACTION_SEND` have transient read permissions granted by Android OS.
- `SafeImageDecoder.copyUriToPrivateCache` immediately creates an isolated copy within the app's sandboxed `cacheDir/shared_captures/`.
- Mitigates runtime `SecurityException` when originating apps terminate.
- Capped memory downsampling guarantees zero `OutOfMemoryError` conditions.

### 5.2 Strict URL Allowlisting & XSS Sanitization
- `SecurityUtils.sanitizeWebUrl` enforces an explicit protocol allowlist: only `https://` and `http://` are permitted.
- Custom URI schemes, JavaScript execution attempts (`javascript:`), and malformed intents are strictly rejected.
- Query tracking parameters (UTM parameters, referral tags) are sanitized before opening external merchant Custom Tabs.

### 5.3 Credential Isolation & Zero Hardcoded Secrets
- No client secret or API key is ever embedded into compiled APK binaries.
- The Android client communicates with the backend gateway, which maintains PayPal OAuth client credentials and Gemini AI tokens securely server-side.

### 5.4 Android 14/15 Foreground Service Policy
- `VisualCartOverlayService` is registered as `foregroundServiceType="specialUse"` with the required manifest declaration `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE`.
- An ongoing low-priority notification is maintained, providing users with instant 1-tap dismissal for full transparency.

---
*End of System Design & Technical Architecture.*
