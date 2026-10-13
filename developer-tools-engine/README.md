# Swift Browser Developer Tools Engine

This Android library imports the complete built Chromium DevTools frontend from the provided Unix Shells `developer-tools-main.zip` and connects it to Swift Browser's existing active `BrowserWebView` using Android WebView's Chrome DevTools Protocol (CDP) abstract socket.

## Imported content

- Complete upstream `devtools_frontend/` asset tree, unchanged (4,058 files).
- Unix Shells CDP bridge and local asset-server design, adapted only for Swift package/module integration, correct active-WebView targeting, loopback-only binding, dynamic ports, authenticated local discovery/WebSocket requests and robust connection cleanup.
- Upstream `LICENSE` and third-party `NOTICE`.

See `UPSTREAM_IMPORT_MAP.md` for exact source mappings and the intentionally excluded standalone browser files.

## Runtime path

1. `BrowserScreen` obtains the active tab ID and its live `WebView` from Swift's existing `TabEngine` and passes both to `DeveloperToolsEngine`; the DevTools module does not import or create its own browser/tab engine.
2. `DeveloperToolsEngine` starts a loopback CDP bridge to Android WebView's `webview_devtools_remote_<pid>` abstract socket.
3. It queries `/json` and resolves the supplied Swift WebView against its live URL. If duplicate-URL tabs cannot be distinguished safely, it fails closed rather than debugging another tab. Native CDP target IDs are validated; Swift extension IDs such as `target_tab_<uuid>` are treated as Swift-owned IDs and resolved through the supplied tab's actual WebView.
4. `DevToolsServer` serves all frontend assets from this module's packaged assets. There is no hosted Google/Appspot fallback and no runtime frontend download.
5. `DeveloperToolsPanel` renders the actual imported Chromium frontend in a dedicated DevTools WebView, offers dock/fullscreen controls and the upstream Inspect Element picker, while Swift retains ownership of browsing and tabs.
6. The existing extension `chrome.debugger` transport delegates to the module's real CDP WebSocket transport instead of the previous removed-transport stub. The transport interface has a test-only injectable factory; production defaults always use the real socket transport.

## Rebuild the frontend (optional)

The checked-in upstream bundle is already present. The upstream Unix Shells `MainActivity`, `TabManager`, `SettingsActivity`, browser navigation/UI and app-level resources are intentionally excluded. Only run `scripts/copy-devtools-frontend.sh` when intentionally upgrading the frontend from a local Chromium build. Set `CHROMIUM_SRC` to your Chromium checkout; the script does not download source files and is not needed at runtime.

The Swift `browser-engine` and `tab-engine` are not duplicates of Unix Shells: they are the existing host browser architecture and must remain. `browser-engine` depends on this module because its `BrowserScreen` hosts the panel; this module intentionally has no project dependency on `browser-engine` or `tab-engine`, avoiding a Gradle dependency cycle.

## Build

Open the Swift Browser project in Android Studio and build `:app`. The project requires its normal Android SDK, Android Gradle Plugin-compatible Gradle distribution, and dependency cache.

## License

See `LICENSE_UNIXSHELLS` and `NOTICE`. The frontend assets retain their upstream contents and licensing notices.

The Swift host exposes bottom, right-side, and fullscreen layouts; these are presentation modes only and do not introduce a browser/tab manager in this module.
