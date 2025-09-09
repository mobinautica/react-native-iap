# Dual Store Integration Guide (Google Play + RuStore)

This guide explains how to configure your app to accept payments through both Google Play and RuStore in a single APK, automatically switching based on user's region.

## Overview

The dual-store configuration allows your Google Play app to:
- Use Google Play Billing for users outside Russia
- Automatically switch to RuStore Billing for Russian users
- Support payments even without RuStore app installed (using RuStore Pay SDK 9.0.1+)
- Maintain a single codebase for both payment systems

## Setup

### 1. Prerequisites

- Google Play Console account with configured app and products
- RuStore Console account with configured app and products
- Product IDs should ideally match between both stores

### 2. Android Manifest Configuration

Add both Google Play and RuStore configurations to your AndroidManifest.xml:

```xml
<application>
    <!-- RuStore Console App ID (required for RuStore) -->
    <meta-data
        android:name="rustore_console_app_id"
        android:value="YOUR_RUSTORE_CONSOLE_APP_ID" />
    
    <!-- Configure deep link for RuStore payment returns -->
    <activity
        android:name=".MainActivity"
        android:launchMode="singleTop">
        <intent-filter>
            <action android:name="android.intent.action.VIEW" />
            <category android:name="android.intent.category.DEFAULT" />
            <category android:name="android.intent.category.BROWSABLE" />
            <data android:scheme="rniap" />
        </intent-filter>
    </activity>
    
    <!-- Google Play billing permission -->
    <uses-permission android:name="com.android.vending.BILLING" />
</application>
```

### 3. Building Your App

Build the Google Play variant which now includes both SDKs:

```bash
# Debug build
cd android
./gradlew assembleGooglePlayDebug

# Release build for Google Play Store
./gradlew assembleGooglePlayRelease
```

## Usage

### Basic Implementation with Auto-Detection

```typescript
import * as RNIap from 'react-native-iap';
import { 
  BillingMode, 
  configureRegionBilling, 
  getActiveBillingStore,
  addBillingStoreChangeListener 
} from 'react-native-iap';

// Initialize with auto-detection
async function initializeBilling() {
  // Configure region-based billing (optional)
  await configureRegionBilling({
    ruStoreCountries: ['RU', 'BY', 'KZ'], // Countries to use RuStore
    fallbackToRuStore: true,               // Use RuStore if Play unavailable
    allowRuStoreWithoutApp: true           // Allow payments without RuStore app
  });
  
  // Initialize connection (will use appropriate store)
  await RNIap.initConnection();
  
  // Check which store is active
  const activeStore = await getActiveBillingStore();
  console.log(`Using ${activeStore} for billing`);
  
  // Listen for store changes (useful for debugging)
  const subscription = addBillingStoreChangeListener((event) => {
    console.log('Billing store changed:', event.store);
    if (event.ruStoreAppInstalled !== undefined) {
      console.log('RuStore app installed:', event.ruStoreAppInstalled);
    }
  });
  
  return subscription;
}

// The rest of your IAP code remains the same
async function purchaseProduct(productId: string) {
  try {
    // This will use the appropriate store automatically
    const purchase = await RNIap.requestPurchase(productId);
    
    // Acknowledge works for both stores
    await RNIap.acknowledgePurchaseAndroid({
      token: purchase.purchaseToken,
    });
    
    return purchase;
  } catch (error) {
    console.error('Purchase failed:', error);
    throw error;
  }
}
```

### Manual Store Selection

You can also manually control which store to use:

```typescript
import { BillingMode, setBillingMode } from 'react-native-iap';

// Force Google Play
await setBillingMode(BillingMode.GOOGLE_PLAY);
await RNIap.initConnection();

// Force RuStore
await setBillingMode(BillingMode.RUSTORE);
await RNIap.initConnection();

// Auto-detect based on region (default)
await setBillingMode(BillingMode.AUTO);
await RNIap.initConnection();
```

### Handling Region-Specific Logic

