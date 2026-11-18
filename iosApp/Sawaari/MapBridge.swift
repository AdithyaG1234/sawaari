import CoreLocation
import MapLibre
import Shared
import UIKit

/// The map and offline downloads for the shared Compose screens, using MapLibre for iOS.
/// Mirrors MapScreen.kt and OfflineRegionRepository.kt in the Android app.
final class MapBridge: NSObject, IosMapBridge, MLNMapViewDelegate {
    private let styleURL = URL(string: MapConfig.shared.STYLE_URL)!
    private let locationManager = CLLocationManager()
    private var centredOnRider = false
    private var regionsListener: (([DownloadedRegion]) -> Void)?
    private var errors: [Int64: String] = [:]
    private var packsObservation: NSKeyValueObservation?

    private lazy var map: MLNMapView = {
        let m = MLNMapView(frame: .zero, styleURL: styleURL)
        m.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        m.delegate = self
        m.setCenter(
            CLLocationCoordinate2D(latitude: MapConfig.shared.DEFAULT_LAT, longitude: MapConfig.shared.DEFAULT_LON),
            zoomLevel: MapConfig.shared.DEFAULT_ZOOM,
            animated: false
        )
        m.showsUserLocation = true
        m.compassViewPosition = .topLeft
        return m
    }()

    override init() {
        super.init()
        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(packProgressChanged(_:)), name: .MLNOfflinePackProgressChanged, object: nil)
        center.addObserver(self, selector: #selector(packFailed(_:)), name: .MLNOfflinePackError, object: nil)
        center.addObserver(self, selector: #selector(packTooLarge(_:)), name: .MLNOfflinePackMaximumMapboxTilesReached, object: nil)
        // Saved packs load in the background when the app starts.
        packsObservation = MLNOfflineStorage.shared.observe(\.packs, options: [.initial, .new]) { [weak self] storage, _ in
            storage.packs?.forEach { $0.requestProgress() }
            self?.publishRegions()
        }
    }

    // MARK: Map

    func mapView() -> UIView {
        if locationManager.authorizationStatus == .notDetermined {
            locationManager.requestWhenInUseAuthorization()
        }
        return map
    }

    func centerOnMe() {
        if let c = map.userLocation?.location?.coordinate, CLLocationCoordinate2DIsValid(c) {
            map.setCenter(c, zoomLevel: max(map.zoomLevel, 13), animated: true)
        }
        map.userTrackingMode = .follow
    }

    func visibleBounds() -> GeoBounds {
        let b = map.visibleCoordinateBounds
        return GeoBounds(north: b.ne.latitude, east: b.ne.longitude, south: b.sw.latitude, west: b.sw.longitude)
    }

    /// Like Android: the first time the rider's location is known, zoom the map to it.
    func mapView(_ mapView: MLNMapView, didUpdate userLocation: MLNUserLocation?) {
        guard !centredOnRider, let c = userLocation?.location?.coordinate, CLLocationCoordinate2DIsValid(c) else { return }
        centredOnRider = true
        mapView.setCenter(c, zoomLevel: 12, animated: false)
    }

    // MARK: Offline areas

    func observeRegions(onChange: @escaping ([DownloadedRegion]) -> Void) {
        regionsListener = onChange
        publishRegions()
    }

    func download(name: String, bounds: GeoBounds, minZoom: Double, maxZoom: Double) {
        let region = MLNTilePyramidOfflineRegion(
            styleURL: styleURL,
            bounds: MLNCoordinateBounds(
                sw: CLLocationCoordinate2D(latitude: bounds.south, longitude: bounds.west),
                ne: CLLocationCoordinate2D(latitude: bounds.north, longitude: bounds.east)
            ),
            fromZoomLevel: minZoom,
            toZoomLevel: maxZoom
        )
        let id = Int64(Date().timeIntervalSince1970 * 1000)
        let context = (try? JSONSerialization.data(withJSONObject: ["name": name, "id": id])) ?? Data()
        MLNOfflineStorage.shared.addPack(for: region, withContext: context) { [weak self] pack, error in
            if let error = error {
                self?.errors[id] = error.localizedDescription
            }
            pack?.resume()
            self?.publishRegions()
        }
    }

    func deleteRegion(regionId: Int64) {
        guard let pack = pack(regionId) else { return }
        pack.suspend()
        MLNOfflineStorage.shared.removePack(pack) { [weak self] _ in
            self?.errors[regionId] = nil
            self?.publishRegions()
        }
    }

    func resumeRegion(regionId: Int64) {
        errors[regionId] = nil
        pack(regionId)?.resume()
        publishRegions()
    }

    @objc private func packProgressChanged(_ note: Notification) {
        publishRegions()
    }

    @objc private func packFailed(_ note: Notification) {
        guard let pack = note.object as? MLNOfflinePack else { return }
        let error = note.userInfo?[MLNOfflinePackUserInfoKey.error] as? NSError
        errors[Self.info(pack).id] = error?.localizedDescription ?? "Download failed. Tap resume to try again."
        publishRegions()
    }

    @objc private func packTooLarge(_ note: Notification) {
        guard let pack = note.object as? MLNOfflinePack else { return }
        errors[Self.info(pack).id] = "Area too large, pick a smaller area"
        publishRegions()
    }

    private func pack(_ id: Int64) -> MLNOfflinePack? {
        MLNOfflineStorage.shared.packs?.first { Self.info($0).id == id }
    }

    private static func info(_ pack: MLNOfflinePack) -> (id: Int64, name: String) {
        let json = (try? JSONSerialization.jsonObject(with: pack.context)) as? [String: Any]
        let id = (json?["id"] as? NSNumber)?.int64Value ?? Int64(abs(pack.context.hashValue))
        return (id, json?["name"] as? String ?? "Offline area")
    }

    private func publishRegions() {
        let regions = (MLNOfflineStorage.shared.packs ?? []).map { pack -> DownloadedRegion in
            let (id, name) = Self.info(pack)
            let p = pack.progress
            return MapBridgeKt.makeRegion(
                regionId: id,
                name: name,
                completedBytes: Int64(p.countOfBytesCompleted),
                completedResources: Int64(p.countOfResourcesCompleted),
                requiredResources: Int64(p.countOfResourcesExpected),
                isComplete: pack.state == .complete,
                error: errors[id]
            )
        }.sorted { $0.name < $1.name }
        let listener = regionsListener
        DispatchQueue.main.async { listener?(regions) }
    }
}
