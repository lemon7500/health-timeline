import SwiftUI
import UniformTypeIdentifiers

struct SettingsHome: View {
    @EnvironmentObject private var store: HealthStore
    @EnvironmentObject private var lock: AppLockController
    @State private var notificationStatus = "正在检查…"
    @State private var backupPassword = ""
    @State private var exportDocument: HealthTimelineBackupDocument?
    @State private var exporting = false
    @State private var importing = false
    @State private var prepared: PreparedPortableBackup?

    var body: some View {
        NavigationStack {
            Form {
                Section("家庭成员") {
                    NavigationLink("管理成员") { MemberManagementView() }
                    if let member = store.currentMember { LabeledContent("当前成员", value: member.displayName) }
                }
                Section("隐私保护") {
                    Toggle("使用面容、指纹或锁屏密码", isOn: Binding(
                        get: { lock.enabled },
                        set: { value in Task { await lock.setEnabled(value) } }
                    ))
                    Text("离开应用超过约 30 秒后再次验证。数据库密钥保存在 Keychain，附件使用系统数据保护。")
                        .font(.footnote).foregroundStyle(.secondary)
                    if let message = lock.message { Text(message).font(.footnote).foregroundStyle(.orange) }
                }
                Section("提醒") {
                    LabeledContent("通知权限", value: notificationStatus)
                    Button("申请通知权限") {
                        Task { _ = await LocalReminderScheduler.requestPermission(); await refreshNotificationStatus(); await LocalReminderScheduler.rebuild(store.state) }
                    }
                    Text("系统最多保留有限数量的待处理通知；应用每次启动、修改或导入后会刷新最近一批提醒。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Section("加密备份 v4") {
                    SecureField("备份密码（至少 8 位）", text: $backupPassword)
                    Button("导出整个家庭的加密备份") { makeExport() }.disabled(backupPassword.count < 8)
                    Button("导入并合并备份") { lock.beginExternalPicker(); importing = true }.disabled(backupPassword.count < 8)
                    Button("导出最近一次自动安全备份") { exportLatestSafetyBackup() }
                    Text("导入会先验证密码、版本、引用、附件大小和 SHA-256。冲突默认保留本机资料，验证失败不会更改现有数据。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Section("说明") {
                    Text("全部资料仅保存在本机；应用不申请网络权限。")
                    Text("本应用只用于记录和提醒，不提供诊断、处方或药物安全建议。")
                        .foregroundStyle(.red)
                }
            }
            .navigationTitle("设置")
            .task { await refreshNotificationStatus() }
            .fileExporter(isPresented: $exporting, document: exportDocument,
                          contentType: .healthTimelineBackup,
                          defaultFilename: "HealthTimeline-\(HealthDate.day(Date())).htbackup") { result in
                if case .failure(let error) = result { store.errorMessage = error.localizedDescription }
                exportDocument = nil
            }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.healthTimelineBackup, .data]) { result in
                lock.endExternalPicker()
                guard case .success(let url) = result else {
                    if case .failure(let error) = result { store.errorMessage = error.localizedDescription }
                    return
                }
                readImport(url)
            }
            .onChange(of: importing) { if !$0 { lock.endExternalPicker() } }
            .sheet(isPresented: Binding(get: { prepared != nil }, set: { if !$0 { prepared = nil } })) {
                if let prepared {
                    BackupPreviewView(
                        prepared: prepared,
                        confirmMerge: { selected in
                            if store.mergeBackup(prepared, useImported: selected) { self.prepared = nil; backupPassword = "" }
                        },
                        confirmReplace: {
                            if store.replaceBackup(prepared, password: backupPassword) { self.prepared = nil; backupPassword = "" }
                        }
                    )
                }
            }
        }
    }

    private func makeExport() {
        do {
            exportDocument = HealthTimelineBackupDocument(data: try store.exportBackup(password: backupPassword))
            exporting = true
        } catch { store.errorMessage = error.localizedDescription }
    }

    private func readImport(_ url: URL) {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        do { prepared = try store.prepareBackupImport(data: Data(contentsOf: url, options: [.mappedIfSafe]), password: backupPassword) }
        catch { store.errorMessage = error.localizedDescription }
    }

