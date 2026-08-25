import Runestone
import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct AlpineFileEntry: Identifiable, Hashable {
    var id: String { path }

    let name: String
    let path: String
    let isDirectory: Bool
    let isSymbolicLink: Bool
    let size: Int64
    let modifiedAt: Date
    let mode: UInt32

    var fileExtension: String {
        URL(fileURLWithPath: name).pathExtension.lowercased()
    }

    var iconName: String {
        if isDirectory { return "folder.fill" }
        if Self.imageExtensions.contains(fileExtension) { return "photo" }
        if Self.codeExtensions.contains(fileExtension) { return "chevron.left.forwardslash.chevron.right" }
        if Self.archiveExtensions.contains(fileExtension) { return "archivebox" }
        return "doc.text"
    }

    var iconColor: Color {
        if isDirectory { return .accentColor }
        if Self.imageExtensions.contains(fileExtension) { return .green }
        if Self.codeExtensions.contains(fileExtension) { return .orange }
        return .secondary
    }

    private static let imageExtensions: Set<String> = ["png", "jpg", "jpeg", "gif", "webp", "bmp", "heic"]
    private static let archiveExtensions: Set<String> = ["zip", "tar", "gz", "tgz", "bz2", "xz", "7z"]
    private static let codeExtensions: Set<String> = [
        "c", "cc", "cpp", "css", "go", "h", "hpp", "html", "java", "js", "json", "jsx", "kt",
        "kts", "md", "mjs", "py", "rb", "rs", "sh", "sql", "swift", "toml", "ts", "tsx", "xml",
        "yaml", "yml",
    ]
}

struct AlpineFileManagerView: View {
    @Environment(\.dismiss) private var dismiss
    let host: SunshineRuntimeHost

    var body: some View {
        NavigationStack {
            AlpineDirectoryView(host: host, path: "/")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Done") { dismiss() }
                    }
                }
        }
    }
}

@MainActor
private final class AlpineDirectoryModel: ObservableObject {
    enum SortOrder: String, CaseIterable, Identifiable {
        case name = "Name"
        case date = "Date"
        case size = "Size"

        var id: String { rawValue }
    }

    @Published var entries: [AlpineFileEntry] = []
    @Published var isLoading = false
    @Published var errorMessage: String?
    @Published var showsHiddenFiles = false
    @Published var sortOrder: SortOrder = .name
    @Published var isImporting = false

    let host: SunshineRuntimeHost
    let path: String

    init(host: SunshineRuntimeHost, path: String) {
        self.host = host
        self.path = path
    }

    func load() {
        isLoading = true
        host.listGuestDirectory(path: path) { [weak self] result in
            guard let self else { return }
            self.isLoading = false
            switch result {
            case let .success(entries): self.entries = entries
            case let .failure(error): self.errorMessage = error.localizedDescription
            }
        }
    }

    func visibleEntries(matching query: String) -> [AlpineFileEntry] {
        entries
            .filter { showsHiddenFiles || !$0.name.hasPrefix(".") }
            .filter { query.isEmpty || $0.name.localizedCaseInsensitiveContains(query) }
            .sorted { lhs, rhs in
                if lhs.isDirectory != rhs.isDirectory { return lhs.isDirectory }
                switch sortOrder {
                case .name:
                    return lhs.name.localizedStandardCompare(rhs.name) == .orderedAscending
                case .date:
                    return lhs.modifiedAt > rhs.modifiedAt
                case .size:
                    if lhs.size == rhs.size {
                        return lhs.name.localizedStandardCompare(rhs.name) == .orderedAscending
                    }
                    return lhs.size > rhs.size
                }
            }
    }

    func create(name: String, directory: Bool) {
        let target = childPath(name)
        let completion: (Result<Void, Error>) -> Void = { [weak self] result in
            switch result {
            case .success: self?.load()
            case let .failure(error): self?.errorMessage = error.localizedDescription
            }
        }
        if directory {
            host.createGuestDirectory(path: target, completion: completion)
        } else {
            host.createGuestFile(path: target, completion: completion)
        }
    }

    func rename(_ entry: AlpineFileEntry, to name: String) {
        host.moveGuestPath(from: entry.path, to: childPath(name)) { [weak self] result in
            switch result {
            case .success: self?.load()
            case let .failure(error): self?.errorMessage = error.localizedDescription
            }
        }
    }

