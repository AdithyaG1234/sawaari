# Sawaari

An Android app for motorcycle riders on weekend group trips: offline maps, navigation, group live tracking, SOS and ride recording. Built on a near-zero-cost stack.

## Stack
- Kotlin + Jetpack Compose, minSdk 26
- [MapLibre Android](https://github.com/maplibre/maplibre-native) for maps (open source, no API key)
- OpenStreetMap vector tiles from [OpenFreeMap](https://openfreemap.org) (free). Later: self-hosted PMTiles.
- [GraphHopper](https://github.com/graphhopper/graphhopper) 4.0 on the phone for offline routing; routing packs built in GitHub Actions (`build-routing.yml`)
- Firebase Realtime Database + anonymous Auth (free Spark plan) for group rides

Map data © OpenStreetMap contributors.

## iPhone version (in progress)
The iPhone app is being built step by step in the same repo, sharing code with Android through Kotlin Multiplatform:
- `shared/`: ride logic used by both apps (routes, navigation tracking, group alerts, ride stats, garage, route links). Its tests run on the JVM and on the iPhone simulator.
- `iosApp/`: the iPhone app shell (SwiftUI + Compose Multiplatform). The Xcode project is generated from `iosApp/project.yml` with XcodeGen.
- Done on iPhone so far: the map, offline map downloads, colour themes, group rides (same groups as Android), ride plans with turn-by-turn voice navigation, and ride history. iPhone routes are planned online with the free OpenStreetMap Valhalla server (motorcycle profile); offline routing on iPhone comes later.
- `build-ios.yml` builds it on GitHub's Mac machines, runs it in the iPhone simulator and saves screenshots. No Apple account is needed until it goes on TestFlight.

## Features
- **Offline maps:** download the visible area, or pick from one-tap ride areas across India. These include Coorg, Ooty and Munnar, Goa and the Konkan, Gokarna, Kerala, Pondicherry, Hyderabad and Srisailam, Rishikesh and Chopta, Rajasthan, Manali to Leh, Spiti, Kashmir, Sikkim and Meghalaya. Downloaded maps work with no network.
- **Offline navigation:** turn-by-turn voice directions are worked out on the phone (GraphHopper), with automatic rerouting. Each area also has offline places along the route: food, fuel, mechanics, tyre shops and hospitals.
- **Whole-trip offline:** for a multi-day trip across several areas, one download covers every stop: "Bengaluru to Goa" (about 120 MB) or "Karnataka & Goa (whole states)" (about 260 MB). The ride plan offers the right one, then "Save the map for this whole route" saves the map in a band about 4 km either side of the route, at full detail, instead of the whole region.
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
  - Ride schedule: the leader sets times (and optional names) for the start, each pit stop and the destination. Every rider gets a notification when each time comes, even with no signal. Any rider can start or end the planned ride, and the whole group is told.
  - What to carry: each rider has their own packing checklist (kept on their phone). Items they mark Share, like a puncture kit or first-aid kit, show to the group so others know who has them.
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

## Release tests
Every release must pass the automated release tests. CI runs them on every push and pull request, and publishes no APK (debug, preview or release) unless they all pass.
- **App logic:** `./gradlew :shared:jvmTest testDebugUnitTest` (navigation, voice prompts, ride plans, route links, schedule, packing, SOS, crash detection, garage, plans and more).
- **Firebase rules:** `cd firebase-tests && npm ci && npm test` checks `database.rules.json` on the database emulator for every group-ride feature: joining, seats and pricing, roles and leader handover, route, meeting point, schedule, start/end ride, packing, locations, alerts, SOS, chat and voice notes. Needs Node 22 and Java 21.
- **Feature checklist:** `release/features.txt` lists every feature and the tests that prove it. `python3 release/check_features.py shared/build/test-results/jvmTest app/build/test-results/testDebugUnitTest firebase-tests/build` fails if any feature has a failing test or none that ran. The table also shows on the CI run's summary page.

When you add a feature, add its tests and a line in `release/features.txt`.

## Releasing
Actions → Build APK → Run workflow, enter a version such as `1.0.0` (or push a tag like `v1.0.0`). CI builds the release APKs and publishes them as the latest release.

## Group rides setup (one time)
1. Create a Firebase project and add an Android app with package `app.sawaari`.
2. Create a Realtime Database, then enable Anonymous sign-in under Authentication.
3. Download `google-services.json` (after creating the database) into `app/`.
4. Paste `database.rules.json` into Realtime Database → Rules and publish (or `firebase deploy --only database`).

Without `google-services.json` the app still builds; the Group tab just explains the setup.

## Free and Pro plans
**Right now pricing is off for the Goa live trial:** every feature is free and groups can have up to 99 riders. The switch is `Plans.PRICING_ON` in `shared/.../plans/Plans.kt` and, on the server, `config/pricing` in the Realtime Database (missing or false = free). To turn pricing back on: set `PRICING_ON = true` and release, then set `config/pricing` to `true` in Firebase → Realtime Database → Data.

- **Free:** maps, offline navigation, SOS, crash detection, ride history, and ride groups of up to 3 riders. The garage is free for the first 14 days.
- **Sawaari Pro:** ₹200 for 3 months or ₹500 a year, through Google Play. A group with more than 3 riders needs Pro for **every** rider in it, the first 3 included. Pro also keeps the garage open.

The rules live in `shared/.../plans/Plans.kt` (`Plans.seatFor`) and are also enforced by `database.rules.json`: each rider holds a numbered seat at `groups/{code}/slots/{n}`. The 4th rider's join sets `groups/{code}/pro`, which needs the joiner and the riders in seats 1-3 to have an active `subs/{uid}`; once set, every new rider needs Pro. Only the server function can write `subs/`.

### Pro setup (once, before the Play Store launch)
1. Play Console → Monetize → Subscriptions: create product `sawaari_pro` with two auto-renewing base plans, `quarterly` (3 months, ₹200) and `yearly` (1 year, ₹500). Activate both.
2. Firebase: switch the project to the Blaze plan (functions need it; this use stays inside the free quota), then `firebase deploy --only functions,database` from the repo root.
3. Play Console → Users and permissions: invite `PROJECT_ID@appspot.gserviceaccount.com` with "View financial data" and "Manage orders and subscriptions", so the function can check purchases.
4. Test with a licence tester account (Play Console → Settings → License testing) on an internal testing build. Purchases only work for builds installed from Google Play.

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
