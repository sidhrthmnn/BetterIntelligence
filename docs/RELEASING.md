# Release signing and internal testing

The replacement app is **Better Intelligence**, package `com.aistudio.betterintelligence.mobile`. It is a separate installation from the previous `com.aistudio.betterintelligence.vqkzp` Play app. Models, saved chats and app approvals are stored per installation and do not migrate automatically. Testers should download a model and approve consumer apps again in the replacement.

Its ContentProvider authority is `com.aistudio.betterintelligence.mobile.provider`; the binding permission and action also use the new package prefix. This avoids provider and permission ownership conflicts when an old installation remains on the phone. Use the current [integration guide](APP_INTEGRATION.md), SDK helpers and sample client. AIDL interface package names remain unchanged.

## Build

Use the documented JDK/SDK/NDK versions. Supply `KEYSTORE_PATH`, `STORE_PASSWORD` and `KEY_PASSWORD` as environment variables. The release upload alias is `upload`. Keep the upload keystore and its passwords outside the repository; never commit them or put them into release notes.

```sh
./gradlew :app:testDebugUnitTest :app:bundleRelease --no-configuration-cache
```

Upload `app/build/outputs/bundle/release/app-release.aab`. Debug APKs cannot be uploaded as a Play release. Verify the bundle's signature, application ID, non-debuggable flag, target SDK and native-library page alignment before uploading. Google Play App Signing protects the distributed signing key; the local upload key must be preserved for later submissions. Increase `versionCode` for each subsequent release of this package.

On the Windows machine used for the initial release preparation, the upload keystore and Windows-user-encrypted credentials are stored under `%USERPROFILE%\.android\release-keys\better-intelligence\`. Back them up securely; the encrypted password record depends on the Windows user profile and must be exported securely before changing machines.

The Windows helper loads this protected password without printing it and restores the calling process's signing environment afterwards:

```powershell
.\scripts\build_release.ps1 -SigningCredentials "$env:USERPROFILE\.android\release-keys\better-intelligence\upload-2026-10-15.credentials.json"
```

The app bundle includes the native runtime and model license notices under `assets/licenses/`. The models themselves remain separate downloads.

## Play Console

Create a free app named Better Intelligence with English (United Kingdom). The account owner must authorise the policy and export-compliance declarations and any applicable signing terms. Create an **Internal testing** release and select the existing **Testers** email list; do not select all account lists. Publish the release, confirm that it is available to internal testers, and share its opt-in URL.

Deleting the previous app is a separate Play Console operation requiring the developer registration transaction ID. Google permits recovery for seven days, then deletion becomes permanent. A package with any lifetime installs cannot be reused by a replacement app. See [Google's deletion guide](https://support.google.com/googleplay/android-developer/answer/16483176) and [testing guide](https://support.google.com/googleplay/android-developer/answer/9845334).
