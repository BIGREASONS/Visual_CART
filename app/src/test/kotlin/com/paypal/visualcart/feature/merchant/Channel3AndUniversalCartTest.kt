package com.paypal.visualcart.feature.merchant

import com.paypal.visualcart.core.util.CurrencyFormatter
import com.paypal.visualcart.domain.model.AvailabilityStatus
import com.paypal.visualcart.domain.model.ListingOrigin
import com.paypal.visualcart.domain.model.SourceType
import com.paypal.visualcart.feature.merchant.adapters.AmazonIndiaAdapter
import com.paypal.visualcart.feature.merchant.adapters.FlipkartAdapter
import com.paypal.visualcart.feature.payment.PayPalBackendClient
import com.paypal.visualcart.feature.payment.PayPalCheckoutCoordinator
import com.paypal.visualcart.feature.payment.PayPalCheckoutUiState
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalCoroutinesApi::class)
class Channel3AndUniversalCartTest {

    @Test
    fun parseDiscoveryResponseParsesChannel3OffersAndSkipsUnpricedSearchLinks() {
        val sampleJson = """
            {
              "ok": true,
              "query": "Logitech MX Master 3S",
              "channel3_configured": true,
              "channel3_used": true,
              "lowest_price": 62.99,
              "currency": "USD",
              "offer_source": "CHANNEL3_API",
              "offers": [
                {
                  "id": "c3_prod_mx3s_newegg",
                  "provider_product_id": "prod_mx3s",
                  "title": "Logitech MX Master 3S Wireless Mouse",
                  "merchant": "Newegg",
                  "merchant_id": "newegg.com",
                  "merchant_domain": "newegg.com",
                  "price": 62.99,
                  "originalPrice": 99.99,
                  "currency": "USD",
                  "status": "IN_STOCK",
                  "match_type": "EXACT",
                  "confidence": 0.96,
                  "isLowest": true,
                  "source_type": "LIVE",
                  "listing_origin": "LIVE_API",
                  "offer_source": "CHANNEL3_API",
                  "max_commission_rate": 0.04,
                  "url": "https://www.newegg.com/p/mx3s",
                  "image_url": "https://cdn.example.com/mx3s.jpg"
                },
                {
                  "id": "c3_prod_mx3s_target",
                  "provider_product_id": "prod_mx3s",
                  "title": "Logitech MX Master 3S Wireless Mouse",
                  "merchant": "Target",
                  "merchant_domain": "target.com",
                  "price": 79.99,
                  "currency": "USD",
                  "status": "OUT_OF_STOCK",
                  "match_type": "LIKELY",
                  "confidence": 0.88,
                  "listing_origin": "LIVE_API",
                  "offer_source": "CHANNEL3_API",
                  "url": "https://www.target.com/p/mx3s"
                },
                {
                  "merchant": "Amazon India",
                  "listing_origin": "DIRECT_SEARCH_URL",
                  "price": null,
                  "url": "https://www.amazon.in/s?k=Logitech"
                }
              ]
            }
        """.trimIndent()

        val parsed = Channel3DiscoveryClient.parseDiscoveryResponse(sampleJson)
        assertTrue(parsed.ok)
        assertTrue(parsed.channel3Used)
        assertEquals(2, parsed.listings.size)

        val newegg = parsed.listings[0]
        assertEquals("Newegg", newegg.merchantName)
        assertEquals(62.99, newegg.price!!, 0.001)
        assertEquals(99.99, newegg.originalPrice!!, 0.001)
        assertEquals("USD", newegg.currency)
        assertEquals("prod_mx3s", newegg.providerProductId)
        assertEquals("CHANNEL3_API", newegg.offerSource)
        assertEquals(0.04, newegg.commissionRate!!, 0.001)
        assertEquals(ListingOrigin.LIVE_API, newegg.listingOrigin)
        assertEquals(SourceType.LIVE, newegg.sourceType)
        assertTrue(newegg.isReferencePriceVerified)

        val target = parsed.listings[1]
        assertEquals(AvailabilityStatus.OUT_OF_STOCK, target.availability)
    }

