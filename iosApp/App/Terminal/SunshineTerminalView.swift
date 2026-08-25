import Combine
import UIKit

final class SunshineTerminalView: UIView {
    var onInput: ((Data) -> Void)?
    var onResize: ((Int, Int) -> Void)?
    var onTitleChanged: ((String) -> Void)?

    private let emulator = TerminalEmulator()
    private let keyInputView = TerminalKeyInputView(frame: .zero)
    private lazy var coordinator = TerminalScrollCoordinator(
        emulator: emulator,
        onResize: { [weak self] columns, rows in
            guard let self else { return }
            self.emulator.resize(cols: columns, rows: rows)
            self.onResize?(columns, rows)
        },
        onPaste: { [weak self] data in self?.sendPaste(data) }
    )
    private lazy var terminalView = TerminalScrollContainerView(
        emulator: emulator,
        coordinator: coordinator
    )
    private var titleSubscription: AnyCancellable?
    private var darkTheme = true

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = TerminalPalette.defaultBackground(darkTheme: true)

        terminalView.onTap = { [weak self] in self?.focus() }
        terminalView.onDoubleTap = { [weak self] in self?.send(Data([0x09])) }
        coordinator.containerView = terminalView
        terminalView.translatesAutoresizingMaskIntoConstraints = false
        addSubview(terminalView)
        NSLayoutConstraint.activate([
            terminalView.leadingAnchor.constraint(equalTo: leadingAnchor),
            terminalView.trailingAnchor.constraint(equalTo: trailingAnchor),
            terminalView.topAnchor.constraint(equalTo: topAnchor),
            terminalView.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])

        keyInputView.onInput = { [weak self] data in
            guard let self else { return }
            self.send(data == Data([0x0a]) ? Data([0x0d]) : data)
        }
        keyInputView.inputAssistantItem.leadingBarButtonGroups = []
        keyInputView.inputAssistantItem.trailingBarButtonGroups = []
        keyInputView.frame = CGRect(x: 0, y: 0, width: 1, height: 1)
        keyInputView.alpha = 0.01
        addSubview(keyInputView)

        emulator.onResponse = { [weak self] data in self?.send(data) }
        titleSubscription = emulator.$title
            .removeDuplicates()
            .receive(on: RunLoop.main)
            .sink { [weak self] title in self?.onTitleChanged?(title) }
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func feed(_ data: Data) {
        if Thread.isMainThread {
            emulator.feed(data)
            keyInputView.applicationCursorKeys = emulator.applicationCursorKeys
        } else {
            DispatchQueue.main.async { [weak self] in self?.feed(data) }
        }
    }

    func focus() {
        guard !keyInputView.isFirstResponder else { return }
        keyInputView.becomeFirstResponder()
    }

    func setDarkTheme(_ darkTheme: Bool) {
        guard self.darkTheme != darkTheme else { return }
        self.darkTheme = darkTheme
        backgroundColor = TerminalPalette.defaultBackground(darkTheme: darkTheme)
        overrideUserInterfaceStyle = darkTheme ? .dark : .light
        terminalView.setDarkTheme(darkTheme)
        keyInputView.keyboardAppearance = darkTheme ? .dark : .light
        keyInputView.overrideUserInterfaceStyle = darkTheme ? .dark : .light
        if keyInputView.isFirstResponder {
            keyInputView.reloadInputViews()
        }
    }

    func sendKey(_ key: String, control: Bool, alt: Bool) {
        let modifier = control && alt ? 7 : control ? 5 : alt ? 3 : 0
        func csi(_ prefix: String, _ suffix: String) -> String {
            modifier == 0 ? "\u{1b}[\(prefix)\(suffix)" : "\u{1b}[\(prefix);\(modifier)\(suffix)"
        }

        let sequence: String
        switch key {
        case "Escape": sequence = "\u{1b}"
        case "Tab": sequence = "\t"
        case "Backspace": sequence = (alt ? "\u{1b}" : "") + (control ? "\u{8}" : "\u{7f}")
        case "Enter": sequence = (alt ? "\u{1b}" : "") + "\r"
        case "Insert": sequence = csi("2", "~")
        case "Delete": sequence = csi("3", "~")
        case "PageUp": sequence = csi("5", "~")
        case "PageDown": sequence = csi("6", "~")
        case "Home":
            sequence = modifier == 0 ? (emulator.applicationCursorKeys ? "\u{1b}OH" : "\u{1b}[H") : csi("1", "H")
        case "End":
            sequence = modifier == 0 ? (emulator.applicationCursorKeys ? "\u{1b}OF" : "\u{1b}[F") : csi("1", "F")
        case "Up", "Down", "Right", "Left":
            let suffix = ["Up": "A", "Down": "B", "Right": "C", "Left": "D"][key]!
            sequence = modifier == 0
                ? "\u{1b}\(emulator.applicationCursorKeys ? "O" : "[")\(suffix)"
                : csi("1", suffix)
        default: return
        }
        send(Data(sequence.utf8))
    }

    func cleanup() {
        keyInputView.resignFirstResponder()
        keyInputView.onInput = nil
        emulator.onResponse = nil
        titleSubscription = nil
        onInput = nil
        onResize = nil
        onTitleChanged = nil
    }

    private func sendPaste(_ data: Data) {
        guard emulator.bracketedPaste else {
            send(data)
            return
        }
        var wrapped = Data("\u{1b}[200~".utf8)
        wrapped.append(data)
        wrapped.append(Data("\u{1b}[201~".utf8))
        send(wrapped)
    }

    private func send(_ data: Data) {
        guard !data.isEmpty else { return }
        onInput?(data)
    }
}