    func delete(_ entry: AlpineFileEntry) {
        host.removeGuestPath(path: entry.path, recursive: entry.isDirectory) { [weak self] result in
            switch result {
            case .success: self?.load()
            case let .failure(error): self?.errorMessage = error.localizedDescription
            }
        }
    }

    func importItems(_ urls: [URL]) {
        guard !urls.isEmpty else { return }
        isImporting = true
        host.importGuestItems(urls: urls, destinationDirectory: path) { [weak self] result in
            guard let self else { return }
            self.isImporting = false
            switch result {
            case .success: self.load()
            case let .failure(error): self.errorMessage = error.localizedDescription
            }
        }
    }

    func validateName(_ name: String, excluding entry: AlpineFileEntry? = nil) -> String? {
        let value = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty, value != ".", value != "..", !value.contains("/") else { return nil }
        guard !entries.contains(where: { $0.name == value && $0.id != entry?.id }) else {
            errorMessage = "An item named \(value) already exists."
            return nil
        }
        return value
    }

    private func childPath(_ name: String) -> String {
        path == "/" ? "/\(name)" : "\(path)/\(name)"
    }
}

private struct AlpineDirectoryView: View {
    private enum NewItemKind { case file, folder }
    private enum ImportKind { case files, folder }

    @StateObject private var model: AlpineDirectoryModel
    @State private var query = ""
    @State private var newItemKind: NewItemKind?
    @State private var draftName = ""
    @State private var renamedEntry: AlpineFileEntry?
    @State private var pendingDeletion: AlpineFileEntry?
    @State private var importKind: ImportKind?

    init(host: SunshineRuntimeHost, path: String) {
        _model = StateObject(wrappedValue: AlpineDirectoryModel(host: host, path: path))
    }