    @Test
    fun channel3LiveProviderRanksLiveOffersAboveDirectSearchLinks() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var capturedRequestPath = ""
        server.createContext("/api/v1/products/search") { exchange ->
            capturedRequestPath = exchange.requestURI.path
            val body = """
                {
                  "ok": true,
                  "channel3_configured": true,
                  "channel3_used": true,
                  "lowest_price": 23.99,
                  "currency": "USD",
                  "offers": [
                    {
                      "id": "c3_pad_jcpenney",
                      "provider_product_id": "prod_pad_1",
                      "title": "World Map Extended Gaming Mouse Pad",
                      "merchant": "JCPenney",
                      "merchant_domain": "jcpenney.com",
                      "price": 23.99,
                      "originalPrice": 34.99,
                      "currency": "USD",
                      "status": "IN_STOCK",
                      "match_type": "EXACT",
                      "confidence": 0.94,
                      "isLowest": true,
                      "listing_origin": "LIVE_API",
                      "offer_source": "CHANNEL3_API",
                      "url": "https://www.jcpenney.com/p/pad"
                    }
                  ]
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/api/v1/products/prod_pad_1") { exchange ->
            val body = """
                {
                  "ok": true,
                  "product_id": "prod_pad_1",
                  "offers": [
                    {
                      "id": "c3_pad_jcpenney",
                      "provider_product_id": "prod_pad_1",
                      "title": "World Map Extended Gaming Mouse Pad",
                      "merchant": "JCPenney",
                      "merchant_domain": "jcpenney.com",
                      "price": 21.50,
                      "currency": "USD",
                      "status": "IN_STOCK",
                      "match_type": "EXACT",
                      "confidence": 0.94,
                      "isLowest": true,
                      "listing_origin": "LIVE_API",
                      "offer_source": "CHANNEL3_API",
                      "url": "https://www.jcpenney.com/p/pad-refreshed"
                    }
                  ]
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val client = Channel3DiscoveryClient(candidateUrlsSupplier = { listOf(baseUrl) })
            val c3Provider = Channel3LiveProvider(discoveryClient = client)
            val engine = MerchantDiscoveryEngine(
                listOf(c3Provider, AmazonIndiaAdapter().apply { isLiveMode = true }, FlipkartAdapter().apply { isLiveMode = true })
            )

            val listings = engine.searchAll("World Map Extended Gaming Mouse Pad")
            assertEquals("/api/v1/products/search", capturedRequestPath)
            assertTrue(listings.isNotEmpty())
            assertEquals("JCPenney", listings.first().merchantName)
            assertEquals(ListingOrigin.LIVE_API, listings.first().listingOrigin)
            assertEquals(23.99, listings.first().price!!, 0.001)
            assertTrue(listings.drop(1).all { it.listingOrigin == ListingOrigin.DIRECT_SEARCH_URL })

            val refreshed = client.refreshProduct("prod_pad_1", "US", "USD")
            assertTrue(refreshed.ok)
            assertEquals(21.50, refreshed.listings.first().price!!, 0.001)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun currencyFormatterFormatsUsdDecimalsAndWholeInrCleanly() {
        assertEquals("$62.99", CurrencyFormatter.format(62.99, "USD"))
        assertEquals("$63.00", CurrencyFormatter.format(63.0, "USD"))
        assertEquals("£49.50", CurrencyFormatter.format(49.5, "GBP"))
        assertEquals("€59.00", CurrencyFormatter.format(59.0, "EUR"))
        assertEquals("₹2,254", CurrencyFormatter.format(2254.0, "INR"))
        assertEquals("₹2,254.50", CurrencyFormatter.format(2254.5, "INR"))
    }

    @Test
    fun startCartCheckoutSubmitsMultiItemOrderToBackend() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var receivedRequestBody = ""
        server.createContext("/health") { exchange ->
            val ok = """{"status":"healthy"}""".toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, ok.size.toLong())
            exchange.responseBody.use { it.write(ok) }
        }
        server.createContext("/api/v1/payments/paypal/create-cart-order") { exchange ->
            receivedRequestBody = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).readText()
            val responseJson = """
                {
                  "order_id": "9CART12345TEST",
                  "status": "CREATED",
                  "approve_url": "https://www.sandbox.paypal.com/checkoutnow?token=9CART12345TEST",
                  "currency": "USD",
                  "amount": 119.85,
                  "is_live_sandbox": true,
                  "order_summary": {
                    "subtotal": 110.97,
                    "tax": 8.88,
                    "delivery_fee": 0.0,
                    "total": 119.85,
                    "currency": "USD",
                    "item_count": 3
                  }
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseJson.size.toLong())
            exchange.responseBody.use { it.write(responseJson) }
        }
        server.start()
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val dispatcher = StandardTestDispatcher(testScheduler)
            val backendClient = PayPalBackendClient(configuredBaseUrl = baseUrl)
            val coordinator = PayPalCheckoutCoordinator(
                backendClient = backendClient,
                scope = TestScope(dispatcher),
                ioDispatcher = dispatcher
            )

            val items = listOf(
                PayPalBackendClient.CartOrderRequestItem(
                    title = "Logitech MX Master 3S",
                    unitPrice = 62.99,
                    quantity = 1,
                    currency = "USD",
                    merchantName = "Newegg",
                    offerSource = "CHANNEL3_API"
                ),
                PayPalBackendClient.CartOrderRequestItem(
                    title = "World Map Mouse Pad",
                    unitPrice = 23.99,
                    quantity = 2,
                    currency = "USD",
                    merchantName = "JCPenney",
                    offerSource = "CHANNEL3_API"
                )
            )
            coordinator.startCartCheckout(items = items)
            advanceUntilIdle()

            assertTrue(receivedRequestBody.contains("Logitech MX Master 3S"))
            assertTrue(receivedRequestBody.contains("JCPenney"))
            val state = coordinator.uiState.value as? PayPalCheckoutUiState.OrderReady
            assertNotNull(state)
            assertEquals("9CART12345TEST", state!!.orderId)
            assertEquals("USD", state.currency)
            assertEquals(3, state.quantity)
            assertEquals(110.97, state.subtotal, 0.001)
            assertEquals(119.85, state.finalTotal, 0.001)
            assertTrue(state.isCartCheckout)
            assertTrue(state.isLiveSandbox)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun startCartCheckoutRejectsEmptyOrUnpricedCartItems() {
        val coordinator = PayPalCheckoutCoordinator(backendClient = PayPalBackendClient())
        coordinator.startCartCheckout(items = emptyList())
        val state = coordinator.uiState.value as? PayPalCheckoutUiState.Failure
        assertNotNull(state)
        assertEquals("ERR_PRICE_UNAVAILABLE", state!!.errorCode)
        assertFalse(state.canRetry)
    }
}
