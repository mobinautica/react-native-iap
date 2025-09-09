package com.dooboolab.rniap

import android.app.Activity
import android.content.Intent
import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter
import ru.rustore.sdk.pay.PayClient
import ru.rustore.sdk.pay.PayClientFactory
import ru.rustore.sdk.pay.model.*
import ru.rustore.sdk.pay.feature.PurchaseFeature
import ru.rustore.sdk.pay.feature.model.PurchaseStatusUpdate
import ru.rustore.sdk.pay.feature.model.FinishCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

@ReactModule(name = RNIapRuStoreModule.TAG)
class RNIapRuStoreModule(
    private val reactContext: ReactApplicationContext
) : ReactContextBaseJavaModule(reactContext), LifecycleEventListener {

    companion object {
        const val TAG = "RNIapModule"
        const val RUSTORE_CONSOLE_APP_ID = "rustore_console_app_id"
    }

    private var payClient: PayClient? = null
    private val productDetailsCache: MutableMap<String, Product> = ConcurrentHashMap()
    private val purchasesCache: MutableMap<String, Purchase> = ConcurrentHashMap()
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private var isInitialized = false
    private var purchaseFeature: PurchaseFeature? = null

    override fun getName(): String = TAG

    override fun initialize() {
        super.initialize()
        reactContext.addLifecycleEventListener(this)
    }

    @ReactMethod
    fun initConnection(promise: Promise) {
        try {
            val appId = getConsoleAppId()
            if (appId.isNullOrEmpty()) {
                promise.safeReject(PromiseUtils.E_DEVELOPER_ERROR, "RUSTORE_CONSOLE_APP_ID not found in AndroidManifest.xml")
                return
            }

            payClient = PayClientFactory.create(
                context = reactContext.applicationContext,
                consoleApplicationId = appId,
                deeplinkScheme = "rniap"
            )

            // Initialize purchase feature for listening to updates
            purchaseFeature = payClient?.getPurchaseFeature()
            
            // Start listening to purchase updates
            purchaseFeature?.purchaseStatusFlow?.collect { update ->
                handlePurchaseUpdate(update)
            }

            isInitialized = true
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing RuStore connection", e)
            promise.safeReject(PromiseUtils.E_UNKNOWN, "Failed to initialize RuStore: ${e.message}")
        }
    }

    @ReactMethod
    fun endConnection(promise: Promise) {
        try {
            payClient = null
            purchaseFeature = null
            productDetailsCache.clear()
            purchasesCache.clear()
            isInitialized = false
            promise.resolve(true)
        } catch (e: Exception) {
            promise.safeReject(PromiseUtils.E_UNKNOWN, "Failed to end connection: ${e.message}")
        }
    }

    @ReactMethod
    fun getItemsByType(type: String, skuArr: ReadableArray, promise: Promise) {
        ensureConnection(promise) { client ->
            val productIds = skuArr.toStringList()
            
            coroutineScope.launch {
                try {
                    val result = client.getProducts(productIds).await()
                    
                    when (result) {
                        is PaymentResult.Success -> {
                            val products = result.data
                            val items = WritableNativeArray()
                            
                            products.forEach { product ->
                                productDetailsCache[product.productId] = product
                                items.pushMap(RuStoreUtils.convertProductToMap(product))
                            }
                            
                            withContext(Dispatchers.Main) {
                                promise.resolve(items)
                            }
                        }
                        is PaymentResult.Failure -> {
                            val error = result.throwable
                            withContext(Dispatchers.Main) {
                                promise.safeReject(PromiseUtils.E_ITEM_UNAVAILABLE, error.message ?: "Failed to fetch products")
                            }
                        }
                        is PaymentResult.Cancelled -> {
                            withContext(Dispatchers.Main) {
                                promise.safeReject(PromiseUtils.E_USER_CANCELLED, "User cancelled product fetch")
                            }
                        }
                        is PaymentResult.InvalidPaymentState -> {
                            withContext(Dispatchers.Main) {
                                promise.safeReject(PromiseUtils.E_SERVICE_ERROR, "Invalid payment state")
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        promise.safeReject(PromiseUtils.E_UNKNOWN, "Failed to fetch products: ${e.message}")
                    }
                }
            }
        }
    }

    @ReactMethod
    fun getAvailableItemsByType(type: String, promise: Promise) {
        ensureConnection(promise) { client ->
            coroutineScope.launch {
                try {
                    val result = client.getPurchases().await()
                    
                    when (result) {
                        is PaymentResult.Success -> {
                            val purchases = result.data
                            val items = WritableNativeArray()
                            
                            purchases.forEach { purchase ->
                                purchasesCache[purchase.purchaseId] = purchase
                                items.pushMap(RuStoreUtils.convertPurchaseToMap(purchase))
                            }
                            
                            withContext(Dispatchers.Main) {
                                promise.resolve(items)
                            }
                        }
                        is PaymentResult.Failure -> {
                            withContext(Dispatchers.Main) {
                                promise.safeReject(PromiseUtils.E_UNKNOWN, "Failed to fetch purchases: ${result.throwable.message}")
                            }
                        }
                        else -> {
                            withContext(Dispatchers.Main) {
                                promise.safeReject(PromiseUtils.E_UNKNOWN, "Unexpected result when fetching purchases")
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        promise.safeReject(PromiseUtils.E_UNKNOWN, "Failed to fetch purchases: ${e.message}")
                    }
                }
            }
        }
    }

    @ReactMethod
    fun buyItemByType(
        type: String,
        skuArr: ReadableArray,
        purchaseToken: String?,
        prorationMode: Int,
        obfuscatedAccountId: String?,
        obfuscatedProfileId: String?,
        subscriptionOffers: ReadableArray?,
        isOfferPersonalized: Boolean,
        promise: Promise
    ) {
        if (skuArr.size() == 0) {
            promise.safeReject(PromiseUtils.E_DEVELOPER_ERROR, "Product ID is required")
            return
        }

        val productId = skuArr.getString(0)
        
        ensureConnection(promise) { client ->
            val product = productDetailsCache[productId]
            if (product == null) {
                promise.safeReject(PromiseUtils.E_ITEM_UNAVAILABLE, "Product not found in cache. Call getItemsByType first.")
                return@ensureConnection
            }

            val currentActivity = currentActivity
            if (currentActivity == null) {
                promise.safeReject(PromiseUtils.E_UNKNOWN, "Current activity is null")
                return@ensureConnection
            }

            coroutineScope.launch {
                try {
                    val purchaseParams = PurchaseParams(
                        productId = productId,
                        quantity = 1,
                        orderId = null,
                        developerPayload = null,
                        appUserId = obfuscatedAccountId,
                        appUserEmail = obfuscatedProfileId
                    )

                    val result = client.purchase(purchaseParams).await()
                    
                    when (result) {
                        is PaymentResult.Success -> {
                            val purchase = result.data
                            purchasesCache[purchase.purchaseId] = purchase
                            
                            withContext(Dispatchers.Main) {
                                promise.resolve(RuStoreUtils.convertPurchaseToMap(purchase))
                                sendEvent("purchase-updated", RuStoreUtils.convertPurchaseToMap(purchase))
                            }
                        }
                        is PaymentResult.Failure -> {
                            withContext(Dispatchers.Main) {
                                val error = RuStoreUtils.createErrorMap(result.throwable)
                                promise.safeReject(error.getString("code"), error.getString("message"))
                                sendEvent("purchase-error", error)
                            }
                        }
                        is PaymentResult.Cancelled -> {
                            withContext(Dispatchers.Main) {
                                promise.safeReject(PromiseUtils.E_USER_CANCELLED, "User cancelled purchase")
                                sendEvent("purchase-error", RuStoreUtils.createUserCancelledError())
                            }
                        }
                        is PaymentResult.InvalidPaymentState -> {
                            withContext(Dispatchers.Main) {
                                promise.safeReject(PromiseUtils.E_SERVICE_ERROR, "Invalid payment state")
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        promise.safeReject(PromiseUtils.E_UNKNOWN, "Purchase failed: ${e.message}")
                    }
                }
            }
        }
    }

    @ReactMethod
    fun acknowledgePurchase(purchaseToken: String, developerPayloadAndroid: String?, promise: Promise) {
        ensureConnection(promise) { client ->
            coroutineScope.launch {
                try {
                    val result = client.confirm(purchaseToken).await()
                    
                    when (result) {
                        is PaymentResult.Success -> {
                            withContext(Dispatchers.Main) {
                                val resultMap = WritableNativeMap().apply {
                                    putBoolean("isSuccessful", true)
                                    putInt("responseCode", 0)
                                    putString("debugMessage", "Purchase confirmed successfully")
                                }
                                promise.resolve(resultMap)
                            }
                        }
                        is PaymentResult.Failure -> {
                            withContext(Dispatchers.Main) {
                                promise.safeReject(PromiseUtils.E_UNKNOWN, "Failed to confirm purchase: ${result.throwable.message}")
                            }
                        }
                        else -> {
                            withContext(Dispatchers.Main) {
                                promise.safeReject(PromiseUtils.E_UNKNOWN, "Unexpected result when confirming purchase")
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        promise.safeReject(PromiseUtils.E_UNKNOWN, "Failed to confirm purchase: ${e.message}")
                    }
                }
            }
        }
    }

    @ReactMethod
    fun consumeProduct(purchaseToken: String, developerPayloadAndroid: String?, promise: Promise) {
        // RuStore doesn't have a separate consume API like Google Play
        // Consumable products are automatically consumed after confirmation
        acknowledgePurchase(purchaseToken, developerPayloadAndroid, promise)
    }

    @ReactMethod
    fun startListening(promise: Promise) {
        // Purchase updates are automatically listened to when initialized
        promise.resolve(true)
    }

    @ReactMethod
    fun flushFailedPurchasesCachedAsPending(promise: Promise) {
        // RuStore handles pending purchases internally
        promise.resolve(false)
    }

    @ReactMethod
    fun getPurchaseHistoryByType(type: String, promise: Promise) {
        // RuStore doesn't provide purchase history API
        // Return current purchases instead
        getAvailableItemsByType(type, promise)
    }

    @ReactMethod 
    fun getPackageName(promise: Promise) {
        promise.resolve(reactContext.packageName)
    }

    @ReactMethod
    fun getStorefront(promise: Promise) {
        promise.resolve("RUS")
    }

    @ReactMethod
    fun presentCodeRedemptionSheet(promise: Promise) {
        promise.safeReject(PromiseUtils.E_UNKNOWN, "presentCodeRedemptionSheet is not supported on RuStore")
    }

    private fun ensureConnection(promise: Promise, callback: (PayClient) -> Unit) {
        val client = payClient
        if (client == null || !isInitialized) {
            promise.safeReject(PromiseUtils.E_NOT_PREPARED, "RuStore not initialized. Call initConnection first.")
            return
        }
        callback(client)
    }

    private fun getConsoleAppId(): String? {
        return try {
            val appInfo = reactContext.packageManager.getApplicationInfo(
                reactContext.packageName,
                android.content.pm.PackageManager.GET_META_DATA
            )
            appInfo.metaData?.getString(RUSTORE_CONSOLE_APP_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get console app ID from manifest", e)
            null
        }
    }

    private fun handlePurchaseUpdate(update: PurchaseStatusUpdate) {
        val writableMap = WritableNativeMap().apply {
            putString("purchaseId", update.purchaseId)
            putString("invoiceId", update.invoiceId)
            putString("orderId", update.orderId)
            putString("purchaseType", update.purchaseType?.toString())
            putString("state", update.state?.toString())
        }
        sendEvent("rustore-purchase-status-update", writableMap)
    }

    private fun sendEvent(eventName: String, params: WritableMap) {
        reactContext
            .getJSModule(RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }

    override fun onHostResume() {
        // Handle deep link returns
        currentActivity?.let { activity ->
            handleDeepLink(activity.intent)
        }
    }

    override fun onHostPause() {}

    override fun onHostDestroy() {}

    private fun handleDeepLink(intent: Intent?) {
        intent?.data?.let { uri ->
            if (uri.scheme == "rniap") {
                // RuStore SDK handles deep links internally
                // This is just for logging/debugging
                Log.d(TAG, "Received deep link: $uri")
            }
        }
    }
}

private fun ReadableArray.toStringList(): List<String> {
    val list = mutableListOf<String>()
    for (i in 0 until size()) {
        list.add(getString(i))
    }
    return list
}

