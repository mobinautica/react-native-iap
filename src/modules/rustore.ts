import {NativeModules} from 'react-native';
import {InstallSourceAndroid} from '../types';

const {RNIapModule} = NativeModules;

/**
 * Check if the current Android build is configured for RuStore
 * @returns {InstallSourceAndroid} The install source (RUSTORE if RuStore flavor is active)
 */
export const getInstallSourceAndroid = (): InstallSourceAndroid => {
  // Check if RNIapModule exists and if it's the RuStore implementation
  // The RuStore module will be present when building with the rustore flavor
  if (RNIapModule && !('getSubscriptionStatus' in RNIapModule)) {
    // RuStore module doesn't have getSubscriptionStatus (Play Store specific)
    // and doesn't have getUserData (Amazon specific)
    if (!('getUserData' in RNIapModule)) {
      return InstallSourceAndroid.RUSTORE;
    }
  }
  
  // Fallback to checking for other implementations
  if (RNIapModule) {
    return InstallSourceAndroid.GOOGLE_PLAY;
  }
  
  return InstallSourceAndroid.NOT_SET;
};

/**
 * RuStore-specific: Confirm a two-step purchase
 * Only available when using RuStore billing
 * @param {string} purchaseId The purchase ID to confirm
 * @returns {Promise<void>}
 */
export const confirmTwoStepPurchaseRuStore = async (purchaseId: string): Promise<void> => {
  if (!RNIapModule || !RNIapModule.confirmTwoStepPurchase) {
    throw new Error('confirmTwoStepPurchase is only available on RuStore');
  }
  
  return RNIapModule.confirmTwoStepPurchase(purchaseId);
};

/**
 * RuStore-specific: Cancel a two-step purchase
 * Only available when using RuStore billing
 * @param {string} purchaseId The purchase ID to cancel
 * @returns {Promise<void>}
 */
export const cancelTwoStepPurchaseRuStore = async (purchaseId: string): Promise<void> => {
  if (!RNIapModule || !RNIapModule.cancelTwoStepPurchase) {
    throw new Error('cancelTwoStepPurchase is only available on RuStore');
  }
  
  return RNIapModule.cancelTwoStepPurchase(purchaseId);
};

/**
 * RuStore-specific: Initiate a two-step purchase
 * This creates a purchase with fund holding, requiring confirmation later
 * @param {Object} params Purchase parameters
 * @param {string} params.productId The product ID to purchase
 * @param {number} params.quantity Quantity to purchase (default: 1)
 * @param {string} params.orderId Optional order ID
 * @param {string} params.developerPayload Optional developer payload
 * @param {string} params.appUserId Optional app user ID
 * @param {string} params.appUserEmail Optional app user email
 * @returns {Promise<any>} Purchase object
 */
export const purchaseTwoStepRuStore = async ({
  productId,
  quantity = 1,
  orderId,
  developerPayload,
  appUserId,
  appUserEmail,
}: {
  productId: string;
  quantity?: number;
  orderId?: string;
  developerPayload?: string;
  appUserId?: string;
  appUserEmail?: string;
}): Promise<any> => {
  if (!RNIapModule || !RNIapModule.purchaseTwoStep) {
    throw new Error('purchaseTwoStep is only available on RuStore');
  }
  
  return RNIapModule.purchaseTwoStep(
    productId,
    quantity,
    orderId,
    developerPayload,
    appUserId,
    appUserEmail
  );
};

/**
 * RuStore-specific event types
 */
export enum RuStoreEventType {
  PURCHASE_STATUS_UPDATE = 'rustore-purchase-status-update',
}

/**
 * RuStore purchase states
 */
export enum RuStorePurchaseState {
  CREATED = 'CREATED',
  INVOICE_CREATED = 'INVOICE_CREATED',
  PAID = 'PAID',
  CONFIRMED = 'CONFIRMED',
  CONSUMED = 'CONSUMED',
  CANCELLED = 'CANCELLED',
  PAUSED = 'PAUSED',
  TERMINATED = 'TERMINATED',
}