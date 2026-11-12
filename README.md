# Sawaari

An Android app for motorcycle riders on weekend group trips: offline maps, navigation, group live tracking, SOS and ride recording. Built on a near-zero-cost stack.

## Stack
- Kotlin + Jetpack Compose, minSdk 26
- [MapLibre Android](https://github.com/maplibre/maplibre-native) for maps (open source, no API key)
- OpenStreetMap vector tiles from [OpenFreeMap](https://openfreemap.org) (free). Later: self-hosted PMTiles.
- [GraphHopper](https://github.com/graphhopper/graphhopper) 4.0 on the phone for offline routing; routing packs built in GitHub Actions (`build-routing.yml`)
- Firebase Realtime Database + anonymous Auth (free Spark plan) for group rides

Map data © OpenStreetMap contributors.

## Features
- **Offline maps:** download the visible area, or pick from one-tap ride areas across India. These include Coorg, Ooty and Munnar, Goa and the Konkan, Gokarna, Kerala, Pondicherry, Hyderabad and Srisailam, Rishikesh and Chopta, Rajasthan, Manali to Leh, Spiti, Kashmir, Sikkim and Meghalaya. Downloaded maps work with no network.
- **Offline navigation:** turn-by-turn voice directions are worked out on the phone (GraphHopper), with automatic rerouting. Each area also has offline places along the route: food, fuel, mechanics, tyre shops and hospitals.
- **Ride plans:**
  - Long-press the map or search to set a start, up to 8 stops and a destination.
  - Reorder stops and turn on Round trip.
  - Stops that can't be reached are marked, with a quick way to fix them.
  - The leader can share the plan with the group.
- **Route links:** Google Maps links, short links and coordinates (decimal or degrees/minutes/seconds) open as a route in Sawaari. Share your own route as a picture plus a link.
- **Group rides:**
  - Creating a group asks for the route: paste a Google Maps link or plan it on the offline map. A planned route can be turned into a group with one tap.
  - Invite riders with a 6-letter code and see everyone live on the map, even with the screen off. Riders who join, even later, get the route on their map.
  - The leader sets a meeting point.
  - Riders can be given roles: Sweep, Marshal or Medic.
- **Group chat:** one-tap quick messages ("Fuel stop", "Puncture, need help") and hold-to-talk voice notes, with notifications while riding.
- **Alerts for the whole group:**
  - "Taking a break".
  - A rider stopped unexpectedly, left the route, fell behind or lost signal.
  - Leader handover.
- **SOS:**
  - A 5-second countdown, then an SMS with your location goes to up to 5 emergency contacts (no mobile data needed).
  - The ride group is alerted too, and the group's medic is told they're needed.
  - Includes optional blood group and medical notes.
- **Crash detection (opt-in):** after a hard impact followed by stopping, Sawaari beeps and counts down 30 seconds, then sends the SOS unless the rider taps I'm OK.
- **Ride history:** every ride is recorded on the phone with distance, riding time, average and top speed, and a map of the route.
- **Garage (motorcycles, cars and bicycles):**
  - Your vehicles, each with its own usual service jobs, and the odometer kept up to date from your rides.
  - Service reminders by km or months: oil, chain, air filter, brakes, tyres and your own jobs.
  - Insurance, PUC and RC expiry reminders.

## Download
Latest release: https://github.com/AdithyaG1234/sawaari/releases/latest/download/sawaari.apk
(Older 32-bit phones: `sawaari-older-phones.apk` on the same release page.)

Open the link in Chrome, not inside another app's browser. If Play Protect warns you, tap More details → Install anyway.
For SOS to send texts on its own, go to Settings → Apps → Sawaari → ⋮ → Allow restricted settings, then Permissions → SMS → Allow.

Test builds from main: https://github.com/AdithyaG1234/sawaari/releases/download/latest-debug/sawaari-debug.apk

## Releasing
Actions → Build APK → Run workflow, enter a version such as `1.0.0` (or push a tag like `v1.0.0`). CI builds the release APKs and publishes them as the latest release.

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
Done: offline maps and navigation for 13 areas, ride plans, route links, group rides with alerts, roles and chat, SOS with crash detection, ride history and the garage.

Next:
1. Clubs and events (club pages, upcoming rides with RSVPs)
2. iPhone app
3. Multi-day trip planner
4. Play Store release (needs a private signing key and Google's approval for SMS and location)