```typescript
import { isUserInRussianRegion } from 'react-native-iap';

async function setupPayments() {
  const isRussian = await isUserInRussianRegion();
  
  if (isRussian) {
    // Show RuStore-specific UI or instructions
    console.log('Configuring for Russian market');
    // You might want to show different payment methods or UI
  } else {
    // Standard Google Play flow
    console.log('Configuring for international market');
  }
  
  // The actual IAP API calls remain the same
  const products = await RNIap.getProducts(['product1', 'product2']);
  // ...
}
```

## Auto-Detection Logic

The module automatically detects which store to use based on:

1. **Manual Override**: If you call `setBillingMode()`, that takes precedence
2. **Region Detection**: 
   - SIM card country
   - Network country
   - Device locale
   - Timezone
3. **Store Availability**:
   - Checks if Google Play Services are available
   - Checks if RuStore app is installed
   - Checks if RuStore SDK can work without the app
4. **Fallback Logic**:
   - Russian users → RuStore (if available) → RuStore without app → Google Play
   - Other users → Google Play → RuStore (if Play unavailable)

## RuStore Without App

When RuStore app is not installed, the SDK (v9.0.1+) can still process payments using:
- New card payments
- SBP (Fast Payment System)
- SberPay
- Other supported payment methods

This works automatically when:
1. `rustore_console_app_id` is configured in AndroidManifest
2. User is detected to be in Russia
3. Google Play is unavailable or you've forced RuStore mode

## Product Configuration

### Matching Product IDs

For the smoothest experience, use the same product IDs in both stores:

**Google Play Console:**
- `premium_monthly` - Premium subscription
- `coins_100` - 100 coins pack

**RuStore Console:**
- `premium_monthly` - Premium subscription (same ID)
- `coins_100` - 100 coins pack (same ID)

### Handling Different Product IDs

If product IDs differ between stores:

```typescript
const getProductId = async (baseProductId: string): Promise<string> => {
  const store = await getActiveBillingStore();
  
  const productMap = {
    'premium': {
      'google_play': 'com.app.premium_monthly',
      'rustore': 'premium_subscription_1m'
    },
    'coins': {
      'google_play': 'com.app.coins_100',
      'rustore': 'coins_pack_100'
    }
  };
  
  return productMap[baseProductId]?.[store] || baseProductId;
};

// Usage
const productId = await getProductId('premium');
const products = await RNIap.getProducts([productId]);
```

## Server-Side Validation

Your server should handle receipt validation for both stores:

```javascript
// Server-side pseudocode
async function validatePurchase(receipt, store) {
  if (store === 'rustore') {
    // Validate with RuStore API
    return validateRuStorePurchase(receipt);
  } else {
    // Validate with Google Play API
    return validateGooglePlayPurchase(receipt);
  }
}
```

Include the store type when sending receipts to your server:

```typescript
const activeStore = await getActiveBillingStore();
const purchase = await RNIap.requestPurchase(productId);

await sendToServer({
  receipt: purchase.transactionReceipt,
  store: activeStore,
  productId: purchase.productId,
  purchaseToken: purchase.purchaseToken
});
```

## Testing

### Testing Google Play Billing
1. Upload signed APK to Google Play Console (internal testing track)
2. Add test accounts in Google Play Console
3. Test on devices with Google Play Services

### Testing RuStore Billing
1. Upload APK to RuStore Console (testing track)
2. Configure test accounts in RuStore Console
3. Test on Russian devices or with Russian SIM/VPN

### Testing Auto-Detection
1. Use a device with Russian SIM card → Should use RuStore
2. Use a device with non-Russian SIM → Should use Google Play
3. Change device locale to Russian → May trigger RuStore (depending on other factors)
4. Use VPN to simulate different regions

### Testing Without RuStore App
1. Uninstall RuStore app from device
2. Ensure `rustore_console_app_id` is configured
3. Set device to Russian region
4. Payments should still work via web interface

## Troubleshooting

### "RuStore not initialized" in Play build
- Check that `rustore_console_app_id` is in AndroidManifest
- Verify the app ID matches your RuStore Console
- Ensure RuStore SDK is included in the build (check build.gradle)