    private func exportLatestSafetyBackup() {
        do {
            exportDocument = HealthTimelineBackupDocument(data: try BackupSafetyStore.latestData())
            exporting = true
        } catch { store.errorMessage = error.localizedDescription }
    }

    private func refreshNotificationStatus() async {
        switch await LocalReminderScheduler.authorizationStatus() {
        case .authorized, .provisional, .ephemeral: notificationStatus = "已允许"
        case .denied: notificationStatus = "已拒绝，请到系统设置开启"
        case .notDetermined: notificationStatus = "尚未询问"
        @unknown default: notificationStatus = "未知"
        }
    }
}

private struct BackupPreviewView: View {
    @Environment(\.dismiss) private var dismiss
    let prepared: PreparedPortableBackup
    let confirmMerge: (Set<String>) -> Void
    let confirmReplace: () -> Void
    @State private var useImported: Set<String> = []
    @State private var confirmingReplacement = false
    var body: some View {
        NavigationStack {
            Form {
                Section("导入预览") {
                    LabeledContent("新增", value: "\(prepared.preview.additions)")
                    LabeledContent("可更新/冲突", value: "\(prepared.preview.updates)")
                    LabeledContent("完全重复", value: "\(prepared.preview.duplicates)")
                }
                if !prepared.preview.conflicts.isEmpty {
                    Section("冲突处理") {
                        Text("每一项默认保留本机版本；只有手动开启的项目才使用导入版本。")
                        ForEach(prepared.preview.conflicts) { conflict in
                            Toggle(isOn: Binding(
                                get: { useImported.contains(conflict.key) },
                                set: { enabled in
                                    if enabled { useImported.insert(conflict.key) }
                                    else { useImported.remove(conflict.key) }
                                }
                            )) {
                                VStack(alignment: .leading) {
                                    Text(conflictLabel(conflict.entity))
                                    Text(conflictDetail(conflict)).font(.caption).foregroundStyle(.secondary)
                                    Text(conflict.uuid.uuidString).font(.caption2).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
                Section { Text("保存时使用一次数据库提交；任一附件安装失败都不会留下半份导入资料。") }
                Section("高级操作") {
                    Button("用备份整体替换本机资料", role: .destructive) { confirmingReplacement = true }
                    Text("执行前会先在应用受保护目录生成一份当前数据的加密安全备份；如果安全备份失败，不会开始替换。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("核对备份")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("确认合并") { confirmMerge(useImported) } }
            }
            .confirmationDialog("确定整体替换本机资料？", isPresented: $confirmingReplacement, titleVisibility: .visible) {
                Button("生成安全备份并替换", role: .destructive, action: confirmReplace)
                Button("取消", role: .cancel) {}
            } message: {
                Text("当前资料会被导入备份整体替换。应用会先自动保存一份加密安全备份。")
            }
        }
    }

    private func conflictLabel(_ entity: String) -> String {
        ["member": "家庭成员", "condition": "病情分类", "record": "病历", "attachment": "附件",
         "followup": "复查计划", "occurrence": "复查记录", "medication": "药物",
         "medicationSchedule": "用药计划", "medicationLog": "服药记录"][entity] ?? entity
    }


    private func conflictDetail(_ conflict: BackupConflict) -> String {
        let local = prepared.previewStateLocal
        let imported = prepared.importedState
        switch conflict.entity {
        case "member": return versions(local.members.first { $0.id == conflict.uuid }?.displayName, imported.members.first { $0.id == conflict.uuid }?.displayName)
        case "condition": return versions(local.conditions.first { $0.id == conflict.uuid }?.name, imported.conditions.first { $0.id == conflict.uuid }?.name)
        case "record": return versions(local.records.first { $0.id == conflict.uuid }?.title, imported.records.first { $0.id == conflict.uuid }?.title)
        case "attachment": return versions(local.attachments.first { $0.id == conflict.uuid }?.displayName, imported.attachments.first { $0.id == conflict.uuid }?.displayName)
        case "followup": return versions(local.followUps.first { $0.id == conflict.uuid }?.title, imported.followUps.first { $0.id == conflict.uuid }?.title)
        case "occurrence": return versions(local.occurrences.first { $0.id == conflict.uuid }.map { HealthDate.day($0.dueDate) }, imported.occurrences.first { $0.id == conflict.uuid }.map { HealthDate.day($0.dueDate) })
        case "medication": return versions(local.medications.first { $0.id == conflict.uuid }?.name, imported.medications.first { $0.id == conflict.uuid }?.name)
        case "medicationSchedule": return versions(local.medicationSchedules.first { $0.id == conflict.uuid }?.localTime, imported.medicationSchedules.first { $0.id == conflict.uuid }?.localTime)
        case "medicationLog": return versions(local.medicationLogs.first { $0.id == conflict.uuid }.map { HealthDate.localDateTime($0.scheduledAt) }, imported.medicationLogs.first { $0.id == conflict.uuid }.map { HealthDate.localDateTime($0.scheduledAt) })
        default: return "内容不同"
        }
    }

    private func versions(_ local: String?, _ imported: String?) -> String {
        "本机：\(local ?? "—")；导入：\(imported ?? "—")"
    }
}

private struct MemberManagementView: View {
    @EnvironmentObject private var store: HealthStore
    @State private var editing: FamilyMember?
    @State private var adding = false
    @State private var archiveCandidate: FamilyMember?
    @State private var deleteCandidate: FamilyMember?

    var body: some View {
        List {
            Section("有效成员") {
                ForEach(store.state.members.filter { !$0.archived }) { member in row(member) }
            }
            if store.state.members.contains(where: \.archived) {
                Section("已归档") { ForEach(store.state.members.filter(\.archived)) { member in row(member) } }
            }
        }
        .navigationTitle("家庭成员")
        .toolbar { Button { adding = true } label: { Image(systemName: "plus") } }
        .sheet(isPresented: $adding) { MemberEditor() }
        .sheet(item: $editing) { MemberEditor(member: $0) }
        .confirmationDialog(archiveCandidate?.archived == true ? "恢复该成员？" : "归档后将暂停该成员的提醒。", isPresented: Binding(get: { archiveCandidate != nil }, set: { if !$0 { archiveCandidate = nil } })) {
            Button(archiveCandidate?.archived == true ? "恢复" : "归档") {
                if let member = archiveCandidate { _ = store.setMemberArchived(member.id, archived: !member.archived) }
                archiveCandidate = nil
            }
            Button("取消", role: .cancel) { archiveCandidate = nil }
        }
        .confirmationDialog("只可删除没有任何历史资料的成员。", isPresented: Binding(get: { deleteCandidate != nil }, set: { if !$0 { deleteCandidate = nil } })) {
            Button("删除", role: .destructive) { if let member = deleteCandidate { _ = store.deleteEmptyMember(member.id) }; deleteCandidate = nil }
            Button("取消", role: .cancel) { deleteCandidate = nil }
        }
    }

    private func row(_ member: FamilyMember) -> some View {
        HStack {
            VStack(alignment: .leading) { Text(member.displayName); Text(member.name).font(.caption).foregroundStyle(.secondary) }
            Spacer()
            Menu {
                Button("编辑") { editing = member }
                Button(member.archived ? "恢复" : "归档") { archiveCandidate = member }
                Button("删除空成员", role: .destructive) { deleteCandidate = member }
            } label: { Image(systemName: "ellipsis.circle") }
        }
    }
}

private struct MemberEditor: View {
    @EnvironmentObject private var store: HealthStore
    @Environment(\.dismiss) private var dismiss
    let member: FamilyMember?
    @State private var name: String
    @State private var relationship: String

    init(member: FamilyMember? = nil) {
        self.member = member
        _name = State(initialValue: member?.name ?? "")
        _relationship = State(initialValue: member?.relationship ?? "")
    }
    var body: some View {
        NavigationStack {
            Form { TextField("姓名", text: $name); TextField("关系（例如：本人、母亲）", text: $relationship) }
                .navigationTitle(member == nil ? "新增成员" : "编辑成员")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) { Button("保存") { if store.saveMember(id: member?.id, name: name, relationship: relationship) { dismiss() } } }
                }
        }
    }
}
