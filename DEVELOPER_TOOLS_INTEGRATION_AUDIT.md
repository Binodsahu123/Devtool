# Developer Tools Integration — Current Source Audit

## Executive summary

The supplied `developer-tools-main.zip` contains both an Android browser app and a separately built DevTools frontend asset tree. The Swift Browser module imports only the DevTools frontend assets and the DevTools-related backend behavior. It intentionally does not import Unix Shells' standalone browser application or tab/navigation owner.

## What is in `:developer-tools-engine`

Runtime Kotlin files:

- `CDPBridge.kt` — token-protected, loopback-only TCP-to-Android `LocalSocket` CDP relay.
- `DevToolsServer.kt` — serves the bundled frontend locally from Android assets.
- `DeveloperToolsEngine.kt` — starts the runtime, discovers WebView targets, binds the active Swift tab to a target, and opens CDP WebSocket sessions.
- `CdpWebSocketConnection.kt` — correlated CDP commands/responses and events using one real WebSocket session implementation.
- `DeveloperToolsPanel.kt` — Compose host for the actual imported Chromium DevTools frontend.

The module contains the upstream `devtools_frontend/` directory: **4,058 files**. SHA-256 comparison against the supplied Unix Shells archive found 4,058 matches, zero missing files, zero extras, and zero content mismatches.

The module does **not** contain Unix Shells' `MainActivity.kt`, `TabManager.kt`, `SettingsActivity.kt`, browser application manifest, browser navigation state, browser UI, or tab manager. Its non-asset runtime surface is five Kotlin files plus the library manifest, licensing/notice, asset-upgrade script, and module documentation.

## Swift-owned architecture and dependency direction

- `:app` consumes the existing `:browser-engine` and application services.
- `:browser-engine` contains the actual Swift `BrowserScreen` and depends on `:tab-engine` and `:developer-tools-engine`.
- `:tab-engine` remains the source of truth for the active tab and its live `WebView`; `BrowserScreen` gets the tab ID and WebView from it and passes them into `DeveloperToolsEngine`.
- `:extension-engine` depends on `:developer-tools-engine` for the real CDP session used by its `chrome.debugger` facade.
- `:developer-tools-engine` intentionally has no project dependency on `:browser-engine`, `:tab-engine`, or `:extension-engine`. That keeps it reusable and avoids Gradle dependency cycles. The host layer supplies the active WebView instead of the DevTools module making its own browser/tab system.

The existing `browser-engine` folder is **Swift Browser's own browser engine**, not Unix Shells' browser code. It must remain. Removing it would remove the host browser UI that displays the DevTools panel.

## Fixes applied in this pass

1. Replaced the main browser-menu item that showed `Developer Tools disabled`; both menu entry points now use one shared open/close action.
2. Threaded the active `TabEngine` tab ID and live WebView into the DevTools entry point.
3. Removed always-on `WebView.setWebContentsDebuggingEnabled(true)` from general WebView configuration. Debugging is now enabled on demand when the DevTools runtime starts, and the toggle runs on the UI dispatcher.
4. Made target resolution fail closed: it matches the live URL (normalizing host/scheme case, default ports, and the root slash) and uses the title only to disambiguate duplicate-URL targets. It no longer falls back to an arbitrary single page when the URL does not match.
5. Fixed the extension debugger's ID mapping. Swift's synthetic `target_tab_<uuid>` IDs are not native Chromium CDP target IDs; the adapter now resolves those through the supplied Swift WebView. Native target IDs are checked against the WebView instead of being ignored.
6. Hardened the DevTools frontend WebView to permit ordinary HTTP resources only from its exact local asset-server origin, not every loopback port.
7. Added URL-decoded asset-path traversal checks to `DevToolsServer` and cleanup of both bridge sockets if startup fails.
8. Fixed WebSocket session connection state so concurrent callers share one connection, a failed/closed connection is not reported as connected, and commands connect before using the socket.
9. Removed unused direct `androidx.core.ktx` and `androidx.webkit` dependencies from the DevTools library.
10. Removed stale bundled DevTools audit documents that described a different custom frontend (`assets/devtools/`, nonexistent protocol/domain files, and nonexistent transports). Replaced them with this current audit and corrected the root README.
11. Ported Unix Shells' `InspectorFrontendHost.enterInspectElementMode()` action into the Swift DevTools panel so Inspect Element does not depend on the upstream standalone browser menu.
12. Added functional bottom, right-side, and fullscreen docking in Swift's existing `BrowserScreen`; the dock toggle only changes panel layout and never creates a second website tab.
13. Clarified the extension capability matrix: the built-in browser DevTools panel is separate from the Chrome extension `devtools.*` API, which remains incomplete.