    var body: some View {
        Group {
            if model.isLoading && model.entries.isEmpty {
                ProgressView()
            } else if model.visibleEntries(matching: query).isEmpty {
                ContentUnavailableView(
                    query.isEmpty ? "Empty Folder" : "No Results",
                    systemImage: query.isEmpty ? "folder" : "magnifyingglass",
                    description: query.isEmpty ? nil : Text("No files match \"\(query)\".")
                )
            } else {
                List(model.visibleEntries(matching: query)) { entry in
                    NavigationLink {
                        if entry.isDirectory {
                            AlpineDirectoryView(host: model.host, path: entry.path)
                        } else {
                            AlpineFileView(host: model.host, entry: entry)
                        }
                    } label: {
                        AlpineFileRow(entry: entry)
                    }
                    .contextMenu {
                        Button {
                            renamedEntry = entry
                            draftName = entry.name
                        } label: {
                            Label("Rename", systemImage: "pencil")
                        }
                        Button(role: .destructive) {
                            pendingDeletion = entry
                        } label: {
                            Label("Delete", systemImage: "trash")
                        }
                    }
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        Button(role: .destructive) { pendingDeletion = entry } label: {
                            Label("Delete", systemImage: "trash")
                        }
                        Button {
                            renamedEntry = entry
                            draftName = entry.name
                        } label: {
                            Label("Rename", systemImage: "pencil")
                        }
                        .tint(.orange)
                    }
                }
                .listStyle(.plain)
                .refreshable { model.load() }
            }
        }
        .navigationTitle(model.path == "/" ? "Alpine" : URL(fileURLWithPath: model.path).lastPathComponent)
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .automatic), prompt: "Search this folder")
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                Menu {
                    Picker("Sort", selection: $model.sortOrder) {
                        ForEach(AlpineDirectoryModel.SortOrder.allCases) { order in
                            Text(order.rawValue).tag(order)
                        }
                    }
                    Toggle("Show Hidden Files", isOn: $model.showsHiddenFiles)
                    Button { model.load() } label: {
                        Label("Refresh", systemImage: "arrow.clockwise")
                    }
                } label: {
                    Image(systemName: "line.3.horizontal.decrease.circle")
                }
                .accessibilityLabel("View options")

                if model.isImporting {
                    ProgressView()
                        .controlSize(.small)
                } else {
                    Menu {
                        Button {
                            draftName = ""
                            newItemKind = .file
                        } label: {
                            Label("New File", systemImage: "doc.badge.plus")
                        }
                        Button {
                            draftName = ""
                            newItemKind = .folder
                        } label: {
                            Label("New Folder", systemImage: "folder.badge.plus")
                        }
                        Button {
                            importKind = .files
                        } label: {
                            Label("Import Files", systemImage: "square.and.arrow.down")
                        }
                        Button {
                            importKind = .folder
                        } label: {
                            Label("Import Folder", systemImage: "square.and.arrow.down")
                        }
                    } label: {
                        Image(systemName: "plus")
                    }
                    .accessibilityLabel("Create")
                }
            }
        }
        .task { if model.entries.isEmpty { model.load() } }
        .fileImporter(
            isPresented: Binding(
                get: { importKind != nil },
                set: { if !$0 { importKind = nil } }
            ),
            allowedContentTypes: importKind == .folder ? [.folder] : [.item],
            allowsMultipleSelection: true
        ) { result in
            defer { importKind = nil }
            switch result {
            case let .success(urls): model.importItems(urls)
            case let .failure(error): model.errorMessage = error.localizedDescription
            }
        }
        .alert(newItemKind == .folder ? "New Folder" : "New File", isPresented: Binding(
            get: { newItemKind != nil },
            set: { if !$0 { newItemKind = nil } }
        )) {
            TextField("Name", text: $draftName)
            Button("Create") {
                guard let kind = newItemKind, let name = model.validateName(draftName) else { return }
                model.create(name: name, directory: kind == .folder)
                newItemKind = nil
            }
            Button("Cancel", role: .cancel) { newItemKind = nil }
        }
        .alert("Rename", isPresented: Binding(
            get: { renamedEntry != nil },
            set: { if !$0 { renamedEntry = nil } }
        )) {
            TextField("Name", text: $draftName)
            Button("Rename") {
                guard let entry = renamedEntry, let name = model.validateName(draftName, excluding: entry) else { return }
                if name != entry.name { model.rename(entry, to: name) }
                renamedEntry = nil
            }
            Button("Cancel", role: .cancel) { renamedEntry = nil }
        }
        .confirmationDialog(
            "Delete \(pendingDeletion?.name ?? "item")?",
            isPresented: Binding(
                get: { pendingDeletion != nil },
                set: { if !$0 { pendingDeletion = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Delete", role: .destructive) {
                if let entry = pendingDeletion { model.delete(entry) }
                pendingDeletion = nil
            }
            Button("Cancel", role: .cancel) { pendingDeletion = nil }
        } message: {
            if pendingDeletion?.isDirectory == true {
                Text("The folder and all of its contents will be permanently deleted.")
            }
        }
        .alert("File Operation Failed", isPresented: Binding(
            get: { model.errorMessage != nil },
            set: { if !$0 { model.errorMessage = nil } }
        )) {
            Button("OK") { model.errorMessage = nil }
        } message: {
            Text(model.errorMessage ?? "Unknown error")
        }
    }
}

private struct AlpineFileRow: View {
    let entry: AlpineFileEntry

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: entry.iconName)
                .font(.title3)
                .foregroundStyle(entry.iconColor)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 5) {
                    Text(entry.name).lineLimit(1)
                    if entry.isSymbolicLink {
                        Image(systemName: "arrow.trianglehead.branch").font(.caption2).foregroundStyle(.secondary)
                    }
                }
                Text(entry.isDirectory ? entry.modifiedAt.formatted(date: .abbreviated, time: .shortened) : ByteCountFormatter.string(fromByteCount: entry.size, countStyle: .file))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 3)
    }
}

@MainActor
private final class AlpineFileModel: ObservableObject {
    @Published var data: Data?
    @Published var isLoading = false
    @Published var errorMessage: String?
    @Published var isSaving = false

    let host: SunshineRuntimeHost
    let entry: AlpineFileEntry

    init(host: SunshineRuntimeHost, entry: AlpineFileEntry) {
        self.host = host
        self.entry = entry
    }

    func load() {
        isLoading = true
        host.readGuestFile(path: entry.path) { [weak self] result in
            guard let self else { return }
            self.isLoading = false
            switch result {
            case let .success(data): self.data = data
            case let .failure(error): self.errorMessage = error.localizedDescription
            }
        }
    }

