package com.paypal.visualcart

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object OnboardingNavKey : NavKey

@Serializable data object HomeNavKey : NavKey

@Serializable data object CartNavKey : NavKey

@Serializable data class ProductDetailNavKey(val productId: String) : NavKey

@Serializable data object SettingsNavKey : NavKey
