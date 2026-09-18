import SwiftUI
#if canImport(HealthTimelineCore)
import HealthTimelineCore
#endif

@main
struct HealthTimelineApp: App {
    @StateObject private var store = HealthStore()
    @StateObject private var lock = AppLockController()
    @Environment(\.scenePhase) private var scenePhase
    var body: some Scene {
        WindowGroup {
            Group {
                if lock.enabled && !lock.unlocked { AppLockGate() }
                else { RootView() }
            }
            .environmentObject(store)
            .environmentObject(lock)
            .task {
                if lock.enabled { _ = await lock.authenticate() }
                await LocalReminderScheduler.rebuild(store.state)
            }
            .onChange(of: scenePhase) { lock.handle($0) }
            .alert("数据操作未完成", isPresented: Binding(get: { store.errorMessage != nil }, set: { if !$0 { store.clearError() } })) {
                Button("知道了") { store.clearError() }
            } message: { Text(store.errorMessage ?? "未知错误。应用没有清空现有资料。") }
            .alert("提示", isPresented: Binding(get: { store.noticeMessage != nil }, set: { if !$0 { store.noticeMessage = nil } })) {
                Button("知道了") { store.noticeMessage = nil }
            } message: { Text(store.noticeMessage ?? "") }
        }
    }
}
