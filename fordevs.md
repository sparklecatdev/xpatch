# For developers

## Build and check

Use JDK 21, Android SDK 36 and Build Tools 36.0.0. Set `ANDROID_HOME` to your SDK directory or supply an untracked `local.properties`.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease :runtime:lintRelease :app:assembleDebug :app:assembleRelease :fixture:assembleDebug
python3 scripts/smoke_android.py --serial emulator-5554
```

Use a disposable emulator. The smoke script clears XPatch data, patches and replaces only the synthetic `com.twitter.android` fixture, then checks both parser paths, ad filtering, live bar hiding, the inspector and persistent settings. It refuses to replace real X. It does not exercise signed-in timelines, folder scheduling or Compose controls.

## Project map

| Module | Purpose |
| --- | --- |
| `app` | Android UI, input validation, DEX matching and rewriting, signing, split installation and folder watch |
| `runtime` | Dependency-free injected Java runtime, timeline filtering, native view controls and live menu |
| `fixture` | Synthetic renamed models, old and modern parsers and native bars for end-to-end checks |

`app:runtimeDex` packages the runtime with D8 and embeds it as an asset. A build adds it as another DEX, adds a bounded JSON profile asset and hooks the target Application's `onCreate`.

## Compatibility work

`DexEngine` discovers schema keys and maps serializer field indexes to actual renamed fields. Legacy mappers require a single unambiguous String field. The modern path finds a Kotlin collection deserializer from multiple bytecode signals. Hooks use each return instruction's register and visit every supported return.

The runtime filters only recognized model classes and promoted entry markers. Unrecognized entries pass through. A successful structural match does not establish that a signed-in timeline uses that path. Test new versions against real timelines before claiming compatibility.

For a new schema, add a focused synthetic case to `DexEngineTest`, update the matcher, and run the fixture plus an on-device build. Avoid version-number bypasses and guesses at obfuscated fields. Native view names are configurable; Compose-only bars need a separate implementation. Premium cosmetics are not implemented.

APK sets are checked for valid signatures, matching signer and version, one base and unique splits. Every output part is signed with the same device key. Original signature entries are removed. Stored native libraries are aligned to 16 KiB. Android still decides whether a device can install and launch the result.

Only use inputs you trust. Build reports include APK hashes and structural matches; review them before sharing. Do not commit proprietary APKs, account data, signing keys or passwords.

## Publisher signing

Release APKs are unsigned after Gradle builds. Sign them with your own persistent publisher key and keep an offline backup of that key and its password. The published certificate fingerprint is in `release-signing.sha256`.

```sh
python3 scripts/sign_release.py \
  --keystore /private/path/publisher.p12 \
  --password-file /private/path/password.txt
```

The script aligns, signs and verifies `app-release-unsigned.apk`, checks the pinned certificate, and writes `.xpatch/release/xpatch.apk` and `SHA256SUMS`. It requires the SDK's Build Tools 36.0.0 and a key alias of `publisher`. Forks must replace the public fingerprint with their own. Never commit the private key or password.

This publisher identity signs XPatch itself. Patched X uses a separate key generated in Android Keystore on each device. Losing XPatch app data loses that device key.

## Preview validation

The first Android preview was checked against X `12.41.0-prod.01` (`312410001`), including its base, arm64, English and density splits. The patcher matched 16 entry models, three promoted metadata fields and one collection parser, rebuilt all parts on-device, installed them through PackageInstaller and opened X's login screen with the live mod menu. Signed-in ad behavior remains unverified. No X APKs are distributed.
