import SwiftUI
import Shared

@main
struct SawaariApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea(.all)
        }
    }
}

/// Shows the shared Compose Multiplatform screens.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
