import Shared
import SwiftUI

@main
struct SawaariApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea(.all)
        }
    }
}

/// Shows the shared Compose Multiplatform screens, with the MapLibre map from MapBridge.swift.
struct ComposeView: UIViewControllerRepresentable {
    private static let bridge = MapBridge()

    func makeUIViewController(context: Context) -> UIViewController {
        AppKt.MainViewController(bridge: Self.bridge)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
