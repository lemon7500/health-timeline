import SwiftUI
#if canImport(HealthTimelineCore)
import HealthTimelineCore
#endif

@main
struct HealthTimelineApp: App {
    @StateObject private var store = HealthStore()
    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(store).task {
                _ = await LocalReminderScheduler.requestPermission()
                await LocalReminderScheduler.rebuild(store.state)
            }.alert("已停止打开数据", isPresented: Binding(get: { store.errorMessage != nil }, set: { _ in })) {
                Button("知道了") {}
            } message: { Text(store.errorMessage ?? "未知错误。应用没有清空现有资料。") }
        }
    }
}
