# Third-party notices

Our application source and original vector illustrations are GPL-3.0-only;
see [LICENSE](LICENSE). This is independent software, without manufacturer endorsement.
No proprietary Soundcore program, firmware or native library is included.

The two product PNGs under `app/src/main/res/drawable-nodpi/` were supplied by the
owner for inclusion in this app. They are used as provided; the left earbud is
mirrored at render time. Their original copyright/license provenance was not
supplied. Image and trademark rights remain with their holders; the source GPL
license does not assert ownership or relicense these assets. No affiliation or
manufacturer endorsement is implied.

Protocol research consulted **OpenSCQ30**, by Oppzippy and contributors, GPL-3.0:
https://github.com/Oppzippy/OpenSCQ30/tree/200d9e1657dff67d516ed1b6347f312d7a1b01a7
The A6611 reference fixture and common packet framing informed this independent
Kotlin implementation. Real/public serials were not copied into test fixtures.
The reference is not a claim that OpenSCQ30 fully supports A20.

Runtime dependencies:

- AndroidX Core, Activity, Lifecycle, DataStore and Compose/Material 3: Apache-2.0,
  copyright The Android Open Source Project and contributors.
- Kotlin and kotlinx.coroutines: Apache-2.0, JetBrains and contributors.

UI tests use Robolectric (Apache-2.0) and AndroidX Compose Test (Apache-2.0).
Tests use JUnit 4 (EPL-1.0); JUnit is not packaged in the app. The Gradle wrapper scripts/JAR are Apache-2.0. The Gradle distribution, Android
tools and JDK are external build dependencies and are not bundled here.
Dependency versions/checksums are recorded under `gradle/`.

Complete GPL-3.0 and Apache-2.0 texts are also embedded in the app's assets.

The optional offline research script uses Unicorn 2.1.4 (GPL-2.0-only), installed
separately. It is not an app/runtime dependency and is not redistributed.

The charging parser, history validator, generic 1.15 eligibility and reset engine,
and their sanitized offline fixtures/tests are reused under GPL-3.0-only from
the author’s A20 charging-repair research.
The integration contains no vendor firmware or updater.
