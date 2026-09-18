import Foundation
import LocalAuthentication
import SwiftUI

@MainActor
final class AppLockController: ObservableObject {
    @Published private(set) var enabled: Bool
    @Published private(set) var unlocked: Bool
    @Published var message: String?

    private let settingKey = "healthTimeline.appLockEnabled"
    private var backgroundedAt: Date?
    private var externalPickerDepth = 0

    init() {
        enabled = UserDefaults.standard.bool(forKey: settingKey)
        unlocked = !enabled
    }

    func setEnabled(_ value: Bool) async {
        if value {
            guard await authenticate(reason: "开启病程日历保护") else { return }
        }
        enabled = value
        unlocked = !value || unlocked
        UserDefaults.standard.set(value, forKey: settingKey)
    }

    @discardableResult
    func authenticate(reason: String = "查看病程日历") async -> Bool {
        guard enabled || reason.contains("开启") else { unlocked = true; return true }
        let context = LAContext()
        context.localizedCancelTitle = "取消"
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            message = "请先在系统中设置面容、指纹或锁屏密码"
            unlocked = false
            return false
        }
        do {
            let success = try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)
            unlocked = success
            return success
        } catch {
            unlocked = false
            message = "身份验证未完成"
            return false
        }
    }

    func beginExternalPicker() { externalPickerDepth += 1 }
    func endExternalPicker() { externalPickerDepth = max(0, externalPickerDepth - 1) }

    func handle(_ phase: ScenePhase) {
        guard enabled else { unlocked = true; return }
        switch phase {
        case .background:
            if externalPickerDepth == 0 { backgroundedAt = Date() }
        case .active:
            if externalPickerDepth == 0, let backgroundedAt, Date().timeIntervalSince(backgroundedAt) >= 30 {
                unlocked = false
            }
            self.backgroundedAt = nil
        default:
            break
        }
    }
}

struct AppLockGate: View {
    @EnvironmentObject private var lock: AppLockController

    var body: some View {
        ZStack {
            Color(.systemBackground).ignoresSafeArea()
            VStack(spacing: 18) {
                Image(systemName: "lock.shield").font(.system(size: 48)).foregroundStyle(.tint)
                Text("病程日历已锁定").font(.title2.bold())
                Button("验证身份") { Task { await lock.authenticate() } }.buttonStyle(.borderedProminent)
                if let message = lock.message { Text(message).font(.footnote).foregroundStyle(.secondary) }
            }
        }
    }
}
