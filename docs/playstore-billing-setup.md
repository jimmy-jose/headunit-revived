# Play Store Billing Setup for HeadUnitLauncher

## Overview
- Play Store product type: one-time in-app product
- Product ID: `headunitlauncher_unlock`
- App behavior:
  - 7-day local trial starts on the first foreground launch of the Play Store build
  - one-time purchase permanently unlocks the app
  - purchase restore uses Google Play Billing purchase queries

## Important Caveat
- The 7-day trial is app-managed, not Play-managed.
- The local trial can be reset by clearing app data or uninstalling the app.
- The permanent purchase restore survives reinstall because the entitlement is restored from Google Play.
- If you want uninstall-proof trials, you would need a different product model such as a subscription or a server-backed account system.

## Play Console Setup
1. Create or open the Play Console app for package `org.xs.headunitlauncher`.
2. Enable Play App Signing if this is a new Play listing or if your release flow requires it.
3. Create an in-app product:
   - Type: one-time product
   - Product ID: `headunitlauncher_unlock`
   - Name: choose your storefront name
   - Description: explain that it permanently unlocks HeadUnitLauncher
   - Pricing: set the final price
4. Activate the product so it is available to testing tracks.
5. Add internal testers or license testers for purchase validation.

## Upload Checklist
1. Upload the `playstore` flavor release build, not the `github` flavor.
2. Confirm the package name matches `org.xs.headunitlauncher`.
3. Confirm the in-app product `headunitlauncher_unlock` is active before testing Billing.
4. Publish the build to an internal testing track first.
5. Add your tester Google accounts to:
   - internal testing users
   - license testers if you use them in Play Console
6. Install the app from the Play testing track. Do not rely only on a side-loaded APK when validating Billing.

## App-Side Expectations
- Fresh install with no purchase:
  - app starts a 7-day local trial
  - app is usable immediately
- Trial expired with no purchase:
  - app opens to the paywall
  - Android Auto usage and auto-start paths are blocked
- Purchase complete:
  - unlock happens immediately after Billing reports ownership
  - unlock persists across restarts
- Reinstall or clear data after purchase:
  - purchase can be restored from Google Play

## Test Scenarios
1. First install, no purchase:
   - verify the trial starts
   - verify the app is usable
2. Purchase:
   - verify the one-time unlock succeeds
   - verify the app stays unlocked after restart
3. Restore after reinstall:
   - reinstall or clear data
   - open the app from the Play-installed build
   - verify restore/query unlocks the app
4. Expired trial without purchase:
   - verify the app is blocked at the paywall
   - verify auto-start paths do not continue into Android Auto

## Release Notes for Operators
- Billing only exists in the Play Store flavor.
- The `github` flavor remains unchanged and does not use Google Play Billing.
- No repo-wide `minSdk` increase is required; the Play Store flavor already uses `minSdk 21`.