    func save(text: String, completion: @escaping (Bool) -> Void) {
        isSaving = true
        host.writeGuestFile(path: entry.path, data: Data(text.utf8)) { [weak self] result in
            self?.isSaving = false
            switch result {
            case .success:
                self?.data = Data(text.utf8)
                completion(true)
            case let .failure(error):
                self?.errorMessage = error.localizedDescription
                completion(false)
            }
        }
    }
}

private struct AlpineFileView: View {
    @StateObject private var model: AlpineFileModel

    init(host: SunshineRuntimeHost, entry: AlpineFileEntry) {
        _model = StateObject(wrappedValue: AlpineFileModel(host: host, entry: entry))
    }

    var body: some View {
        Group {
            if model.isLoading || model.data == nil && model.errorMessage == nil {
                ProgressView()
            } else if let data = model.data, let image = UIImage(data: data) {
                ScrollView([.horizontal, .vertical]) {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .padding()
                }
                .background(Color(uiColor: .systemBackground))
            } else if let data = model.data, let text = String(data: data, encoding: .utf8) {
                RunestoneEditorScreen(model: model, initialText: text)
            } else {
                ContentUnavailableView(
                    "Preview Unavailable",
                    systemImage: "doc.questionmark",
                    description: Text("This file is not UTF-8 text or a supported image.")
                )
            }
        }
        .navigationTitle(model.entry.name)
        .navigationBarTitleDisplayMode(.inline)
        .task { if model.data == nil { model.load() } }
        .alert("Unable to Open File", isPresented: Binding(
            get: { model.errorMessage != nil },
            set: { if !$0 { model.errorMessage = nil } }
        )) {
            Button("OK") { model.errorMessage = nil }
        } message: {
            Text(model.errorMessage ?? "Unknown error")
        }
    }
}

private struct RunestoneEditorScreen: View {
    @ObservedObject var model: AlpineFileModel
    @State private var text: String
    @State private var savedText: String
    @State private var wrapsLines = false

    init(model: AlpineFileModel, initialText: String) {
        self.model = model
        _text = State(initialValue: initialText)
        _savedText = State(initialValue: initialText)
    }

    var body: some View {
        RunestoneEditor(text: $text, wrapsLines: wrapsLines)
            .ignoresSafeArea(.keyboard, edges: .bottom)
            .toolbar {
                ToolbarItemGroup(placement: .primaryAction) {
                    Button { wrapsLines.toggle() } label: {
                        Image(systemName: wrapsLines ? "text.word.spacing" : "arrow.left.and.right.text.vertical")
                    }
                    .accessibilityLabel(wrapsLines ? "Disable line wrapping" : "Enable line wrapping")

                    Button {
                        model.save(text: text) { saved in
                            if saved { savedText = text }
                        }
                    } label: {
                        if model.isSaving {
                            ProgressView()
                        } else {
                            Image(systemName: "square.and.arrow.down")
                        }
                    }
                    .disabled(model.isSaving || text == savedText)
                    .accessibilityLabel("Save")
                }
            }
    }
}

private struct RunestoneEditor: UIViewRepresentable {
    @Binding var text: String
    let wrapsLines: Bool

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> TextView {
        let view = TextView()
        view.editorDelegate = context.coordinator
        view.text = text
        view.showLineNumbers = true
        view.isLineWrappingEnabled = wrapsLines
        view.autocorrectionType = .no
        view.autocapitalizationType = .none
        view.spellCheckingType = .no
        view.smartQuotesType = .no
        view.smartDashesType = .no
        view.backgroundColor = .systemBackground
        return view
    }

    func updateUIView(_ view: TextView, context: Context) {
        context.coordinator.parent = self
        view.isLineWrappingEnabled = wrapsLines
        if view.text != text && !context.coordinator.isApplyingEditorChange {
            view.text = text
        }
    }

    final class Coordinator: NSObject, TextViewDelegate {
        var parent: RunestoneEditor
        var isApplyingEditorChange = false

        init(_ parent: RunestoneEditor) {
            self.parent = parent
        }

        func textViewDidChange(_ textView: TextView) {
            isApplyingEditorChange = true
            parent.text = textView.text
            isApplyingEditorChange = false
        }
    }
}
