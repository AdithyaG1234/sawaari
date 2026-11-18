import CoreLocation
import MapLibre
import Shared
import UIKit

/// The map and offline downloads for the shared Compose screens, using MapLibre for iOS.
/// Mirrors MapScreen.kt and OfflineRegionRepository.kt in the Android app.
final class MapBridge: NSObject, IosMapBridge, MLNMapViewDelegate, CLLocationManagerDelegate {
    private let styleURL = URL(string: MapConfig.shared.STYLE_URL)!
    private let locationManager = CLLocationManager()
    private var centredOnRider = false
    private var regionsListener: (([DownloadedRegion]) -> Void)?
    private var errors: [Int64: String] = [:]
    private var packsObservation: NSKeyValueObservation?
    private var onFix: ((LocationFix) -> Void)?
    private var riderAnnotations: [String: RiderAnnotation] = [:]
    private var meetAnnotation: MeetAnnotation?

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
        // Keep the OpenStreetMap credit (the "i" button) visible next to the MapLibre logo,
        // clear of the Download button on the bottom right.
        m.attributionButtonPosition = .bottomLeft
        m.attributionButtonMargins = CGPoint(x: 120, y: 12)
        return m
    }()

    override init() {
        super.init()
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.distanceFilter = 10
        locationManager.activityType = .automotiveNavigation
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

    func moveTo(lat: Double, lon: Double, zoom: Double) {
        map.userTrackingMode = .none
        map.setCenter(CLLocationCoordinate2D(latitude: lat, longitude: lon), zoomLevel: zoom, animated: true)
    }

    // MARK: Location (for the ride group)

    func observeLocation(onFix: @escaping (LocationFix) -> Void) {
        self.onFix = onFix
        locationManager.startUpdatingLocation()
    }

    /// While in a ride group, keep sharing location with the screen off. iOS shows the blue location pill.
    func setBackgroundLocation(enabled: Bool) {
        locationManager.allowsBackgroundLocationUpdates = enabled
        locationManager.showsBackgroundLocationIndicator = enabled
        locationManager.pausesLocationUpdatesAutomatically = !enabled
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if onFix != nil { manager.startUpdatingLocation() }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let l = locations.last, l.horizontalAccuracy >= 0 else { return }
        onFix?(MapBridgeKt.makeFix(lat: l.coordinate.latitude, lon: l.coordinate.longitude, speedMps: l.speed, course: l.course))
    }

    // MARK: Riders and meeting point

    func showRiders(riders: [RiderPin]) {
        let wanted = Set(riders.map { $0.uid })
        for (uid, a) in riderAnnotations where !wanted.contains(uid) {
            map.removeAnnotation(a)
            riderAnnotations[uid] = nil
        }
        for r in riders {
            let coordinate = CLLocationCoordinate2D(latitude: r.lat, longitude: r.lon)
            if let a = riderAnnotations[r.uid], a.kind == r.kind, a.faded == r.faded, a.title == r.name {
                a.coordinate = coordinate
                a.subtitle = r.label
            } else {
                if let old = riderAnnotations[r.uid] { map.removeAnnotation(old) }
                let a = RiderAnnotation(kind: r.kind, faded: r.faded)
                a.coordinate = coordinate
                a.title = r.name
                a.subtitle = r.label
                riderAnnotations[r.uid] = a
                map.addAnnotation(a)
            }
        }
    }

    func showMeetingPoint(point: GeoPoint?, title: String) {
        if let old = meetAnnotation { map.removeAnnotation(old) }
        meetAnnotation = nil
        guard let p = point else { return }
        let a = MeetAnnotation()
        a.coordinate = CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon)
        a.title = title
        a.subtitle = "Meeting point"
        meetAnnotation = a
        map.addAnnotation(a)
    }

    func mapView(_ mapView: MLNMapView, viewFor annotation: MLNAnnotation) -> MLNAnnotationView? {
        if let r = annotation as? RiderAnnotation {
            let color: UIColor
            switch r.kind {
            case "sos": color = UIColor(red: 0.83, green: 0.18, blue: 0.18, alpha: 1)
            case "break": color = UIColor(red: 0.98, green: 0.66, blue: 0.15, alpha: 1)
            case "leader": color = UIColor(red: 0.91, green: 0.44, blue: 0.10, alpha: 1)
            case "sweep": color = UIColor(red: 0.15, green: 0.20, blue: 0.22, alpha: 1)
            default: color = UIColor(red: 0.08, green: 0.40, blue: 0.75, alpha: 1)
            }
            return BadgeView(text: String((r.title ?? "R").prefix(1)).uppercased(), color: color, faded: r.faded)
        }
        if annotation is MeetAnnotation {
            return BadgeView(text: "⚑", color: UIColor(red: 0.0, green: 0.54, blue: 0.48, alpha: 1), faded: false)
        }
        return nil
    }

    func mapView(_ mapView: MLNMapView, annotationCanShowCallout annotation: MLNAnnotation) -> Bool { true }

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

/// Another rider on the map.
final class RiderAnnotation: MLNPointAnnotation {
    let kind: String
    let faded: Bool
    init(kind: String, faded: Bool) {
        self.kind = kind
        self.faded = faded
        super.init()
    }
    required init?(coder: NSCoder) { fatalError("not used") }
}

/// The group's meeting point.
final class MeetAnnotation: MLNPointAnnotation {}

/// A round marker with a letter, coloured by role (like the rider dots on Android).
final class BadgeView: MLNAnnotationView {
    init(text: String, color: UIColor, faded: Bool) {
        super.init(reuseIdentifier: nil)
        frame = CGRect(x: 0, y: 0, width: 34, height: 34)
        backgroundColor = color
        layer.cornerRadius = 17
        layer.borderColor = UIColor.white.cgColor
        layer.borderWidth = 3
        layer.shadowOpacity = 0.3
        layer.shadowRadius = 2
        layer.shadowOffset = CGSize(width: 0, height: 1)
        alpha = faded ? 0.5 : 1
        let label = UILabel(frame: bounds)
        label.text = text
        label.textAlignment = .center
        label.textColor = .white
        label.font = .boldSystemFont(ofSize: 16)
        addSubview(label)
    }
    required init?(coder: NSCoder) { fatalError("not used") }
}
