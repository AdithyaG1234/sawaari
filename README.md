# Sawaari

An Android app for motorcycle riders on weekend group trips: offline maps, navigation, group live tracking, SOS and ride recording. Built on a near-zero-cost stack.

## Stack
- Kotlin + Jetpack Compose, minSdk 26
- [MapLibre Android](https://github.com/maplibre/maplibre-native) for maps (open source, no API key)
- OpenStreetMap vector tiles from [OpenFreeMap](https://openfreemap.org) (free). Later: self-hosted PMTiles.
- [GraphHopper](https://github.com/graphhopper/graphhopper) 4.0 on the phone for offline routing; routing packs built in GitHub Actions (`build-routing.yml`)
- Firebase Realtime Database + anonymous Auth (free Spark plan) for group rides

Map data © OpenStreetMap contributors.

## Features (v1.0)
- **Offline maps:** download the visible area or one-tap ride regions (Coorg, Lonavala, Manali-Leh, Spiti and more). They work with no network.
- **Offline navigation:** turn-by-turn voice directions computed on the phone (GraphHopper), with automatic rerouting.
- **Ride plans:** long-press the map to set a start, up to 8 stops and a destination. The leader can share the plan with the group.
- **Group rides:**
  - Invite riders with a 6-letter code and see everyone live on the map.
  - The leader picks a sweep (last rider).
  - Location sharing keeps working with the screen off.
- **Alerts for the whole group:** "Taking a break", a rider stopped for 5 min, the leader or sweep with no signal. The leader also hears when a rider falls 2 km behind.
- **Leader handover:** the leader can hand over. If the leader has no signal for 10 min, sends an SOS or leaves, any rider can take over.
- **SOS:**
  - A 5-second countdown, then an SMS with your location goes to up to 5 emergency contacts (no mobile data needed).
  - The ride group is alerted too.
  - Includes optional blood group and medical notes.

## Download
Latest release: https://github.com/AdithyaG1234/sawaari/releases/latest/download/sawaari.apk
(Older 32-bit phones: `sawaari-older-phones.apk` on the same release page.)

Open the link in Chrome, not inside another app's browser. If Play Protect warns you, tap More details → Install anyway.
For SOS to send texts on its own, go to Settings → Apps → Sawaari → ⋮ → Allow restricted settings, then Permissions → SMS → Allow.

Test builds from main: https://github.com/AdithyaG1234/sawaari/releases/download/latest-debug/sawaari-debug.apk

## Releasing
Push a version tag, for example `git tag v1.0.0 && git push origin v1.0.0`. CI builds the release APKs and publishes them as the latest release.

## Group rides setup (one time)
1. Create a Firebase project and add an Android app with package `app.sawaari`.
2. Create a Realtime Database, then enable Anonymous sign-in under Authentication.
3. Download `google-services.json` (after creating the database) into `app/`.
4. Paste `database.rules.json` into Realtime Database → Rules and publish.

Without `google-services.json` the app still builds; the Group tab just explains the setup.

## Build
Open the folder in Android Studio (Ladybug or newer) and press Run, or:

```
./gradlew assembleDebug
```

## Roadmap
Done in v1.0: offline maps, offline navigation, ride plans with stops, group rides with alerts and leader handover, SOS.

Next:
1. iPhone app
2. Multi-day trip planner
3. Ride recording and stats (stored as structured data, ready for the AI Instagram infographic later)
4. Play Store release (needs a private signing key and Google's approval for SMS and location)
