# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

React Native IAP is a comprehensive in-app purchase module supporting iOS (StoreKit 2), Android (Google Play Billing), and Amazon App Store for React Native applications. Currently on branch `rustore` based on version 12.16.4.

## Key Commands

### Development Setup
```bash
# Install all dependencies
yarn

# Bootstrap example app with pods
yarn bootstrap  # Runs yarn in IapExample and installs iOS pods
```

### Building & Testing
```bash
# Type checking
yarn lint:tsc

# Linting (ESLint + Prettier)
yarn lint
yarn lint:ci  # For CI environment

# Format only modified files
yarn format

# Run tests
yarn test

# Generate documentation
yarn gen:doc
```

### Example App Development
```bash
cd IapExample

# iOS
yarn ios

# Android (Google Play variant)
yarn android:play

# Android (Amazon variant) 
yarn android:amazon

# Start Metro bundler
yarn start

# Install iOS pods
yarn pods
```

## Architecture & Structure

### Core Module Architecture
The library uses a modular architecture with platform-specific implementations:

- **src/iap.ts** - Main API interface exposing all IAP methods
- **src/eventEmitter.ts** - Event system for purchase updates and errors
- **src/types/** - TypeScript definitions for all data structures
- **src/modules/** - Platform-specific implementations:
  - `ios/` - iOS-specific methods (StoreKit 2)
  - `android/` - Android-specific methods (Google Play Billing)
  - `amazon/` - Amazon-specific methods
  - `common/` - Shared cross-platform utilities

### Native Bridge Implementation
- **iOS**: `/ios/*.swift` - Swift implementation using StoreKit 2
- **Android**: `/android/src/main/java/com/dooboolab/rniap/*.kt` - Kotlin implementation with Google Play Billing Library
- Uses React Native's NativeModules for JavaScript-Native communication

### Platform-Specific Features

**iOS (StoreKit 2)**:
- Promotional offers
- Subscription introductory prices
- Family sharing
- Receipt validation
- Transaction finishing

**Android (Google Play Billing)**:
- Consume purchases
- Multiple SKU purchases
- Subscription offers
- Obfuscated account/profile IDs

**Amazon**:
- DRM verification (3.x SDK)
- Sandbox testing support

### Event System
The library uses an event-driven architecture for purchase updates:
- `purchaseUpdated` - Fired when purchase state changes
- `purchaseError` - Fired on purchase errors
- `transactionUpdated` (iOS) - Transaction state changes
- Platform-specific events for detailed monitoring

### Error Handling
All errors are wrapped in `PurchaseError` class with standardized error codes across platforms. Check `src/purchaseError.ts` for error types.

## Testing & Development Notes

### Platform Setup Requirements
- **iOS**: Requires valid provisioning profiles and App Store Connect configuration
- **Android Play**: Requires signed APK and Google Play Console setup
- **Amazon**: Requires Amazon Developer account and proper manifest configuration

### Testing Purchases
- Use sandbox/test accounts for each platform
- Products must be configured in respective stores before testing
- Check `/IapExample/src/` for example implementation patterns

### Build Variants (Android)
The example app supports multiple Android build variants:
- `GooglePlayDebug/Release` - For Google Play Store
- `AmazonDebug/Release` - For Amazon App Store

## Module Exports Structure

The library exports from `src/index.ts`:
- Core IAP functions from `iap.ts`
- Type definitions from `types/`
- Event emitter functionality
- React hooks (`useIAP`, `withIAPContext`)
- Platform-specific modules

## Important Implementation Details

- All purchase flows must handle both success and error cases
- Always finish/acknowledge transactions to prevent issues
- Products must be fetched before attempting purchases
- Connection to store must be initialized before any IAP operations
- Subscription status should be verified server-side for security