### Store selection issues
```typescript
// Debug store selection
const store = await getActiveBillingStore();
console.log('Active store:', store);

// Force a specific store for testing
await setBillingMode(BillingMode.RUSTORE);
```

### Payment failures with RuStore
- Ensure deep link scheme is configured (`rniap://`)
- Check that MainActivity has `singleTop` launch mode
- Verify product IDs exist in RuStore Console
- Check if user needs to be logged into VK ID (for subscriptions)

### Google Play services check failed
- This is normal on devices without Google Play (e.g., Huawei)
- The module will automatically fallback to RuStore

## Best Practices

1. **Always test both stores** before releasing
2. **Use consistent product IDs** across stores when possible
3. **Handle store-specific errors** gracefully
4. **Monitor analytics** to see which store users are using
5. **Provide clear UI hints** about which payment system is active
6. **Cache store selection** to avoid repeated detection
7. **Test edge cases** like store switching mid-session

## Compliance Notes

- Ensure you comply with both Google Play and RuStore policies
- Some regions may have specific payment regulations
- Keep user data handling consistent across both payment systems
- Clearly communicate which payment processor is being used

## Example: Complete Implementation

```typescript
import React, { useEffect, useState } from 'react';
import * as RNIap from 'react-native-iap';
import {
  BillingMode,
  configureRegionBilling,
  getActiveBillingStore,
  addBillingStoreChangeListener,
} from 'react-native-iap';

const productIds = ['premium_monthly', 'coins_100'];

export function useInAppPurchases() {
  const [products, setProducts] = useState([]);
  const [activeStore, setActiveStore] = useState(null);
  
  useEffect(() => {
    let storeListener;
    
    async function initialize() {
      try {
        // Configure dual-store billing
        await configureRegionBilling({
          ruStoreCountries: ['RU', 'BY', 'KZ'],
          fallbackToRuStore: true,
          allowRuStoreWithoutApp: true
        });
        
        // Initialize IAP connection
        await RNIap.initConnection();
        
        // Get and set active store
        const store = await getActiveBillingStore();
        setActiveStore(store);
        
        // Listen for store changes
        storeListener = addBillingStoreChangeListener((event) => {
          console.log('Store changed to:', event.store);
          setActiveStore(event.store);
        });
        
        // Fetch products
        const items = await RNIap.getProducts(productIds);
        setProducts(items);
        
        // Setup purchase listeners
        const purchaseUpdateSubscription = RNIap.purchaseUpdatedListener(
          async (purchase) => {
            console.log('Purchase updated:', purchase);
            // Handle purchase
            await handlePurchase(purchase);
          }
        );
        
        const purchaseErrorSubscription = RNIap.purchaseErrorListener(
          (error) => {
            console.error('Purchase error:', error);
          }
        );
        
        return () => {
          purchaseUpdateSubscription.remove();
          purchaseErrorSubscription.remove();
          storeListener?.remove();
        };
      } catch (error) {
        console.error('Failed to initialize IAP:', error);
      }
    }
    
    initialize();
    
    return () => {
      RNIap.endConnection();
    };
  }, []);
  
  const purchase = async (productId) => {
    try {
      const result = await RNIap.requestPurchase(productId);
      return result;
    } catch (error) {
      console.error('Purchase failed:', error);
      throw error;
    }
  };
  
  const handlePurchase = async (purchase) => {
    // Validate on your server
    const validation = await validateOnServer(purchase, activeStore);
    
    if (validation.success) {
      // Acknowledge the purchase
      await RNIap.acknowledgePurchaseAndroid({
        token: purchase.purchaseToken,
      });
      
      // Grant user the purchased content
      await grantPurchase(purchase.productId);
    }
  };
  
  return {
    products,
    purchase,
    activeStore,
    isRussianStore: activeStore === 'rustore'
  };
}
```

## Summary

The dual-store configuration provides a seamless way to serve both international and Russian markets with a single APK. The module handles the complexity of store detection and switching, while your app code remains largely unchanged. This approach ensures maximum market reach while maintaining code simplicity.