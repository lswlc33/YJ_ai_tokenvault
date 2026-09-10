import SwiftUI
import Shared

// 阶段4 落地：真正的 UI 全在 shared 的 Compose Multiplatform 树里
// （AppRoot → LockGate → VaultShell，与 Android 端同一份代码）。
// Swift 这一层只做壳：把 Compose 的 UIViewController 包进 SwiftUI 的 WindowGroup。
// 密钥/密码相关的弹层全部画在 Compose 的同一渲染树里，不走独立的 UIKit 窗口。
@main
struct TokenVaultApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                // SwiftUI 只负责挂载 Compose；安全区与键盘避让都由 Compose 侧的 Scaffold 处理。
                // 不忽略 SwiftUI 安全区时，SwiftUI 先裁一次、Compose 再留一次 inset，
                // iPhone 上会表现为上下各多出一大块空白。
                .ignoresSafeArea(.all)
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
