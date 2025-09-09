import {NativeModules, DeviceEventEmitter} from 'react-native';
import type {EmitterSubscription} from 'react-native';

const {RNIapModule} = NativeModules;

export enum BillingMode {
  /**
   * Automatically detect and use appropriate store based on region
   */
  AUTO = 'auto',
  
  /**
   * Force Google Play Billing
   */
  GOOGLE_PLAY = 'google_play',
  
  /**
   * Force RuStore Billing
   */
  RUSTORE = 'rustore',
}

export interface StoreChangeEvent {
  store: string;
  ruStoreAppInstalled?: boolean;
}

/**
 * Set the billing mode for the dual-store module
 * This only works when using Google Play flavor with dual-store support
 * 
 * @param mode The billing mode to use
 * @returns Promise<boolean> indicating success
 */
export const setBillingMode = async (mode: BillingMode): Promise<boolean> => {
  if (!RNIapModule || !RNIapModule.setBillingMode) {
    throw new Error('setBillingMode is only available in dual-store configuration');
  }
  
  return RNIapModule.setBillingMode(mode);
};

/**
 * Get the currently active billing store
 * 
 * @returns Promise<string> The active store ('google_play' or 'rustore')
 */
export const getActiveBillingStore = async (): Promise<string> => {
  if (!RNIapModule || !RNIapModule.getActiveBillingStore) {
    // Fallback for non-dual-store builds
    return 'google_play';
  }
  
  return RNIapModule.getActiveBillingStore();
};

/**
 * Listen to billing store changes
 * Useful when using AUTO mode and the store switches based on region detection
 * 
 * @param callback Function to call when store changes
 * @returns EmitterSubscription to remove the listener
 */
export const addBillingStoreChangeListener = (
  callback: (event: StoreChangeEvent) => void
): EmitterSubscription => {
  return DeviceEventEmitter.addListener('billing-store-changed', callback);
};

/**
 * Check if the current configuration supports dual-store
 * 
 * @returns boolean indicating if dual-store is supported
 */
export const isDualStoreSupported = (): boolean => {
  return !!(RNIapModule && RNIapModule.setBillingMode);
};

/**
 * Configuration for region-based billing
 */
export interface RegionBillingConfig {
  /**
   * ISO country codes that should use RuStore (e.g., ['RU', 'BY'])
   */
  ruStoreCountries?: string[];
  
  /**
   * Whether to fallback to RuStore if Google Play is unavailable
   */
  fallbackToRuStore?: boolean;
  
  /**
   * Whether to use RuStore without the app installed (external payments)
   */
  allowRuStoreWithoutApp?: boolean;
}

/**
 * Configure region-based billing preferences
 * This is a helper that sets up the module based on your preferences
 * 
 * @param config Configuration object
 * @returns Promise<string> The selected store
 */
export const configureRegionBilling = async (
  config: RegionBillingConfig = {}
): Promise<string> => {
  const {
    ruStoreCountries = ['RU', 'BY', 'KZ'],
    fallbackToRuStore = true,
    allowRuStoreWithoutApp = true,
  } = config;
  
  // For now, we'll use AUTO mode which handles region detection
  // In the future, we could pass more detailed config to native
  await setBillingMode(BillingMode.AUTO);
  
  return getActiveBillingStore();
};

/**
 * Helper to determine if user is likely in Russia/CIS region
 * This is done on the native side but exposed here for convenience
 * 
 * @returns Promise<boolean> indicating if user is in RU/CIS region
 */
export const isUserInRussianRegion = async (): Promise<boolean> => {
  const store = await getActiveBillingStore();
  return store === 'rustore';
};