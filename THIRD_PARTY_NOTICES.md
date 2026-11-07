# Third-party notices

## plural-star-mods

Source: https://github.com/sparklecatdev/plural-star-mods/tree/563fd29980ac1ae0a3ee73072dbc4953bd152a53

Copyright the plural-star-mods contributors. Licensed under AGPL-3.0; see [LICENSE](LICENSE). `ApkPatcher.java` and `SigningIdentity.java` adapt the project's Android ZIP alignment and signing approach. The cat SVG and README presentation are derived from the same repository. Adaptations add structural DEX rewriting, split APK handling, validation and a separate signing alias.

## Piko

Source: https://github.com/crimera/piko/tree/a0ed9a5ae782fe66e02294e5a49d235e2f139b24

Copyright (C) 2026 piko. Licensed under GPL-3.0, with the attribution requirement preserved verbatim in [third_party/piko/NOTICE](third_party/piko/NOTICE). The original license is [third_party/piko/Piko-LICENSE.txt](third_party/piko/Piko-LICENSE.txt).

`runtime/MediaCatalog.java` adapts media traversal and original-image handling from `extensions/twitter/src/main/java/app/morphe/extension/twitter/entity/ExtMediaEntities.java`. It adds discovered field mappings, URL validation, explicit highest-bitrate selection, format preservation and bounded in-memory storage. `Mods.flag` uses feature-switch names from `extensions/twitter/src/main/java/app/morphe/extension/twitter/patches/FeatureSwitchPatch.java`; the hook matching and local display-state patch are maintained here.

Piko's NOTICE and license are packaged in `assets/xpatch/` in XPatch and copied into every patched base APK. Source remains available in this repository. No upstream patch bundle is embedded.

## Runtime and build dependencies

| Component | Version | License / source |
| --- | --- | --- |
| Android apksig | 8.12.0 | Apache-2.0 · https://android.googlesource.com/platform/tools/apksig/ |
| dexlib2 | 2.5.2 | BSD-3-Clause · https://github.com/JesusFreke/smali |
| Guava Android | 33.5.0-android | Apache-2.0 · https://github.com/google/guava |
| Android Gradle Plugin | 8.12.0 | Apache-2.0 · https://android.googlesource.com/platform/tools/base/ |
| Gradle wrapper | 8.13 | Apache-2.0 · https://github.com/gradle/gradle |
| JUnit (tests only) | 4.13.2 | EPL-1.0 · https://github.com/junit-team/junit4 |
| JSON-java (tests only) | 20240303 | Public domain · https://github.com/stleary/JSON-java |

Dependency license files and notices retain their upstream terms. Android SDK tools are supplied by the developer's SDK installation, not redistributed here.

X / Twitter is proprietary software owned by its respective rightsholders. No X APK, split, account data or modified X distribution is included in this repository or its releases. ReVanced and Morphe are references for feature ideas. Piko adaptations are identified above. No upstream patch bundle is included.
