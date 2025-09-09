package com.dooboolab.rniap

import com.facebook.react.bridge.WritableMap
import com.facebook.react.bridge.WritableNativeMap
import ru.rustore.sdk.pay.model.Product
import ru.rustore.sdk.pay.model.Purchase
import ru.rustore.sdk.pay.model.PurchaseState
import ru.rustore.sdk.pay.model.SubscriptionPeriod
import java.text.SimpleDateFormat
import java.util.*

object RuStoreUtils {
    
    fun convertProductToMap(product: Product): WritableMap {
        return WritableNativeMap().apply {
            putString("productId", product.productId)
            putString("type", when(product.productType) {
                Product.Type.CONSUMABLE -> "inapp"
                Product.Type.NON_CONSUMABLE -> "inapp"
                Product.Type.SUBSCRIPTION -> "subs"
                else -> "inapp"
            })
            putString("price", product.priceLabel ?: "")
            putDouble("price_amount_micros", product.price?.toDouble() ?: 0.0)
            putString("price_currency_code", product.currency ?: "RUB")
            putString("title", product.title ?: "")
            putString("description", product.description ?: "")
            putString("localizedPrice", product.priceLabel ?: "")
            putString("currency", product.currency ?: "RUB")
            
            // Subscription-specific fields
            product.subscription?.let { sub ->
                putString("subscriptionPeriodAndroid", convertPeriodToString(sub.subscriptionPeriod))
                sub.freeTrialPeriod?.let { trial ->
                    putString("freeTrialPeriodAndroid", convertPeriodToString(trial))
                }
                sub.gracePeriod?.let { grace ->
                    putString("gracePeriodAndroid", convertPeriodToString(grace))
                }
                sub.introductoryPriceInfo?.let { intro ->
                    putString("introductoryPrice", intro.priceLabel)
                    putDouble("introductoryPriceAmountMicros", intro.price?.toDouble() ?: 0.0)
                    putString("introductoryPricePeriodAndroid", convertPeriodToString(intro.period))
                    putInt("introductoryPriceCycles", intro.periodCount ?: 1)
                }
            }
            
            // Original JSON for compatibility
            putString("originalJson", product.toJsonString())
        }
    }
    
    fun convertPurchaseToMap(purchase: Purchase): WritableMap {
        return WritableNativeMap().apply {
            putString("productId", purchase.productId)
            putString("purchaseId", purchase.purchaseId)
            putString("orderId", purchase.orderId)
            putString("purchaseToken", purchase.purchaseId) // Using purchaseId as token
            putString("invoiceId", purchase.invoiceId)
            putDouble("purchaseTime", purchase.purchaseTime?.time?.toDouble() ?: 0.0)
            putString("purchaseState", convertPurchaseState(purchase.purchaseState))
            putInt("purchaseStateAndroid", purchase.purchaseState?.ordinal ?: 0)
            putString("developerPayload", purchase.developerPayload)
            putBoolean("isAcknowledged", purchase.purchaseState == PurchaseState.CONFIRMED)
            putString("packageNameAndroid", purchase.applicationCode)
            putString("signatureAndroid", purchase.signature ?: "")
            putInt("quantity", purchase.quantity ?: 1)
            
            // Add amountLabel if available
            purchase.amountLabel?.let {
                putString("amountLabel", it)
            }
            
            // Add amount if available
            purchase.amount?.let {
                putDouble("amount", it.toDouble())
            }
            
            // Add currency if available
            purchase.currency?.let {
                putString("currency", it)
            }
            
            // Original JSON for compatibility
            putString("originalJson", purchase.toJsonString())
            putString("transactionReceipt", purchase.toJsonString())
        }
    }
    
    fun createErrorMap(throwable: Throwable): WritableMap {
        val errorCode = when {
            throwable.message?.contains("user", ignoreCase = true) == true -> PromiseUtils.E_USER_CANCELLED
            throwable.message?.contains("network", ignoreCase = true) == true -> PromiseUtils.E_NETWORK_ERROR
            throwable.message?.contains("service", ignoreCase = true) == true -> PromiseUtils.E_SERVICE_ERROR
            throwable.message?.contains("item", ignoreCase = true) == true -> PromiseUtils.E_ITEM_UNAVAILABLE
            throwable.message?.contains("developer", ignoreCase = true) == true -> PromiseUtils.E_DEVELOPER_ERROR
            else -> PromiseUtils.E_UNKNOWN
        }
        
        return WritableNativeMap().apply {
            putString("code", errorCode)
            putString("message", throwable.message ?: "Unknown error")
            putString("debugMessage", throwable.stackTraceToString())
        }
    }
    
    fun createUserCancelledError(): WritableMap {
        return WritableNativeMap().apply {
            putString("code", PromiseUtils.E_USER_CANCELLED)
            putString("message", "User cancelled the purchase")
            putInt("responseCode", 1)
        }
    }
    
    private fun convertPeriodToString(period: SubscriptionPeriod?): String {
        period ?: return ""
        val unit = when {
            period.days > 0 -> "D"
            period.months > 0 -> "M"
            period.years > 0 -> "Y"
            else -> ""
        }
        val value = when {
            period.days > 0 -> period.days
            period.months > 0 -> period.months
            period.years > 0 -> period.years
            else -> 0
        }
        return "P$value$unit"
    }
    
    private fun convertPurchaseState(state: PurchaseState?): String {
        return when (state) {
            PurchaseState.CREATED -> "PENDING"
            PurchaseState.INVOICE_CREATED -> "PENDING"
            PurchaseState.PAID -> "PURCHASED"
            PurchaseState.CONFIRMED -> "PURCHASED"
            PurchaseState.CONSUMED -> "CONSUMED"
            PurchaseState.CANCELLED -> "CANCELLED"
            PurchaseState.PAUSED -> "PAUSED"
            PurchaseState.TERMINATED -> "TERMINATED"
            null -> "UNSPECIFIED_STATE"
        }
    }
    
    private fun Product.toJsonString(): String {
        return """
            {
                "productId": "$productId",
                "productType": "$productType",
                "price": ${price ?: 0},
                "priceLabel": "$priceLabel",
                "currency": "$currency",
                "title": "$title",
                "description": "$description"
            }
        """.trimIndent()
    }
    
    private fun Purchase.toJsonString(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        
        return """
            {
                "purchaseId": "$purchaseId",
                "productId": "$productId",
                "orderId": "${orderId ?: ""}",
                "invoiceId": "${invoiceId ?: ""}",
                "purchaseTime": "${purchaseTime?.let { dateFormat.format(it) } ?: ""}",
                "purchaseState": "$purchaseState",
                "developerPayload": "${developerPayload ?: ""}",
                "quantity": ${quantity ?: 1},
                "applicationCode": "${applicationCode ?: ""}",
                "signature": "${signature ?: ""}",
                "amountLabel": "${amountLabel ?: ""}",
                "amount": ${amount ?: 0},
                "currency": "${currency ?: ""}"
            }
        """.trimIndent()
    }
}