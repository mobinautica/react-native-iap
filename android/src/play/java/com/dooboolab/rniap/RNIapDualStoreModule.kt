package com.dooboolab.rniap

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import com.android.billingclient.api.*
import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import ru.rustore.sdk.pay.PayClient
import ru.rustore.sdk.pay.PayClientFactory
import ru.rustore.sdk.pay.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Dual-store module that can switch between Google Play Billing and RuStore
 * based on user's region or configuration
 */
@ReactModule(name = RNIapDualStoreModule.TAG)
class RNIapDualStoreModule(
    private val reactContext: ReactApplicationContext,
    private val googlePlayModule: RNIapModule = RNIapModule(reactContext),
    private val builder: BillingClient.Builder = BillingClient.newBuilder(reactContext).enablePendingPurchases(),
    private val googleApiAvailability: GoogleApiAvailability = GoogleApiAvailability.getInstance()
) : ReactContextBaseJavaModule(reactContext), PurchasesUpdatedListener {

    companion object {
        const val TAG = "RNIapModule"
        const val RUSTORE_CONSOLE_APP_ID = "rustore_console_app_id"
        private const val BILLING_MODE_AUTO = "auto"
        private const val BILLING_MODE_GOOGLE_PLAY = "google_play"
        private const val BILLING_MODE_RUSTORE = "rustore"
    }

    private var payClient: PayClient? = null
    private val productDetailsCache: MutableMap<String, Product> = ConcurrentHashMap()
    private val purchasesCache: MutableMap<String, ru.rustore.sdk.pay.model.Purchase> = ConcurrentHashMap()
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    
    private var currentBillingMode: String = BILLING_MODE_AUTO
    private var isRuStoreInitialized = false
    private var isGooglePlayInitialized = false
    private var activeStore: String? = null

    override fun getName(): String = TAG

    override fun initialize() {
        super.initialize()
        reactContext.addLifecycleEventListener(googlePlayModule)
    }

    /**
     * Detect if user is in Russia based on various signals
     */
    private fun isUserInRussia(): Boolean {
        try {
            // Check SIM country
            val tm = reactContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val simCountry = tm?.simCountryIso?.uppercase(Locale.ROOT)
            if (simCountry == "RU") return true
            
            // Check network country
            val networkCountry = tm?.networkCountryIso?.uppercase(Locale.ROOT)
            if (networkCountry == "RU") return true
            
            // Check locale
            val locale = Locale.getDefault()
            if (locale.country.uppercase(Locale.ROOT) == "RU") return true
            
            // Check timezone
            val tz = TimeZone.getDefault()
            if (tz.id.contains("Europe/Moscow") || 
                tz.id.contains("Europe/Samara") || 
                tz.id.contains("Europe/Volgograd") ||
                tz.id.contains("Asia/Yekaterinburg") ||
                tz.id.contains("Asia/Novosibirsk") ||
                tz.id.contains("Asia/Vladivostok")) {
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting user region", e)
        }
        
        return false
    }

    /**
     * Check if RuStore app is installed
     */
    private fun isRuStoreInstalled(): Boolean {
        return try {
            reactContext.packageManager.getPackageInfo("ru.vk.store", 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if Google Play Services are available
     */
    private fun isGooglePlayAvailable(): Boolean {
        val result = googleApiAvailability.isGooglePlayServicesAvailable(reactContext)
        return result == ConnectionResult.SUCCESS
    }

    /**
     * Determine which store to use based on region and availability
     */
    private fun determineActiveStore(): String {
        when (currentBillingMode) {
            BILLING_MODE_GOOGLE_PLAY -> return BILLING_MODE_GOOGLE_PLAY
            BILLING_MODE_RUSTORE -> return BILLING_MODE_RUSTORE
            BILLING_MODE_AUTO -> {
                // Auto-detect based on region and availability
                val isRussianUser = isUserInRussia()
                val ruStoreAvailable = isRuStoreInstalled() || canUseRuStoreWithoutApp()
                val playAvailable = isGooglePlayAvailable()
                
                return when {
                    isRussianUser && ruStoreAvailable -> BILLING_MODE_RUSTORE
                    isRussianUser && !playAvailable -> BILLING_MODE_RUSTORE
                    playAvailable -> BILLING_MODE_GOOGLE_PLAY
                    ruStoreAvailable -> BILLING_MODE_RUSTORE
                    else -> BILLING_MODE_GOOGLE_PLAY // Fallback
                }
            }
        }
        return BILLING_MODE_GOOGLE_PLAY
    }

    /**
     * Check if we can use RuStore SDK without the app installed
     * (for external payments as per documentation)
     */
    private fun canUseRuStoreWithoutApp(): Boolean {
        // RuStore Pay SDK 9.0.1+ supports payments without RuStore app
        return getConsoleAppId() != null
    }

    @ReactMethod
    fun setBillingMode(mode: String, promise: Promise) {
        currentBillingMode = mode
        promise.resolve(true)
    }

    @ReactMethod
    fun getActiveBillingStore(promise: Promise) {
        promise.resolve(activeStore ?: determineActiveStore())
    }

    @ReactMethod
    fun initConnection(promise: Promise) {
        val store = determineActiveStore()
        activeStore = store
        
        Log.d(TAG, "Initializing connection with store: $store")
        
        when (store) {
            BILLING_MODE_RUSTORE -> initRuStoreConnection(promise)
            else -> {
                // Delegate to original Google Play module
                googlePlayModule.ensureConnection(promise) { client ->
                    isGooglePlayInitialized = true
                    promise.resolve(true)
                }
            }
        }
    }

    private fun initRuStoreConnection(promise: Promise) {
        try {
            val appId = getConsoleAppId()
            if (appId.isNullOrEmpty()) {
                // Fallback to Google Play if RuStore is not configured
                Log.w(TAG, "RuStore console app ID not found, falling back to Google Play")
                activeStore = BILLING_MODE_GOOGLE_PLAY
                googlePlayModule.ensureConnection(promise) { client ->
                    isGooglePlayInitialized = true
                    promise.resolve(true)
                }
                return
            }

            payClient = PayClientFactory.create(
                context = reactContext.applicationContext,
                consoleApplicationId = appId,
                deeplinkScheme = "rniap"
            )

            isRuStoreInitialized = true
            promise.resolve(true)
            
            // Send event to notify JS about active store
            sendEvent("billing-store-changed", WritableNativeMap().apply {
                putString("store", BILLING_MODE_RUSTORE)
                putBoolean("ruStoreAppInstalled", isRuStoreInstalled())
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing RuStore, falling back to Google Play", e)
            activeStore = BILLING_MODE_GOOGLE_PLAY
            googlePlayModule.ensureConnection(promise) { client ->
                isGooglePlayInitialized = true
                promise.resolve(true)
            }
        }
    }

    @ReactMethod
    fun endConnection(promise: Promise) {
        when (activeStore) {
            BILLING_MODE_RUSTORE -> {
                payClient = null
                productDetailsCache.clear()
                purchasesCache.clear()
                isRuStoreInitialized = false
                promise.resolve(true)
            }
            else -> googlePlayModule.endConnection(promise)
        }
        activeStore = null
    }

    @ReactMethod
    fun getItemsByType(type: String, skuArr: ReadableArray, promise: Promise) {
        when (activeStore) {
            BILLING_MODE_RUSTORE -> getRuStoreItems(type, skuArr, promise)
            else -> googlePlayModule.getItemsByType(type, skuArr, promise)
        }
    }

    private fun getRuStoreItems(type: String, skuArr: ReadableArray, promise: Promise) {
        val client = payClient
        if (client == null) {
            promise.safeReject(PromiseUtils.E_NOT_PREPARED, "RuStore not initialized")
            return
        }
        
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
                            items.pushMap(convertRuStoreProductToMap(product))
                        }
                        
                        withContext(Dispatchers.Main) {
                            promise.resolve(items)
                        }
                    }
                    is PaymentResult.Failure -> {
                        withContext(Dispatchers.Main) {
                            promise.safeReject(PromiseUtils.E_ITEM_UNAVAILABLE, "Failed to fetch products")
                        }
                    }
                    else -> {
                        withContext(Dispatchers.Main) {
                            promise.safeReject(PromiseUtils.E_UNKNOWN, "Unexpected result")
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

    @ReactMethod
    fun getAvailableItemsByType(type: String, promise: Promise) {
        when (activeStore) {
            BILLING_MODE_RUSTORE -> getRuStorePurchases(type, promise)
            else -> googlePlayModule.getAvailableItemsByType(type, promise)
        }
    }

    private fun getRuStorePurchases(type: String, promise: Promise) {
        val client = payClient
        if (client == null) {
            promise.safeReject(PromiseUtils.E_NOT_PREPARED, "RuStore not initialized")
            return
        }
        
        coroutineScope.launch {
            try {
                val result = client.getPurchases().await()
                
                when (result) {
                    is PaymentResult.Success -> {
                        val purchases = result.data
                        val items = WritableNativeArray()
                        
                        purchases.forEach { purchase ->
                            purchasesCache[purchase.purchaseId] = purchase
                            items.pushMap(convertRuStorePurchaseToMap(purchase))
                        }
                        
                        withContext(Dispatchers.Main) {
                            promise.resolve(items)
                        }
                    }
                    else -> {
                        withContext(Dispatchers.Main) {
                            promise.safeReject(PromiseUtils.E_UNKNOWN, "Failed to fetch purchases")
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
        when (activeStore) {
            BILLING_MODE_RUSTORE -> buyRuStoreItem(
                type, skuArr, obfuscatedAccountId, obfuscatedProfileId, promise
            )
            else -> googlePlayModule.buyItemByType(
                type, skuArr, purchaseToken, prorationMode, 
                obfuscatedAccountId, obfuscatedProfileId, 
                subscriptionOffers, isOfferPersonalized, promise
            )
        }
    }

    private fun buyRuStoreItem(
        type: String,
        skuArr: ReadableArray,
        obfuscatedAccountId: String?,
        obfuscatedProfileId: String?,
        promise: Promise
    ) {
        if (skuArr.size() == 0) {
            promise.safeReject(PromiseUtils.E_DEVELOPER_ERROR, "Product ID is required")
            return
        }

        val productId = skuArr.getString(0)
        val client = payClient
        
        if (client == null) {
            promise.safeReject(PromiseUtils.E_NOT_PREPARED, "RuStore not initialized")
            return
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
                            promise.resolve(convertRuStorePurchaseToMap(purchase))
                            sendEvent("purchase-updated", convertRuStorePurchaseToMap(purchase))
                        }
                    }
                    is PaymentResult.Failure -> {
                        withContext(Dispatchers.Main) {
                            promise.safeReject(PromiseUtils.E_UNKNOWN, "Purchase failed")
                        }
                    }
                    is PaymentResult.Cancelled -> {
                        withContext(Dispatchers.Main) {
                            promise.safeReject(PromiseUtils.E_USER_CANCELLED, "User cancelled purchase")
                        }
                    }
                    else -> {
                        withContext(Dispatchers.Main) {
                            promise.safeReject(PromiseUtils.E_UNKNOWN, "Unexpected purchase result")
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

    @ReactMethod
    fun acknowledgePurchase(purchaseToken: String, developerPayloadAndroid: String?, promise: Promise) {
        when (activeStore) {
            BILLING_MODE_RUSTORE -> confirmRuStorePurchase(purchaseToken, promise)
            else -> googlePlayModule.acknowledgePurchase(purchaseToken, developerPayloadAndroid, promise)
        }
    }

    private fun confirmRuStorePurchase(purchaseToken: String, promise: Promise) {
        val client = payClient
        if (client == null) {
            promise.safeReject(PromiseUtils.E_NOT_PREPARED, "RuStore not initialized")
            return
        }
        
        coroutineScope.launch {
            try {
                val result = client.confirm(purchaseToken).await()
                
                when (result) {
                    is PaymentResult.Success -> {
                        withContext(Dispatchers.Main) {
                            val resultMap = WritableNativeMap().apply {
                                putBoolean("isSuccessful", true)
                                putInt("responseCode", 0)
                            }
                            promise.resolve(resultMap)
                        }
                    }
                    else -> {
                        withContext(Dispatchers.Main) {
                            promise.safeReject(PromiseUtils.E_UNKNOWN, "Failed to confirm purchase")
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    promise.safeReject(PromiseUtils.E_UNKNOWN, "Failed to confirm: ${e.message}")
                }
            }
        }
    }

    @ReactMethod
    fun consumeProduct(purchaseToken: String, developerPayloadAndroid: String?, promise: Promise) {
        when (activeStore) {
            BILLING_MODE_RUSTORE -> confirmRuStorePurchase(purchaseToken, promise)
            else -> googlePlayModule.consumeProduct(purchaseToken, developerPayloadAndroid, promise)
        }
    }

    @ReactMethod
    fun startListening(promise: Promise) {
        promise.resolve(true)
    }

    @ReactMethod
    fun flushFailedPurchasesCachedAsPending(promise: Promise) {
        when (activeStore) {
            BILLING_MODE_RUSTORE -> promise.resolve(false)
            else -> googlePlayModule.flushFailedPurchasesCachedAsPending(promise)
        }
    }

    @ReactMethod
    fun getPurchaseHistoryByType(type: String, promise: Promise) {
        when (activeStore) {
            BILLING_MODE_RUSTORE -> getAvailableItemsByType(type, promise)
            else -> googlePlayModule.getPurchaseHistoryByType(type, promise)
        }
    }

    @ReactMethod
    fun getPackageName(promise: Promise) {
        promise.resolve(reactContext.packageName)
    }

    @ReactMethod
    fun getStorefront(promise: Promise) {
        when (activeStore) {
            BILLING_MODE_RUSTORE -> promise.resolve("RUS")
            else -> googlePlayModule.getStorefront(promise)
        }
    }

    @ReactMethod
    fun presentCodeRedemptionSheet(promise: Promise) {
        when (activeStore) {
            BILLING_MODE_RUSTORE -> 
                promise.safeReject(PromiseUtils.E_UNKNOWN, "Not supported on RuStore")
            else -> googlePlayModule.presentCodeRedemptionSheet(promise)
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        if (activeStore == BILLING_MODE_GOOGLE_PLAY) {
            googlePlayModule.onPurchasesUpdated(billingResult, purchases)
        }
    }

    private fun getConsoleAppId(): String? {
        return try {
            val appInfo = reactContext.packageManager.getApplicationInfo(
                reactContext.packageName,
                android.content.pm.PackageManager.GET_META_DATA
            )
            appInfo.metaData?.getString(RUSTORE_CONSOLE_APP_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get RuStore console app ID", e)
            null
        }
    }

    private fun sendEvent(eventName: String, params: WritableMap) {
        reactContext
            .getJSModule(RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }

    // Conversion utilities for RuStore
    private fun convertRuStoreProductToMap(product: Product): WritableMap {
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
            putString("originalJson", product.toString())
        }
    }

    private fun convertRuStorePurchaseToMap(purchase: ru.rustore.sdk.pay.model.Purchase): WritableMap {
        return WritableNativeMap().apply {
            putString("productId", purchase.productId)
            putString("purchaseId", purchase.purchaseId)
            putString("orderId", purchase.orderId)
            putString("purchaseToken", purchase.purchaseId)
            putString("invoiceId", purchase.invoiceId)
            putDouble("purchaseTime", purchase.purchaseTime?.time?.toDouble() ?: 0.0)
            putString("purchaseState", purchase.purchaseState?.toString() ?: "UNSPECIFIED_STATE")
            putString("developerPayload", purchase.developerPayload)
            putBoolean("isAcknowledged", purchase.purchaseState == PurchaseState.CONFIRMED)
            putString("packageNameAndroid", purchase.applicationCode)
            putString("originalJson", purchase.toString())
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