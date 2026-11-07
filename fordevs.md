# For developers

## Build and check

Use JDK 21, Android SDK 36 and Build Tools 36.0.0. Set `ANDROID_HOME` to your SDK directory or supply an untracked `local.properties`.

```sh
./gradlew :app:testDebugUnitTest :runtime:testReleaseUnitTest :app:lintDebug :app:lintRelease :runtime:lintRelease :app:assembleDebug :app:assembleRelease :fixture:assembleDebug
python3 scripts/smoke_android.py --serial emulator-5554
```

Use a disposable emulator. The smoke script clears XPatch data, patches and replaces only the synthetic `com.twitter.android` fixture, then checks both parser paths, ad filtering, local Premium display state, original photo / highest-bitrate MP4 selection, live bar hiding, the inspector, license assets, persistent settings, update menus, release notes and saved-source restoration. It refuses to replace real X. It does not exercise signed-in timelines, real media downloads, remote update installation, folder scheduling or Compose controls.

## Project map

| Module | Purpose |
| --- | --- |
| `app` | Android UI, input validation, DEX matching and rewriting, signing, split installation and folder watch |
| `runtime` | Dependency-free injected Java runtime, timeline filtering, media discovery / downloads, local Premium flags, native view controls and live menu |
| `fixture` | Synthetic renamed models, old and modern parsers and native bars for end-to-end checks |
| `shared` | Java sources compiled into both apps for bounded public release feeds and update version/channel policy |

`app:runtimeDex` packages the runtime with D8 and embeds it as an asset. A build adds it as another DEX, adds a bounded JSON profile asset and hooks the target Application's `onCreate`. Engine 4 includes schema mappings, hook capabilities, patch/input versions, release notes and preserved upstream notices. Each build writes a new UUID generation; the output pointer changes only after all splits are signed and archived. Failure retains the previous generation.

## Compatibility work

`DexEngine` discovers schema keys and maps serializer field indexes to actual renamed fields. Legacy mappers require a single unambiguous String field. The modern path finds a Kotlin collection deserializer from multiple bytecode signals. Hooks use each return instruction's register and visit every supported return.

`ModernHooks` matches semantic model labels, navigation factories, supported Compose bar signatures and pre-roll metadata assignments. `SemanticFields` traces straight-line StringBuilder/toString helpers with their actual argument order and wide-register offsets; ambiguous or branched helpers are rejected. `ContentFilter` filters recognized models and promoted entry markers, reconstructs immutable modules and repairs conversation IDs. Unrecognized entries pass through. A successful structural match does not establish that a signed-in timeline uses that path. Test new versions against real timelines before claiming compatibility.

For a new schema, add a focused synthetic case to the matcher tests, update the matcher, and run the fixture plus an on-device build. Avoid version-number bypasses and guesses at obfuscated fields. Native view names are configurable. Compose navigation hide/order, home-tab hiding and Spaces hiding use separate hooks; native view colors/scaling do not apply. Premium display matching requires the exact display-state descriptor and two boolean fields, then changes only the `hasPremium` constructor assignment. Older custom-navigation flags are hooked only when their names and boolean call sites exist; no account identity models are patched.

Media models are mapped from Kotlin serializer descriptors, semantic model fields or Apollo JSON writer property / field pairs. Reader return hooks and all matched constructor exits feed the in-memory catalog. Only HTTPS `pbs.twimg.com` photos and `video.twimg.com` MP4 variants are accepted; unknown models pass through. Keep the Piko NOTICE and GPLv3 license in both app assets and patched base APKs when changing this code.

Update feeds are limited to five pages of 20 public GitHub releases, 2 MiB per page, with a 60-second cache. Release notes, checksums, APK downloads and profiles have separate size limits. Downloads require a matching SHA-256, valid APK signature with the installed publisher certificate, matching package and increasing semantic/version code. Verification runs again before PackageInstaller. Automatic checks/downloads are opt-in; installation stays manual. The injected runtime only checks releases and opens the patcher. Engine code ships with the patcher rather than as remotely loaded executable packs. Never weaken these checks to accept a fork's different signing identity.

APK sets are checked for valid signatures, matching signer and version, one base and unique splits. Every output part is signed with the same device key. Original signature entries are removed. DEX pools that exceed a 16-bit reference limit are split at class boundaries; new DEX names avoid existing DEX files and the injected runtime. Stored native libraries are aligned to 16 KiB. Android still decides whether a device can install and launch the result.

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

The 0.3 preview was checked against X `12.41.0-prod.01` (`312410001`), including its base, arm64, English and density splits. The patcher matched 18 entry models, 22 modern model schemas, two timeline parsers, one navigation factory, three Compose bar hooks, one pre-roll metadata constructor, 28 media models / 14 parsers / nine constructors and two Premium display sites. It rebuilt all parts on-device and installed through PackageInstaller; launch to login and the live mod menu were checked. Overflowing DEX pools are split during the real X rebuild. The 39 unit tests cover matching, nested filtering, immutable inputs, navigation, media and update validation. Signed-in ad behavior, Compose interactions and real media downloads remain unverified. No X APKs are distributed.
