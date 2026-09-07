import SwiftUI
import Shared

// 阶段4 的空壳入口：只负责把 shared.framework 链接进来，展示一个占位视图。
// 真正的 iOS UI 要等 Compose Multiplatform 迁移后才填充（见计划.md 阶段4/5）。
// 这个入口的目的：验证 iOS CI 链路能产 .app / .ipa，不伪装成已完成产品。
@main
struct TokenVaultApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

struct ContentView: View {
    // 调用 shared.framework 里的真实代码，验证 framework 已成功编译并链接。
    private let bridgeMessage = IosBridge.shared.sharedGreeting()

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "lock.shield")
                .font(.system(size: 56))
                .foregroundColor(.accentColor)
            Text("TokenVault")
                .font(.largeTitle.bold())
            Text(bridgeMessage)
                .font(.footnote)
                .foregroundColor(.secondary)
            Text("iOS 端尚未实现（阶段4）")
                .font(.caption)
                .foregroundColor(.secondary)
        }
    }
}
