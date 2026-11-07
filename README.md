# PayPal Visual Cart

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%2014%2B%20%7C%20API%2036-356B57?style=for-the-badge&logo=android&logoColor=white" alt="Android API 36" />
  <img src="https://img.shields.io/badge/Language-Kotlin%202.2%2B-202B32?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin 2.2" />
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose%20%7C%20M3-356B57?style=for-the-badge&logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
  <img src="https://img.shields.io/badge/Payment-PayPal%20Orders%20v2-003087?style=for-the-badge&logo=paypal&logoColor=white" alt="PayPal Orders v2" />
  <img src="https://img.shields.io/badge/AI-Google%20Gemini%201.5%20Flash-4285F4?style=for-the-badge&logo=google&logoColor=white" alt="Gemini 1.5 Flash" />
  <img src="https://img.shields.io/badge/License-MIT-C97858?style=for-the-badge" alt="License MIT" />
  <img src="https://img.shields.io/badge/Tests-100%25%20Passing-356B57?style=for-the-badge" alt="Tests 100% Passing" />
</p>

> **"See it. Save it. Find it. Pay with PayPal."**

An Android shopping companion that bridges visual product discovery across any mobile app (Instagram, YouTube, Google Circle to Search, TikTok, web browsers) into a unified, persistent shopping cart with cross-merchant price comparison and PayPal checkout.

---

## 🎨 Editorial Fintech Design Palette

PayPal Visual Cart breaks away from generic e-commerce templates, employing a bespoke **Editorial Fintech** design language:

