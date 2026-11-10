# PayPal Orders v2 API Integration & Checkout Architecture

> **Document Version**: 1.0.0  
> **Date**: November 2026  
> **Project**: PayPal Visual Cart  
> **Design Theme**: Editorial Fintech (Warm Ivory `#F6F3EC`, Deep Ink `#202B32`, Forest Green `#356B57`, Muted Sage `#A6B5A0`, Terracotta `#C97858`)

---

## 1. Executive Summary & PayPal Value Proposition

**PayPal Visual Cart** integrates PayPal’s modern **Orders v2 REST API** to solve one of mobile e-commerce’s greatest challenges: cart fragmentation and checkout abandonment.

By turning discovery in any app into a unified visual cart, PayPal Visual Cart positions PayPal not merely as a payment button on individual checkout pages, but as the **universal commerce front door and settlement rail** across all online retailers.

```
+─────────────────────────────────────────────────────────────────────────────+
|                        PAYPAL COMMERCE ADVANTAGE                            |
|                                                                             |
|  [ Walled Garden Discoveries ]  ──> [ Visual Cart ] ──> [ PayPal Rail ]     |
|  - Instagram, YouTube, TikTok       - Normalizes specs   - 1-Click Checkout |
|  - Google Circle to Search          - Finds lowest price - Buyer Protection |
|  - Chrome, Web Articles             - Aggregates cart    - GST Transparency |
+─────────────────────────────────────────────────────────────────────────────+
```

---

## 2. End-to-End Transaction Lifecycle Architecture

The checkout architecture decouples client-side UI rendering from server-side credential management:

```
[ Android Client ]           [ Visual Cart Backend ]           [ PayPal Sandbox REST API ]
       │                                │                                    │
       │ 1. User taps "Checkout"        │                                    │
       ├───────────────────────────────>│                                    │
       │                                │ 2. POST /v1/oauth2/token           │
       │                                │    (Basic Auth: ID + Secret)       │
       │                                ├───────────────────────────────────>│
       │                                │<───────────────────────────────────┤
       │                                │    Returns Bearer Access Token     │
       │                                │                                    │
       │                                │ 3. POST /v2/checkout/orders        │
       │                                │    (intent="CAPTURE", amount, GST) │
       │                                ├───────────────────────────────────>│
       │                                │<───────────────────────────────────┤
       │                                │    Returns Order ID & Approve URL  │
       │ 4. Receives Order Details      │                                    │
       │<───────────────────────────────┤                                    │
       │                                                                     │
       │ 5. Buyer Confirms in PayPal UI / Dialog                             │
       │                                                                     │
       │ 6. User Approves Order         │                                    │
       ├───────────────────────────────>│                                    │
       │                                │ 7. POST /v2/checkout/orders/{id}/capture
       │                                │    (PayPal-Request-Id: <UUID>)     │
       │                                ├───────────────────────────────────>│
       │                                │<───────────────────────────────────┤
       │                                │    Status: COMPLETED, Capture ID   │
       │ 8. Order Confirmed & Stored    │                                    │
       │<───────────────────────────────┤                                    │
```

---

## 3. OAuth 2.0 Client Credentials Grant

All requests to PayPal’s REST API require an active OAuth 2.0 bearer token. Visual Cart executes the Client Credentials grant:

### 3.1 Token Request
```http
POST /v1/oauth2/token HTTP/1.1
Host: api-m.sandbox.paypal.com
Authorization: Basic <base64(PAYPAL_CLIENT_ID:PAYPAL_CLIENT_SECRET)>
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
```

### 3.2 Token Caching Implementation (`backend/paypal_client.py`)
To prevent redundant roundtrips on every checkout interaction, Visual Cart caches tokens in memory with an expiration buffer:

```python
if self._cached_token and time.time() < (self._token_expires_at - 60):
    return self._cached_token
```

---

## 4. Order Creation Lifecycle (`POST /v2/checkout/orders`)

