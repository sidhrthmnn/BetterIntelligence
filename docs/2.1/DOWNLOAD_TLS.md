# Model download certificate compatibility

Version 7 (2.1.0-beta2) addresses Android's `Trust anchor for certification path not found` when the device cannot build the Hugging Face/Amazon CDN certificate path. This error occurs before downloading weights and is separate from model RAM requirements.

## Trust policy

The application uses Android's declarative Network Security Configuration, not a custom trust manager. System certificate authorities remain enabled. The official, self-signed Amazon Root CA 1 is additionally supplied **only** for `huggingface.co`, `hf.co` and their subdomains. These serve the current catalogue's API, resolve URLs and CDN redirects. Custom model hosts and public web research use system roots only. User-installed certificates are not broadly enabled. Hostname, certificate validity and signature checks remain active, and cleartext traffic is disabled.

Certificate source: [Amazon Trust Services repository](https://www.amazontrust.com/repository/), [AmazonRootCA1.pem](https://www.amazontrust.com/repository/AmazonRootCA1.pem).

DER SHA-256: `8ecde6884f3d87b1125ba31ac3fcb13d7016de7f57cc904fe1cb97c6ae98196e` (verified against the repository's published fingerprint before inclusion). Valid to 17 January 2038. The root is already widely publicly trusted; the bundle covers devices whose system/OEM store or cross-signed path lacks it. No server leaf or intermediate is promoted to a trust anchor. Reassess the scoped root if the provider changes its certificate hierarchy.

The live chains inspected on 21 October 2026 were Amazon RSA 2048 M01 for `huggingface.co` and `cas-bridge.xethub.hf.co`, and M04 for `us.aws.cdn.hf.co`, all chaining to Amazon Root CA 1. [Android's TLS troubleshooting guidance](https://developer.android.com/privacy-and-security/security-ssl) describes unknown roots and missing intermediates; [Network Security Configuration](https://developer.android.com/privacy-and-security/security-config) documents scoped additional trust anchors.

## Download behaviour

Weights, Hub metadata and checksum pointers now share manual HTTPS redirect handling. A downgrade to HTTP, missing redirect destination or more than eight redirects fails closed. Failed TLS handshakes and previous redirect connections are disconnected. Resume ranges, identity encoding, pinned SHA-256 verification, private storage and atomic model installation are preserved. Metadata responses are limited to their configured byte budget.

Certificate errors give recovery steps instead of raw Java exception names. A wrong clock, intercepted HTTPS connection, missing unrelated CA or invalid server certificate can still fail securely. Try automatic date/time, Android updates and a different network if a VPN/HTTPS filter is involved. Browser-downloaded models can be imported. The screenshot alone cannot distinguish those device/network causes, so confirmation on the originally affected phone remains necessary.

## Device checks

Build with `-PbiValidationPackage=true`, install the matching debug and androidTest APKs, then run:

```text
adb shell am instrument -w -e biNetworkTests true -e class com.example.ModelDownloadTlsDeviceTest com.aistudio.betterintelligence.mobile.validation.test/androidx.test.runner.AndroidJUnitRunner
```

This opt-in test uses real HTTPS: it verifies the three provider chains with **only** the bundled root (no system roots), checks rejection of self-signed, expired and wrong-host certificates, reads a 4 KiB range from every catalogue model, and downloads/validates/installs the full 105,454,144-byte SmolLM2 135M model. It operates only in the isolated validation package and removes its own synthetic files. It does not download other multi-gigabyte models. JVM regression tests cover handshake cleanup, retained partial files, redirects, downgrade rejection, loops, bounded metadata and successful verified resume.
