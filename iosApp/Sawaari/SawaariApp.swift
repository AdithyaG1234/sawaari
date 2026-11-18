import Shared
import SwiftUI
import UserNotifications

@main
struct SawaariApp: App {
    private static let notifications = ForegroundNotifications()

    init() {
        UNUserNotificationCenter.current().delegate = Self.notifications
    }

    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea(.all)
                // Route links shared from Sawaari (sawaari://route?...) open as a ride plan.
                .onOpenURL { url in IosSafetyKt.openLink(url: url.absoluteString) }
        }
    }
}

/// Shows the shared Compose Multiplatform screens, with the MapLibre map from MapBridge.swift.
struct ComposeView: UIViewControllerRepresentable {
    private static let bridge = MapBridge()
    private static let firebase = FirebaseBridge()

    func makeUIViewController(context: Context) -> UIViewController {
        AppKt.MainViewController(bridge: Self.bridge, backend: Self.firebase)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

/// Shows Sawaari's notifications (group alerts, service reminders, crash countdown) while the app is open too.
final class ForegroundNotifications: NSObject, UNUserNotificationCenterDelegate {
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }
}