### 4.1 Order Request Schema
Visual Cart creates orders with `intent: "CAPTURE"` and transparent itemized breakdowns:

```json
{
  "intent": "CAPTURE",
  "purchase_units": [
    {
      "reference_id": "visual_cart_item_001",
      "description": "Crucial P3 Plus 1TB PCIe 4.0 3D NAND NVMe M.2 SSD",
      "custom_id": "merchant_flipkart_in",
      "amount": {
        "currency_code": "USD",
        "value": "64.88",
        "breakdown": {
          "item_total": {
            "currency_code": "USD",
            "value": "54.99"
          },
          "tax_total": {
            "currency_code": "USD",
            "value": "9.89"
          },
          "shipping": {
            "currency_code": "USD",
            "value": "0.00"
          }
        }
      }
    }
  ],
  "application_context": {
    "brand_name": "PayPal Visual Cart",
    "landing_page": "NO_PREFERENCE",
    "shipping_preference": "GET_FROM_FILE",
    "user_action": "PAY_NOW",
    "return_url": "https://visualcart.paypal.com/checkout/return",
    "cancel_url": "https://visualcart.paypal.com/checkout/cancel"
  }
}
```

### 4.2 Indian E-Commerce GST & Order Calculation (`PayPalSandboxPaymentAdapter.kt`)
For domestic Indian commerce demonstrations, prices are normalized in INR with explicit Goods & Services Tax (GST) calculations:
- **Base Subtotal**: $P_{\text{base}} = \frac{P_{\text{total}}}{1 + r_{\text{gst}}}$
- **GST Amount (18%)**: $A_{\text{gst}} = P_{\text{total}} - P_{\text{base}}$
- **Delivery**: Transparently shown as Free (₹0.00) under Visual Cart promotion.

```kotlin
val summary = OrderSummary.calculate(
    listing = listing,
    gstRate = 0.18,
    deliveryFee = 0.0
)
```

Example breakdown for Crucial P3 Plus (₹5,499 Total):
- Item Base Subtotal: **₹4,660.17**
- GST @ 18%: **₹838.83**
- Total Authorized: **₹5,499.00**

---

## 5. Order Capture & Idempotency Guarantee

### 5.1 Capture Request (`POST /v2/checkout/orders/{id}/capture`)
Once the buyer approves the order, the gateway issues the capture call to finalize fund movement:

```http
POST /v2/checkout/orders/8B743912KL893412P/capture HTTP/1.1
Host: api-m.sandbox.paypal.com
Authorization: Bearer <access_token>
PayPal-Request-Id: 9f8a3c20-7b3e-4d52-9c12-32a10486be2a
Content-Type: application/json
Prefer: return=representation

{}
```

### 5.2 Idempotency via `PayPal-Request-Id`
Mobile connections frequently drop or switch between Wi-Fi and 5G mid-transaction. Visual Cart attaches a unique UUID v4 as `PayPal-Request-Id`. PayPal guarantees:
- If a capture request is received multiple times with the same request ID within 72 hours, PayPal returns the existing capture response without re-executing charges.
- Prevents double-charging bugs during connectivity hiccups.

### 5.3 Capture Response Structure
```json
{
  "id": "8B743912KL893412P",
  "status": "COMPLETED",
  "purchase_units": [
    {
      "payments": {
        "captures": [
          {
            "id": "CAP-9A127845KL",
            "status": "COMPLETED",
            "amount": {
              "currency_code": "USD",
              "value": "64.88"
            },
            "final_capture": true,
            "seller_protection": {
              "status": "ELIGIBLE"
            }
          }
        ]
      }
    }
  ]
}
```

---

## 6. Smart Routing Architecture: Dual-Path Checkout

Visual Cart implements an intelligent dual-path routing engine (`PayPalSandboxPaymentAdapter.kt`):

