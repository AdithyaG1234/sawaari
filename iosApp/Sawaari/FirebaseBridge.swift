import FirebaseAuth
import FirebaseCore
import FirebaseDatabase
import Foundation
import Shared

/// Firebase for the shared group-ride code (GroupClient.kt): anonymous sign-in and the Realtime Database,
/// the same project, rules and data as the Android app. Values cross to Kotlin as JSON text.
final class FirebaseBridge: NSObject, GroupBackend {
    private let configured: Bool
    private var observers: [Int64: (DatabaseQuery, DatabaseHandle)] = [:]
    private var nextHandle: Int64 = 1

    override init() {
        // GoogleService-Info.plist comes from the Firebase console (iOS app "app.sawaari").
        // Without it the app still runs, and the Ride screen explains that groups aren't set up.
        if FirebaseApp.app() == nil, Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil {
            FirebaseApp.configure()
        }
        configured = FirebaseApp.app() != nil
        super.init()
    }

    /// Like Android's sawaariDatabase(): the plist only carries the database address if it was downloaded
    /// after the database was created, so fall back to the Singapore (asia-southeast1) default.
    private lazy var db: Database = {
        let options = FirebaseApp.app()!.options
        let url = options.databaseURL ?? "https://\(options.projectID ?? "")-default-rtdb.asia-southeast1.firebasedatabase.app"
        return Database.database(url: url)
    }()

    func isConfigured() -> Bool { configured }

    func currentUid() -> String? { configured ? Auth.auth().currentUser?.uid : nil }

    func signIn(callback: @escaping (String?, String?) -> Void) {
        guard configured else { return callback(nil, "Group rides aren't set up in this build yet.") }
        if let uid = Auth.auth().currentUser?.uid { return callback(uid, nil) }
        Auth.auth().signInAnonymously { result, error in
            callback(result?.user.uid, error?.localizedDescription)
        }
    }

    func read(path: String, callback: @escaping (String?, String?) -> Void) {
        db.reference(withPath: path).getData { error, snapshot in
            if let error = error { return callback(nil, error.localizedDescription) }
            callback(Self.json(snapshot?.value), nil)
        }
    }

    func write(path: String, json: String?, callback: @escaping (String?) -> Void) {
        let ref = db.reference(withPath: path)
        if let value = Self.value(json), !(value is NSNull) {
            ref.setValue(value) { error, _ in callback(error?.localizedDescription) }
        } else {
            ref.removeValue { error, _ in callback(error?.localizedDescription) }
        }
    }

    func update(path: String, json: String, callback: @escaping (String?) -> Void) {
        guard let values = Self.value(json) as? [String: Any] else { return callback("Bad update") }
        // NSNull values delete those children, like null in Android's updateChildren.
        db.reference(withPath: path).updateChildValues(values) { error, _ in callback(error?.localizedDescription) }
    }

    func push(path: String, json: String, callback: @escaping (String?, String?) -> Void) {
        let ref = db.reference(withPath: path).childByAutoId()
        ref.setValue(Self.value(json)) { error, _ in
            callback(error == nil ? ref.key : nil, error?.localizedDescription)
        }
    }

    func observe(
        path: String,
        orderByChild: String?,
        limitToLast: Int32,
        onValue: @escaping (String?) -> Void,
        onError: @escaping (String) -> Void
    ) -> Int64 {
        var query: DatabaseQuery = db.reference(withPath: path)
        if let child = orderByChild { query = query.queryOrdered(byChild: child) }
        if limitToLast > 0 { query = query.queryLimited(toLast: UInt(limitToLast)) }
        let handle = query.observe(.value, with: { snapshot in
            onValue(Self.json(snapshot.value))
        }, withCancel: { error in
            onError(error.localizedDescription)
        })
        let id = nextHandle
        nextHandle += 1
        observers[id] = (query, handle)
        return id
    }

    func stopObserving(handle: Int64) {
        guard let (query, h) = observers.removeValue(forKey: handle) else { return }
        query.removeObserver(withHandle: h)
    }

    private static func json(_ value: Any?) -> String? {
        guard let value = value, !(value is NSNull) else { return nil }
        guard let data = try? JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed]) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private static func value(_ json: String?) -> Any? {
        guard let data = json?.data(using: .utf8) else { return nil }
        return try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
    }
}
