import XCTest

final class SunshineUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testMessageSubmissionDoesNotTerminateApp() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()

        let privacyAgreement = app.buttons["Agree"]
        if privacyAgreement.waitForExistence(timeout: 5) {
            privacyAgreement.tap()
        }

        let composer = app.textViews.firstMatch
        XCTAssertTrue(composer.waitForExistence(timeout: 30))
        composer.tap()
        composer.typeText("iOS message submission regression test")

        let send = app.buttons["Send"]
        XCTAssertTrue(send.waitForExistence(timeout: 10))
        send.tap()

        for second in 1...30 {
            XCTAssertEqual(
                app.state,
                .runningForeground,
                "Sunshine left the foreground \(second) seconds after sending a message"
            )
            Thread.sleep(forTimeInterval: 1)
        }
        XCTAssertTrue(composer.waitForExistence(timeout: 10))
    }

    func testComposerStaysAtBottomAfterOrientationRoundTrip() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()

        let privacyAgreement = app.buttons["Agree"]
        if privacyAgreement.waitForExistence(timeout: 5) {
            privacyAgreement.tap()
        }

        let composer = app.textViews.firstMatch
        XCTAssertTrue(composer.waitForExistence(timeout: 30))
        composer.tap()
        composer.typeText("orientation-regression")

        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(waitForLandscape(app))
        XCUIDevice.shared.orientation = .portrait
        XCTAssertTrue(waitForPortrait(app))
        XCTAssertTrue(composer.waitForExistence(timeout: 10))
        XCTAssertLessThan(app.frame.maxY - composer.frame.maxY, 120)
    }

    func testOnboardingRuntimeAndIOSCapabilitySurface() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()

        let privacyAgreement = app.buttons["Agree"]
        if privacyAgreement.waitForExistence(timeout: 10) {
            privacyAgreement.tap()
        }
        XCTAssertTrue(app.buttons["Get started"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts["Welcome to Sunshine"].exists)
        app.buttons["Get started"].tap()

        XCTAssertTrue(app.staticTexts["Set up the built-in Alpine Linux environment. It stays inside Sunshine's private app storage."].waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["Initialize"].waitForExistence(timeout: 20))
        app.buttons["Initialize"].tap()
        let setupDetails = app.descendants(matching: .any)
            .matching(identifier: "Details")
            .firstMatch
        XCTAssertTrue(setupDetails.waitForExistence(timeout: 20))
        setupDetails.tap()
        XCTAssertTrue(app.staticTexts["Setup details"].waitForExistence(timeout: 10))
        app.buttons["Close"].tap()
        XCTAssertTrue(app.buttons["Continue"].waitForExistence(timeout: 300))
        XCTAssertTrue(app.staticTexts["Alpine is ready and will be used as the default local runtime."].exists)
        app.buttons["Continue"].tap()

        XCTAssertTrue(app.buttons["Skip"].waitForExistence(timeout: 30))
        app.buttons["Skip"].tap()
        XCTAssertTrue(app.staticTexts["What can I help with?"].waitForExistence(timeout: 30))

        let composer = app.textViews.firstMatch
        XCTAssertTrue(composer.waitForExistence(timeout: 10))
        composer.tap()
        composer.typeText("keyboard-e2e")
        XCTAssertTrue((composer.value as? String)?.contains("keyboard-e2e") == true)

        if UIDevice.current.userInterfaceIdiom == .pad {
            XCTAssertFalse(app.buttons["Menu"].exists)
            XCTAssertTrue(app.buttons["Settings"].waitForExistence(timeout: 10))
        } else {
            XCTAssertTrue(app.buttons["Menu"].exists)
            app.buttons["Menu"].tap()
            XCTAssertTrue(app.buttons["Settings"].waitForExistence(timeout: 10))
        }
        app.buttons["Settings"].tap()

        XCTAssertTrue(app.staticTexts["General Settings"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["Model Providers"].exists)
        XCTAssertTrue(app.staticTexts["Personalization"].exists)
        XCTAssertTrue(app.staticTexts["Web Tools"].exists)
        XCTAssertTrue(app.staticTexts["Reliability"].exists)
        XCTAssertTrue(app.staticTexts["Agent Skills"].exists)
        XCTAssertTrue(app.staticTexts["Extensions"].exists)
        app.swipeUp()
        XCTAssertTrue(app.staticTexts["MCP Servers"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Alpine"].exists)
        app.swipeUp()
        XCTAssertTrue(app.staticTexts["About"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts["Termux"].exists)
        XCTAssertFalse(app.staticTexts["Runtime defaults"].exists)
        XCTAssertFalse(app.staticTexts["Agent Mode"].exists)
        XCTAssertFalse(app.staticTexts["Scheduled Tasks"].exists)

        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(app.staticTexts["Settings"].waitForExistence(timeout: 10))
        XCUIDevice.shared.orientation = .portrait
        XCTAssertTrue(app.staticTexts["Settings"].waitForExistence(timeout: 10))

        for _ in 0..<3 where !app.staticTexts["General Settings"].isHittable {
            app.swipeDown()
        }
        XCTAssertTrue(app.staticTexts["General Settings"].isHittable)

        let generalSettings = app.buttons
            .matching(NSPredicate(format: "label BEGINSWITH %@", "General Settings,"))
            .firstMatch
        XCTAssertTrue(generalSettings.waitForExistence(timeout: 10))
        generalSettings.tap()
        XCTAssertTrue(app.staticTexts["Language"].waitForExistence(timeout: 10))
        app.buttons["System, Theme"].tap()
        let darkTheme = app.descendants(matching: .any)
            .matching(identifier: "Dark")
            .firstMatch
        XCTAssertTrue(darkTheme.waitForExistence(timeout: 10))
        darkTheme.tap()
        app.buttons["English, Language"].tap()
        let simplifiedChinese = app.descendants(matching: .any)
            .matching(identifier: "简体中文")
            .firstMatch
        XCTAssertTrue(simplifiedChinese.waitForExistence(timeout: 10))
        simplifiedChinese.tap()

        app.terminate()
        let localizedApp = XCUIApplication()
        localizedApp.launch()

        XCTAssertTrue(localizedApp.staticTexts["想让我帮你做什么？"].waitForExistence(timeout: 30))
        let localizedDarkHome = XCTAttachment(screenshot: localizedApp.screenshot())
        localizedDarkHome.name = "Chinese dark chat home"
        localizedDarkHome.lifetime = .keepAlways
        add(localizedDarkHome)
    }

    private func waitForLandscape(_ app: XCUIApplication) -> Bool {
        let expectation = XCTNSPredicateExpectation(
            predicate: NSPredicate { _, _ in app.frame.width > app.frame.height },
            object: nil
        )
        return XCTWaiter.wait(for: [expectation], timeout: 10) == .completed
    }

    private func waitForPortrait(_ app: XCUIApplication) -> Bool {
        let expectation = XCTNSPredicateExpectation(
            predicate: NSPredicate { _, _ in app.frame.height > app.frame.width },
            object: nil
        )
        return XCTWaiter.wait(for: [expectation], timeout: 10) == .completed
    }
}