```
                       [ Initiate Checkout ]
                                 │
                                 ▼
                     [ Smart Routing Engine ]
                                 │
        ┌────────────────────────┴────────────────────────┐
        ▼                                                 ▼
[ Path A: Integrated Checkout ]           [ Path B: External Merchant Redirect ]
- Eligible partner & sandbox stores        - Third-party external stores
- Generates itemized order breakdown      - Chrome Custom Tabs with disclaimer
- Direct PayPal sandbox auth code          - Never touches merchant passwords
- Returns PaymentResult.Success            - Returns CheckoutResult.ExternalRedirect
```

### Path A: Integrated Sandbox Checkout
- Used for integrated partners and demonstration items.
- Itemized summary, explicit buyer confirmation modal, sandbox transaction ID generation (`PP-IND-SBX-XXXXXXXXXXXX`).

### Path B: External Merchant Redirect
- For retailers requiring direct web checkout, Visual Cart launches Android Chrome Custom Tabs to the sanitized product listing URL.
- Displays an explicit security disclaimer:
  > *"You are being redirected to Flipkart (flipkart.com). Your transaction and credentials will be processed directly on the merchant's secure platform. PayPal Visual Cart never stores external merchant passwords or payment details."*

---

## 7. Security, Privacy & Compliance Guarantees

1. **Zero Credential Storage (PCI-DSS Scoping)**:
   Visual Cart never collects, transmits, or stores credit card numbers, CVVs, or PayPal passwords. All sensitive payment interactions occur within PayPal’s secure sandbox domain or the merchant’s official portal.
2. **Strict URL Sanitization**:
   All merchant URLs are sanitized via `SecurityUtils.sanitizeWebUrl` to prevent open-redirect exploits and strip tracking parameters.
3. **No Hardcoded Sandbox Keys**:
   Credentials resolve at runtime via `.env` variables or server-side configuration.
4. **PayPal Buyer Protection**:
   All integrated transactions explicitly highlight PayPal Buyer Protection coverage, increasing buyer confidence.

---

## 8. Official PayPal Developer Sandbox Verification Guide

