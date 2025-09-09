# RuStore Integration Guide

This guide explains how to set up and use RuStore billing in your React Native app using react-native-iap.

## Prerequisites

1. RuStore Console account with configured application
2. Application published or in testing mode on RuStore
3. Products configured in RuStore Console
4. RuStore app installed on the testing device

## Setup

### 1. Android Manifest Configuration

Add your RuStore Console App ID to your AndroidManifest.xml:

```xml
<application>
    <meta-data
        android:name="rustore_console_app_id"
        android:value="YOUR_RUSTORE_CONSOLE_APP_ID" />
    
    <!-- Configure deep link for payment returns -->
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
</application>
```

### 2. Build Configuration

The library is already configured with RuStore support. To build your app with RuStore billing:

```bash
# For debug build
cd android
./gradlew assembleRustoreDebug

# For release build
./gradlew assembleRustoreRelease
```

Or if using the example app:

```bash
cd IapExample
yarn android:rustore
```

## Usage

### Basic Implementation

```typescript
import * as RNIap from 'react-native-iap';
import { InstallSourceAndroid, RuStoreEventType } from 'react-native-iap';

// Check if RuStore is the active billing provider
const installSource = RNIap.getInstallSourceAndroid();
if (installSource === InstallSourceAndroid.RUSTORE) {
  console.log('Using RuStore billing');
}

// Initialize connection
await RNIap.initConnection();

// Fetch products
const products = await RNIap.getProducts(['product_id_1', 'product_id_2']);

// Make a purchase
try {
  const purchase = await RNIap.requestPurchase('product_id_1');
  console.log('Purchase successful:', purchase);
  
  // Acknowledge the purchase
  await RNIap.acknowledgePurchaseAndroid({
    token: purchase.purchaseToken,
  });
} catch (error) {
  console.error('Purchase failed:', error);
}

// Get available purchases
const purchases = await RNIap.getAvailablePurchases();

// Listen to purchase updates
const purchaseUpdateSubscription = RNIap.purchaseUpdatedListener(
  (purchase) => {
    console.log('Purchase updated:', purchase);
  }
);

// Clean up
purchaseUpdateSubscription.remove();
await RNIap.endConnection();
```

### Two-Step Purchases (RuStore Specific)

RuStore supports two-step purchases for certain payment methods, where funds are held until confirmation:

```typescript
import { purchaseTwoStepRuStore, confirmTwoStepPurchaseRuStore, cancelTwoStepPurchaseRuStore } from 'react-native-iap';

// Initiate two-step purchase
const purchase = await purchaseTwoStepRuStore({
  productId: 'product_id',
  quantity: 1,
  orderId: 'unique_order_id',
  appUserId: 'user_id',
  appUserEmail: 'user@example.com',
});

// Later, confirm or cancel the purchase
if (shouldConfirm) {
  await confirmTwoStepPurchaseRuStore(purchase.purchaseId);
} else {
  await cancelTwoStepPurchaseRuStore(purchase.purchaseId);
}
```

### RuStore-Specific Events

```typescript
import { RuStoreEventType } from 'react-native-iap';

// Listen to RuStore-specific purchase status updates
const ruStoreListener = DeviceEventEmitter.addListener(
  RuStoreEventType.PURCHASE_STATUS_UPDATE,
  (update) => {
    console.log('RuStore purchase status:', update);
    // Handle purchase status update
  }
);

// Clean up
ruStoreListener.remove();
```

## Product Types

RuStore supports the following product types:
- **CONSUMABLE**: Can be purchased multiple times
- **NON_CONSUMABLE**: One-time purchase
- **SUBSCRIPTION**: Recurring payments

## Error Handling

Common error codes when using RuStore:

- `E_USER_CANCELLED`: User cancelled the purchase
- `E_ITEM_UNAVAILABLE`: Product not found or not available
- `E_NOT_PREPARED`: Connection not initialized
- `E_SERVICE_ERROR`: RuStore service error
- `E_NETWORK_ERROR`: Network connectivity issues
- `E_DEVELOPER_ERROR`: Configuration error (check console app ID)

## Testing

1. Ensure your app is signed with the same certificate as uploaded to RuStore Console
2. Add test accounts in RuStore Console for sandbox testing
3. Install RuStore app on the testing device
4. Make sure the device has network connectivity

## Limitations

- Purchase history API is not available in RuStore SDK
- Consumable products are automatically consumed after confirmation
- Two-step purchases are only available for specific payment methods
- Subscriptions currently only support one-step payment flow

## Troubleshooting

### "RUSTORE_CONSOLE_APP_ID not found" error
- Ensure you've added the meta-data tag in AndroidManifest.xml
- Verify the console app ID matches the one in RuStore Console

### Purchases not working
- Check that RuStore app is installed and logged in
- Verify products are configured and active in RuStore Console
- Ensure the app is signed with the production certificate

### Deep links not working
- Verify the intent filter is correctly configured in AndroidManifest.xml
- Check that android:launchMode is set to "singleTop" for your main activity

## Additional Resources

- [RuStore Developer Documentation](https://www.rustore.ru/help/sdk/pay)
- [RuStore Console](https://console.rustore.ru/)