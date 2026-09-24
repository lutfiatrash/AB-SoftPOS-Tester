# SoftPOS Tester

Minimal Android app that talks to Arab Bank's SoftPOS (`com.arabbank.softpos`) app-to-app,
using the M4Bank PayBox interaction protocol.

Operations: Sign In (no money) · Pay (ILS) · Reverse last · Check last status.
Every request and response is shown raw in the on-screen log.

## Build (no Android Studio needed)

1. Create a new **private** GitHub repo, e.g. `AB-SoftPOS-Tester`.
2. Upload everything in this folder, **including the hidden `.github` folder**.
   On Mac, press `Cmd + Shift + .` in Finder to show hidden files, then drag all of it
   onto the GitHub upload page (Chrome works best for folder uploads).
3. Commit to `main`. The **Actions** tab starts "Build APK" automatically (~4 min).
4. Open the finished run → **Artifacts** → `softpos-tester-apk` → download.
   The zip contains `app-debug.apk`.
5. Send the APK to the phone that has the SoftPOS app, open it, allow
   "Install unknown apps" when asked.

## Test order

1. Open the app. It auto-discovers SoftPOS activities — check the log.
2. **Leave the token empty** and tap **Sign In**. This tests the pure app-to-app
   round trip: SoftPOS should open and return an error. Tap **Copy log**.
3. Get a token (**Fetch token from server**, or paste one) and tap **Sign In** again.
4. Only then: **Pay** 1.00 on your own card → **Reverse last payment**.

If Sign In does nothing or SoftPOS shows the wrong screen, pick a different activity
in the dropdown and retry.

## Notes

- The token-server read key is prefilled in `Config.kt` — keep this repo private.
- `taxRate` / `accountingSubject` default to the protocol's example values
  (`TAX_20`, `PRODUCT`). Replace them in the app's Advanced fields once the bank
  confirms the Palestine values.
- A payment's external ID is saved before SoftPOS opens. If the app is killed mid-payment,
  reopen it and tap **Check last operation status**.