Following the official PayPal Developer guidelines ([paypaldev/getting-started-with-paypal-sandbox](https://github.com/paypaldev/getting-started-with-paypal-sandbox)):

### 8.1 Sandbox Accounts Setup
1. **Developer Dashboard**: Log in to [developer.paypal.com](https://developer.paypal.com) using your primary PayPal account.
2. **Access Test Accounts**: Navigate to **Testing Tools** &rarr; **Sandbox Accounts** (`developer.paypal.com/dashboard/accounts`).
   - A **Business Account** (`sb-xxxx-biz@business.example.com`): Represents the merchant receiving payments.
   - A **Personal Account** (`sb-xxxx-buyer@personal.example.com`): Represents the shopper purchasing items with fake sandbox money.
3. **App Credentials**: Under **Apps & Credentials** &rarr; **Sandbox**, copy the `Client ID` and `Secret` into `.env`:
   ```bash
   PAYPAL_CLIENT_ID=your_sandbox_client_id
   PAYPAL_CLIENT_SECRET=your_sandbox_secret
   PAYPAL_ENVIRONMENT=sandbox
   ```

### 8.2 End-to-End Buyer Approval & Capture Flow
1. **Create Order**: Client calls `POST /api/v1/payments/paypal/create-order` with the selected item.
2. **Buyer Approval**: Android app opens PayPal sandbox checkout (`https://www.sandbox.paypal.com/checkoutnow?token=ORDER_ID`) in Chrome Custom Tabs.
   - Log in with the **Personal Sandbox Account** credentials.
   - Tap **Complete Purchase** to approve.
3. **Redirect & Capture**: Custom Tabs redirects to `visualcart://paypal-return?token=ORDER_ID&PayerID=PAYER_ID`. The backend captures payment via `POST /v2/checkout/orders/{id}/capture` and returns a real PayPal Capture ID (`CAP-XXXXXXXXXX`).

### 8.3 Verifying Settlement in PayPal Sandbox Portal
To verify the transaction settled with fake funds:
1. Open [www.sandbox.paypal.com](https://www.sandbox.paypal.com).
2. Log in using the **Business Account** (`sb-xxxx-biz@business.example.com`).
3. View **Recent Activity** and **PayPal Balance**:
   - The captured payment appears with the exact gross amount, transaction fee, and net balance increase.
   - Click the transaction to inspect the PayPal Capture ID matching the one displayed in Visual Cart.

### 8.4 Sandbox Negative Testing & Security Hardening

Visual Cart implements official PayPal Orders v2 Sandbox Negative Testing via the `PayPal-Mock-Response` header:

#### 1. Supported Negative Testing Scenarios
- **INSTRUMENT_DECLINED**: Simulates funding instrument decline on payment capture (`POST /v2/checkout/orders/{id}/capture`).
  ```http
  PayPal-Mock-Response: {"mock_application_codes": "INSTRUMENT_DECLINED"}
  ```
  PayPal Sandbox responds with HTTP 422 `UNPROCESSABLE_ENTITY`:
  ```json
  {
    "name": "UNPROCESSABLE_ENTITY",
    "details": [
      {
        "issue": "INSTRUMENT_DECLINED",
        "description": "The instrument presented was declined. Please try another instrument."
      }
    ],
    "message": "The requested action could not be performed, semantically incorrect, or failed business validation.",
    "debug_id": "sbx_dbg_4f89d12a3e"
  }
  ```
  The backend parses this error envelope, logs the diagnostics (`debug_id` and `issue`) without exposing credentials, and returns a structured recovery prompt for the mobile client.
- **DUPLICATE_INVOICE_ID**: Simulates attempt to process an invoice that was previously captured.
  ```http
  PayPal-Mock-Response: {"mock_application_codes": "DUPLICATE_INVOICE_ID"}
  ```
  Returns HTTP 422 with issue `DUPLICATE_INVOICE_ID` and corresponding `debug_id`.

#### 2. Duplicate Capture Idempotency via `PayPal-Request-Id`
Mobile clients attach a unique UUID v4 as `PayPal-Request-Id` (or `idempotency_key`):
- All capture transactions are cached in-memory and matched against `PayPal-Request-Id`.
- Duplicate calls with identical request IDs immediately return the verified completed transaction without re-executing charges.
- Double-capture attempts on previously captured order IDs are proactively detected, returning `already_captured = true` and `duplicate_capture_prevented = true`.

#### 3. Diagnostic Debug ID Logging (Zero Secret Leakage)
PayPal's official error envelope is parsed to extract `debug_id`, `issue`, and `description`. Diagnostics are logged at `ERROR` level:
```text
PayPal API error envelope parsed: issue='INSTRUMENT_DECLINED', debug_id='sbx_dbg_4f89d12a3e', desc='The instrument presented was declined. Please try another instrument.'
```
**Strict Privacy Guarantee**: Sensitive tokens (Bearer tokens, Client Credentials, PayPal passwords, user secrets) are strictly excluded from logs, error responses, and client-facing exceptions.

#### 4. Sandbox-Only Security Enforcement
Negative testing headers can cause severe disruption if activated against live transaction rails. Visual Cart enforces compile-time and runtime isolation:
- The `PayPal-Mock-Response` header and parameter are **strictly permitted ONLY when `PAYPAL_MODE == "sandbox"`**.
- In live or production environments (`PAYPAL_MODE == "live"` or `PAYPAL_MODE == "production"`), any request containing `PayPal-Mock-Response` is immediately rejected with `HTTP 400 Bad Request`:
  > *"PayPal-Mock-Response header is strictly forbidden when PAYPAL_MODE is not 'sandbox'"*
- At the low-level API client (`PayPalClient`), a `ValueError` is thrown before any network call can be initiated.

---
*End of PayPal Integration Documentation.*