## Runtime flow

1. Swift `BrowserScreen` reads `activeTab.id` and obtains the current website WebView through `TabEngine`.
2. Opening Developer Tools enables WebView debugging on demand.
3. `CDPBridge` relays authenticated loopback HTTP/WebSocket requests to Android WebView's `webview_devtools_remote_<pid>` abstract socket.
4. `DevToolsServer` serves `devtools_app.html`, JavaScript, CSS, fonts, protocol files, and other bundled assets from the app package.
5. The DevTools WebView connects over the bridge to the target matching the active Swift WebView.
6. The browser's tabs, navigation, storage, and other browser services stay in Swift's existing engines.

## Security and compatibility boundaries

- Both TCP listeners bind to `127.0.0.1`; the random per-process token is required for CDP relay requests and is stripped before the Android WebView socket sees them.
- The frontend has no Google/Appspot runtime fallback and does not download frontend files at runtime.
- If URL/title metadata cannot distinguish duplicate-URL tabs, the resolver reports an error rather than silently inspecting another tab.
- The upstream DevTools frontend is much broader than the CDP feature set provided by every Android System WebView. Desktop-only Chromium commands, browser-level targets, service workers, and some panels may remain unavailable on a device.

### Additional fixes in this pass

- The legacy extension debugger integration test expected fabricated CDP domain responses from a target with no WebView. Replaced that production-path assumption with a transport interface and a test-only fake factory. The production default remains `ChromeDebuggerCdpTransport` over the real authenticated WebSocket; the fake exists under `src/test` only.
- Restricted `chrome.debugger.attach` to CDP version `1.3`, matching the supported WebView transport, and removed the unused protocol-check/accessor methods that had no callers.
- Failed WebSocket handshakes now cancel the underlying socket even if a failure callback has already marked it closed. Session close reasons flow through to the actual socket close frame.
- Local CDP target discovery bypasses system HTTP proxies with `Proxy.NO_PROXY`, preventing browser/VPN proxy configuration from hijacking loopback target discovery.

## Verification performed

- Upstream asset comparison: **PASS** — 4,058 files, 0 missing, 0 extra, 0 SHA-256 mismatches.
- Archive integrity: **PASS** — `unzip -t` reports no errors.
- Gradle settings: `:developer-tools-engine` included once; host and extension dependencies present.
- Source topology: the DevTools module has no dependency on another project module and does not include Unix Shells' browser Activity/tab manager.
- Menu strings/source: disabled-toast implementation removed; active `TabEngine` tab ID is passed into target selection.
- Stale documentation scan: obsolete `assets/devtools/` references and reports about nonexistent DevTools domain/transport classes removed.
- XML/TOML parsing and source-pattern checks were run. A Kotlin/Android compilation was not verified because this environment lacks the Android SDK and Gradle; do not interpret static source checks as compilation success.

## Not certified here

A full Android Gradle build and device runtime test could not be run in this environment: the supplied source archive has no Gradle wrapper, and no installed Gradle distribution or Android SDK is available here. Do not treat the static checks as a successful APK build. The remaining gate is to sync/build `:app` in Android Studio and validate the CDP connection on a real Android System WebView version.
