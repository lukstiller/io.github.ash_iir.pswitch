# PSwitch

> **Experimental compatibility fork — Play Store 54.4.11-34 (85441140).**
> Static DEX analysis only; not yet built or tested on a device. The upstream APK and
> releases are not builds of this patch. Do not use this branch for other Play Store versions.

An LSPosed / Vector module that changes which logged-in Google account Google Play uses
for **in-app purchases and billing**, not only the account shown in the Play Store UI.

The companion app lists the Google accounts available to Play Store and lets you pick
one with a tap. No root is needed: the selected account is stored in the app's own
preferences and shared with the hook through a caller-checked local `ContentProvider`.
The normal billing resolver reads the selected account at resolution time.

## Details

- Application ID: `io.github.ash_iir.pswitch`
- Scope: `com.android.vending` (Google Play Store)
- Xposed API: 102 (modern libxposed API, no legacy `de.robv` bridge)
- Android: 8.0 (API 26) or newer

## Install

1. Build this branch from source. Existing upstream APKs and releases do not contain this patch.
2. Enable **PSwitch** in LSPosed / Vector and limit its scope to **Google Play Store**.
3. Force-stop Google Play Store once.
4. Open PSwitch and tap the account you want for purchases. Confirm in the purchase
   sheet. Reopen the purchase flow after changing the selection.
5. Use **Reset** to return to the Play Store default account.

## Privacy

The account list and the chosen account stay on the device. The module requests no network
permission, does not broadcast account lists or selected email addresses, and checks the
Play Store UID at the provider boundary. No email addresses are intentionally written to logs.
The refresh broadcast carries no account information.

## Build

`build.sh` uses `aapt2`, `javac`, D8 and `jarsigner` directly (no Gradle). It downloads
the compile-only `libxposed:api:102.0.0` classes automatically:

```sh
bash build.sh
```

The signed APK is written to `PSwitch.apk`. Signing is done with `apksigner` **after**
`zipalign` so `resources.arsc` stays 4-byte aligned (required by Android 11+ / targetSdk 30+).
You still need `aapt2`, an Android `android.jar`, D8/R8 and `zipalign` available in your
`PATH`; adjust the tool paths in `build.sh` for your environment.

## Compatibility notes

The old obfuscated account lookup no longer has the expected signature in 54.4.11-34.
The replacement is scoped to the normal billing account resolver. It only substitutes a
real Google account available on the device when the resolver already has a Google account
and no explicit account was requested. Unsupported versions, missing accounts, and
unrecognized signatures retain the original behavior. It does not rewrite authorization
results, checkout state, payment verification or purchase receipts. Some flows may not
use this resolver, so successful switching is not guaranteed.

Validate on-device: check the hook-installation log, open a purchase sheet, verify the
account without completing a charge, then reset and confirm the original account returns.

Original MIT license and attribution are retained.

## Disclaimer

Unofficial module, not affiliated with Google. Use only on devices and accounts you own.
