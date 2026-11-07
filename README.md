# Sawaari

An Android app for motorcycle riders on weekend group trips: offline maps, navigation, group live tracking, SOS and ride recording. Built on a near-zero-cost stack.

## Stack
- Kotlin + Jetpack Compose, minSdk 26
- [MapLibre Android](https://github.com/maplibre/maplibre-native) for maps (open source, no API key)
- OpenStreetMap vector tiles from [OpenFreeMap](https://openfreemap.org) (free). Later: self-hosted PMTiles.
- Planned: GraphHopper on-device for offline routing, Firebase (free tier) for sign-in, group tracking and alerts

Map data © OpenStreetMap contributors.

## What works now (v0.1)
- Map screen with OpenStreetMap tiles
- "Download this area": save the visible part of the map for offline use
- Offline maps screen: one-tap popular ride areas (Coorg, Lonavala, Manali-Leh, Spiti, etc.), progress, size, resume and delete
- Downloaded areas display with no network

## Build
Open the folder in Android Studio (Ladybug or newer) and press Run, or:

```
./gradlew assembleDebug
```

## Roadmap (MVP)
1. Offline maps and region downloads ✔ (first cut)
2. Offline turn-by-turn navigation (GraphHopper)
3. Group rides: invite link, live locations, leader/sweep, fell-behind alerts
4. SOS with SMS to emergency contacts
5. Trip planner with multi-day itinerary
6. Ride recording and stats (stored as structured data, ready for the AI Instagram infographic later)