| Color Role | Hex Code | Visual Sample | Application |
| :--- | :---: | :---: | :--- |
| **Warm Ivory** | `#F6F3EC` | ![#F6F3EC](https://via.placeholder.com/16/F6F3EC/000000?text=+) | Primary screen background, warm canvas |
| **Deep Ink** | `#202B32` | ![#202B32](https://via.placeholder.com/16/202B32/000000?text=+) | Primary high-contrast serif typography |
| **Forest Green** | `#356B57` | ![#356B57](https://via.placeholder.com/16/356B57/000000?text=+) | Primary brand accent, verified PayPal badge, primary CTAs |
| **Muted Sage** | `#A6B5A0` | ![#A6B5A0](https://via.placeholder.com/16/A6B5A0/000000?text=+) | Specification tags, subtle borders, secondary states |
| **Terracotta** | `#C97858` | ![#C97858](https://via.placeholder.com/16/C97858/000000?text=+) | Verified price drop callouts, urgency highlights |

---

## 📖 Complete Documentation Index

| Document | Description |
| :--- | :--- |
| **[`HACKATHON_COMPLIANCE.md`](file:///c:/Users/singh/Documents/antigravity/ez/HACKATHON_COMPLIANCE.md)** | Comprehensive checklist of all Devpost criteria (Technological Implementation, Design, Impact, Innovation, Presentation), evidence, and status. |
| **[`DEVPOST_SUBMISSION.md`](file:///c:/Users/singh/Documents/antigravity/ez/DEVPOST_SUBMISSION.md)** | Full hackathon submission draft including project title, tagline, problem statement, architecture, AI tools used, PayPal capabilities used, and impact narrative. |
| **[`DEMO_SCRIPT.md`](file:///c:/Users/singh/Documents/antigravity/ez/DEMO_SCRIPT.md)** | Exact video demo script and storyboard strictly under 3 minutes (target ~2m40s) detailing the exact 6 scenes. |
| **[`AI_ARCHITECTURE.md`](file:///c:/Users/singh/Documents/antigravity/ez/AI_ARCHITECTURE.md)** | Technical doc explaining multimodal vision model, prompt design, structured JSON output, canonicalization, and fallback. |
| **[`PAYPAL_INTEGRATION.md`](file:///c:/Users/singh/Documents/antigravity/ez/PAYPAL_INTEGRATION.md)** | Technical doc explaining Orders v2 API, OAuth, sandbox workflow, create-order, buyer approval, capture-order, and security guarantees. |
| **[`SETUP.md`](file:///c:/Users/singh/Documents/antigravity/ez/SETUP.md)** | Step-by-step developer instructions for running the Android app and the FastAPI backend with sandbox credentials. |
| **[`ARCHITECTURE.md`](file:///c:/Users/singh/Documents/antigravity/ez/ARCHITECTURE.md)** | System design, sequence diagrams, and relational database ERD. |
| **[`docs/reference-implementation-review.md`](file:///c:/Users/singh/Documents/antigravity/ez/docs/reference-implementation-review.md)** | Academic-grade report on Android Sharing, PayPal Orders v2, Gemini multimodal, and UCP / Google AP2. |
| **[`LICENSE`](file:///c:/Users/singh/Documents/antigravity/ez/LICENSE)** | Official MIT License (2026). |
| **[`.env.example`](file:///c:/Users/singh/Documents/antigravity/ez/.env.example)** | Safe configuration template for backend and client endpoints. |

---

## 1. System Architecture

PayPal Visual Cart is built using **Clean Architecture** and **MVVM** principles on native modern Android (Kotlin 2.2+, Jetpack Compose, Material 3, SQLite Reactive Storage), paired with a **FastAPI** gateway:

```
+───────────────────────────────────────────────────────────────────────────────────+
|                                ANDROID SYSTEM                                     |
|  +---------------------------+  +----------------------------+  +---------------+ |
|  | Quick Settings Tile       |  | System Alert Window        |  | Direct Share  | |
|  | (VisualCartTileService)   |  | (VisualCartOverlayService) |  | (ACTION_SEND) | |
|  +-------------+-------------+  +--------------+-------------+  +-------+-------+ |
+----------------|-------------------------------|------------------------|---------+
                 |                               |                        |
                 v                               v                        v
+───────────────────────────────────────────────────────────────────────────────────+
|                           APPLICATION INTEGRATION LAYER                           |
|  +-----------------------------------------------------------------------------+  |
|  | CaptureEventBus: Broadcasts capture triggers (Screenshot, URL, Intent)      |  |
|  | SafeImageDecoder: Memory-safe subsampling, isolates stream to private cache |  |
|  +-----------------------------------------------------------------------------+  |
+────────────────────────────────────────┬──────────────────────────────────────────+
                                         |
                                         v
+───────────────────────────────────────────────────────────────────────────────────+
|                                 CORE SERVICES                                     |
|  +───────────────────────────────+           +─────────────────────────────────+  |
|  | Visual Product Recognition    |           | Merchant Discovery Engine       |  |
|  | - Google Gemini 1.5 Flash     |           | - Amazon India, Flipkart        |  |
|  | - LocalProductParser (Regex)  |           | - Croma, Reliance Digital       |  |
|  | - ProductMatchingEngine       |           | - Myntra, Tata CLiQ             |  |
|  +---------------+---------------+           +----------------+────────────────+  |
|                  \                                           /                    |
|                   \                                         /                     |
|                    v                                       v                      |
|  +─────────────────────────────────────────────────────────────────────────────+  |
|  | CartRepository & SQLite Database (Room-compatible relational architecture)  |  |
|  | Tables: saved_products, merchant_listings, collections, price_observations   |  |
|  +─────────────────────────────────────┬───────────────────────────────────────+  |
+────────────────────────────────────────|──────────────────────────────────────────+
                                         |
                                         v
+───────────────────────────────────────────────────────────────────────────────────+
|                             PAYPAL PAYMENT INTEGRATION                            |
|  +─────────────────────────────────────────────────────────────────────────────+  |
|  | PayPal Orders v2 API: OAuth 2.0, Order Creation, Buyer Approval, Capture   |  |
|  | PayPalSandboxPaymentAdapter: Order summary, GST calculation, authorization  |  |
|  | External Checkout Fallback: Verified merchant URL with security disclaimer  |  |
|  +─────────────────────────────────────────────────────────────────────────────+  |
+───────────────────────────────────────────────────────────────────────────────────+
```

---

## 2. Key Android System Integrations

### A. Google Circle to Search & Direct Share (`VisualCartShareActivity.kt`)
- **Sanctioned Android Architecture**: Google Circle to Search isolates screen content securely. When a user selects a product via Circle to Search and taps "Share", or shares an image/link from Instagram, YouTube, Chrome, or Twitter, Android launches the system share sheet.
- `SharingShortcutsManager` registers dynamic shortcuts via `ShortcutManagerCompat`, giving Visual Cart top-row Direct Share prominence.
- Safely reads `Intent.EXTRA_TEXT`, `Intent.EXTRA_STREAM`, and `ClipData` content URIs.
- `SafeImageDecoder` immediately copies the content stream to sandboxed private app storage, guarding against revoked OS permissions.

### B. Quick Settings Tile (`VisualCartTileService.kt`)
- Declared with `android.permission.BIND_QUICK_SETTINGS_TILE` and action `android.service.quicksettings.action.QS_TILE`.
- Displays **VISUAL CART** with the cart icon (`ic_quick_settings_cart.xml`).
- Single tap toggles the floating bubble on/off.
- Android 14+ compliant permission flow if overlay permissions are pending.

### C. Floating Shopping Cart Bubble (`VisualCartOverlayService.kt`)
- 56dp circular Forest Green (`#356B57`) floating button with warm ivory icon.
- Soft elevation shadow, smooth entry animation, spring edge-snapping physics.
- Respects system window insets, status bar, navigation bar, and display cutouts.
- Complies with Android 14/15 foreground service regulations (`FOREGROUND_SERVICE_SPECIAL_USE` with declared manifest property).
- 4-Action Quick Modal:
  1. **Add from screenshot**: Launches system Photo Picker to select captured products.
  2. **Paste product link**: Quick input dialog to resolve and scrape merchant links.
  3. **Open my cart**: Brings the main Visual Cart application to foreground.
  4. **Hide floating button**: Dismisses overlay cleanly.

---

## 3. Product Recognition & Matching Engine

- **Cloud Multimodal Vision (`CloudProductUnderstandingProvider.kt`)**:
  - Leverages Google Gemini 1.5 Flash (`gemini-1.5-flash`) via structured JSON schema output (`generationConfig.response_mime_type = "application/json"`).
  - Extracts canonical title, brand, category, and technical hardware specifications without conversational filler.
- **On-Device Spec Parser (`LocalProductParser.kt`)**:
  - Regex pattern matching for electronic capacities (e.g., `1TB`, `512GB`, `2TB`), memory types (`NVMe`, `PCIe 4.0`, `M.2 2280`), RAM, form factors, apparel sizes (`UK 9`, `US 10`), and model numbers.
  - Removes common marketing noise (e.g., "Best Deal", "Limited Offer", "Free Delivery").
- **Product Matching Engine (`ProductMatchingEngine.kt`)**:
  - Classifies candidate matches into `EXACT`, `LIKELY`, or `SIMILAR`.
  - Computes weighted confidence scores (0.0 to 1.0) with human-readable explainability badges (e.g., *"Brand & storage capacity match exact specifications"*).
- **Dual-Tier Offline Resilience**:
  - If network connectivity is lost or API quotas are exhausted, the app seamlessly falls back to on-device parsing with zero crash risk.

---

## 4. Multi-Merchant Discovery Engine

- **Supported Merchants**:
  - **Amazon India** (`amazon.in`)
  - **Flipkart** (`flipkart.com`)
  - **Croma** (`croma.com`)
  - **Reliance Digital** (`reliancedigital.in`)
  - **Myntra** (`myntra.com`)
  - **Tata CLiQ** (`tatacliq.com`)
- **Comparison Engine (`MerchantDiscoveryEngine.kt`)**:
  - Queries merchant adapters in parallel with coroutines.
  - Normalizes prices to Indian Rupee (INR ₹).
  - Highlights lowest price with savings indicator (e.g., *"Save ₹600 compared to average retail"*).
  - Detects stock availability (`IN_STOCK`, `LOW_STOCK`, `OUT_OF_STOCK`).
  - Strict URL sanitizer strips untrusted query parameters and prevents open-redirect vulnerabilities.

---

## 5. PayPal Payment Integration

- **PayPal Orders v2 API**:
  - OAuth 2.0 Client Credentials token exchange with in-memory caching and safety expiration buffer.
  - Order creation (`POST /v2/checkout/orders`) with `intent: "CAPTURE"`, transparent itemized subtotal, and 18% GST calculation.
  - Idempotent order capture (`POST /v2/checkout/orders/{id}/capture`) with `PayPal-Request-Id` HTTP headers.
- **Smart Routing Architecture**:
  - **Path A (Integrated Partner Checkout)**: Native simulated sandbox authorization, transparent GST calculation, authorized transaction ID generation, covered by PayPal Buyer Protection.
  - **Path B (External Merchant Redirect)**: For non-integrated stores, launches Android Chrome Custom Tabs to the merchant listing with an explicit security disclaimer.

---

## 6. Verification & Test Suite

The test suite exercises all security, matching, parsing, and merchant logic:

| Test Class | Scope & Verification | Status |
| :--- | :--- | :---: |
| `SecurityUtilsTest` | Strict URL validation, scheme validation, XSS prevention, Content URI security | **PASS** |
| `ProductMatchingEngineTest` | Exact / Likely / Similar classification and weighted confidence scoring | **PASS** |
| `LocalProductParserTest` | Capacity (1TB), NVMe, PCIe 4.0, and spec regex extraction accuracy | **PASS** |
| `NaturalLanguageCartFilterTest` | Natural language filtering ("SSD under 6000", "Shoes") | **PASS** |
| `MerchantAdaptersTest` | Search URL generation and merchant listing normalization | **PASS** |
| `PayPalPaymentAdapterTest` | Order breakdown, GST (18%) computation, sandbox authorization | **PASS** |
| `CartEntityMappingTest` | Database entity to domain model mapping and relational integrity | **PASS** |

---

## 7. Build & Quickstart Instructions

### Prerequisites
- Android SDK 36 (`compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`)
- JDK 17
- Python 3.10+ (for FastAPI backend)

### Step 1: Configure Environment
```powershell
Copy-Item .env.example .env
```

### Step 2: Run Unit Tests & Build Android APK
```powershell
# Run unit tests
.\gradlew.bat testDebugUnitTest

# Assemble debug APK
.\gradlew.bat assembleDebug

# Install onto connected device or emulator
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Step 3: Run the FastAPI Backend (Optional)
```powershell
python -m venv venv
.\venv\Scripts\Activate.ps1
pip install -r backend/requirements.txt
uvicorn backend.main:app --host 0.0.0.0 --port 8000 --reload
```

---

## 📄 License

This project is licensed under the MIT License — see the [LICENSE](file:///c:/Users/singh/Documents/antigravity/ez/LICENSE) file for details.
