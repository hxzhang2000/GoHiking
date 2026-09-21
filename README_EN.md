# GoHiking 🏔

An Android hiking recorder for mountain lovers: fully offline-capable, private by design, and built for real terrain.
From route planning to on-trail recording to post-hike analysis — the complete loop for a day in the mountains.

> Current version **0.5.0** (in development) · See [CHANGELOG.md](CHANGELOG.md) · 中文：[README.md](README.md)

## Features

| Module | Capabilities |
| --- | --- |
| 🗺 Route planning | Map pick / search, auto-suggested walking routes, manual waypoints, outbound & return legs, ascent & difficulty estimation, direction arrows, route export |
| 🥾 Track recording | Start/pause/resume/finish, live track & pace, barometric altitude (optional hardware, multi-level fallback), step counting with four-level fallback, distance/altitude alerts with TTS voice |
| 📊 History & stats | Distance/duration/pace/ascent/calories (MET estimate), altitude profile & per-km pace charts, kilometer & climb split tables |
| 📷 Photo map | Scans on-device photos/videos with location (WGS-84→GCJ-02 conversion), grid-cluster bubbles, thumbnail sheet, full-screen viewer, auto-association with trips (±30 min + 500 m) |
| 💾 Data ownership | Export trip JSON / GPX / full ZIP backup, import with conflict preview (ZIP safety guards), single key, no cloud |
| 🌐 Bilingual | First-class i18n (English fallback + Simplified Chinese), light/dark themes follow the system |

Design principles: **usable in weak-coverage mountains** (offline fallbacks everywhere), **never fabricate data** (show "—" when sensors are unavailable), **privacy first** (the AMap SDK is initialized only after user consent).

## Tech Stack

- **Language/UI**: Kotlin 2.1 + Jetpack Compose (Material 3) + MVVM
- **DI**: Hilt · **Persistence**: Room + DataStore · **Async**: Coroutines + Flow
- **Maps/Location/Search**: AMap Android SDK (official combined artifact `3dmap-location-search`)
- **Build**: AGP 8.7.3 · Gradle 8.9 · JDK 17 · minSdk 26 / targetSdk 35

## Project Layout

```
GoHiking/                     # Gradle multi-module (18 modules)
├── app/                      # Shell: privacy gate + screen assembly
├── core/                     # Foundation
│   ├── common/               #   Result types / formatters / polyline simplification
│   ├── model/                #   Domain models (enums are protocol, never translated)
│   ├── database/             #   Room (tracks / media / planned routes / elevation cache)
│   ├── datastore/            #   Preferences
│   ├── resources/            #   Bilingual strings (key alignment enforced by checkStringKeys)
│   ├── designsystem/         #   Theme (light & dark)
│   ├── location/             #   AMap/Fused dual sources, GCJ-02↔WGS-84 conversion
│   ├── map/                  #   Map rendering, search & route-planning clients
│   ├── elevation/            #   Elevation 3-level fallback + route ascent/difficulty
│   └── data/                 #   Recording session / repositories
├── feature/                  # Screens: home / recording / history / plan / media / io / settings
├── docs/                     # PRD · Development design · Terrain feasibility
└── prototype/                # Interactive HTML prototypes (P-01~P-18, 393×852)
```

## Getting Started

### Prerequisites

- Android Studio (JDK **17** for running Gradle — the AGP/Gradle combination caps the JDK version)
- Android SDK 35
- An [AMap Android Key](https://console.amap.com/) bound to package `com.gohiking.app` and your signing SHA1

### Configure the AMap Key

The key is injected via `local.properties` and **never committed** (see `local.properties.example`):

```properties
AMAP_KEY_DEBUG=your debug key
AMAP_KEY_RELEASE=your release key
```

### Build & Verify

```bash
# Debug APK (app/build/outputs/apk/debug/GoHiking-debug-v<version>.apk)
./gradlew assembleDebug

# Full gate (required before committing): build + lint + string-key alignment + unit tests
./gradlew assembleDebug :app:lintDebug :core:resources:checkStringKeys \
  :core:data:testDebugUnitTest :core:location:testDebugUnitTest \
  :core:common:testDebugUnitTest :core:elevation:testDebugUnitTest
```

## Documentation

| Document | Contents |
| --- | --- |
| [docs/PRD.md](docs/PRD.md) | Product requirements baseline (feature IDs `F-XXX-nn`, acceptance criteria, roadmap) |
| [docs/DEV-DESIGN.md](docs/DEV-DESIGN.md) | Development design (modules / interfaces / algorithm parameters / build baseline) |
| [CHANGELOG.md](CHANGELOG.md) | Release history & versioning rules |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Contribution guide (engineering constraints & pre-commit gate) |

## License

[Apache-2.0](LICENSE). Third-party library licenses are governed by their upstream repositories.
