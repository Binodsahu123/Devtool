package com.swift.browser.browserengine

import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.WebView

object SwiftCompatibilityEngine {
    private const val TAG = "SwiftCompatibilityEngine"

    fun injectCompatibilityLayer(view: WebView?, url: String?, context: Context, isDesktop: Boolean = false) {
        if (view == null || url == null || url.startsWith("swift://") || url.startsWith("about:") || url.startsWith("file://")) {
            return
        }

        val webViewVersion = try {
            val packageInfo = WebView.getCurrentWebViewPackage()
            packageInfo?.versionName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }

        Log.i(TAG, "Initializing Swift Compatibility Layer on Android SDK ${Build.VERSION.SDK_INT}, System WebView version: $webViewVersion")

        val desktopEmulationJs = if (isDesktop) """
            var desktopWidth = 1280;
            var desktopHeight = 800;
            try {
                Object.defineProperty(window.screen, 'width', { get: function() { return desktopWidth; }, configurable: true });
                Object.defineProperty(window.screen, 'availWidth', { get: function() { return desktopWidth; }, configurable: true });
                Object.defineProperty(window.screen, 'height', { get: function() { return desktopHeight; }, configurable: true });
                Object.defineProperty(window.screen, 'availHeight', { get: function() { return desktopHeight; }, configurable: true });
                Object.defineProperty(window, 'innerWidth', { get: function() { return desktopWidth; }, configurable: true });
                Object.defineProperty(window, 'innerHeight', { get: function() { return desktopHeight; }, configurable: true });
                Object.defineProperty(window, 'outerWidth', { get: function() { return desktopWidth; }, configurable: true });
                Object.defineProperty(window, 'outerHeight', { get: function() { return desktopHeight; }, configurable: true });
                Object.defineProperty(window, 'devicePixelRatio', { get: function() { return 1.0; }, configurable: true });
            } catch (e) {
                console.log("Swift: screen metrics redefine failed:", e);
            }

            try {
                var originalMatchMedia = window.matchMedia;
                window.matchMedia = function(query) {
                    if (query.indexOf('max-width') !== -1 || query.indexOf('max-device-width') !== -1) {
                        return {
                            matches: false,
                            media: query,
                            addListener: function() {},
                            removeListener: function() {},
                            addEventListener: function() {},
                            removeEventListener: function() {}
                        };
                    }
                    if (query.indexOf('min-width') !== -1 || query.indexOf('min-device-width') !== -1) {
                        return {
                            matches: true,
                            media: query,
                            addListener: function() {},
                            removeListener: function() {},
                            addEventListener: function() {},
                            removeEventListener: function() {}
                        };
                    }
                    return originalMatchMedia.call(window, query);
                };
            } catch (e) {
                console.log("Swift: matchMedia redefine failed:", e);
            }

            try {
                var dStyle = document.createElement('style');
                dStyle.setAttribute('id', 'swift-desktop-css');
                dStyle.innerHTML = 'html { min-width: 1200px !important; }';
                document.head.appendChild(dStyle);
            } catch(e) {}

            function fixViewport() {
                var meta = document.querySelector('meta[name="viewport"]');
                if (!meta) {
                    meta = document.createElement('meta');
                    meta.name = 'viewport';
                    document.head.appendChild(meta);
                }
                meta.content = 'width=1280';
            }
            if (document.head) {
                fixViewport();
            } else {
                document.addEventListener('DOMContentLoaded', fixViewport);
            }
        """ else """
            function fixMobileViewport() {
                var meta = document.querySelector('meta[name="viewport"]');
                if (meta) {
                    meta.content = 'width=device-width, initial-scale=1.0, minimum-scale=1.0, user-scalable=yes';
                }
            }
            if (document.head) {
                fixMobileViewport();
            } else {
                document.addEventListener('DOMContentLoaded', fixMobileViewport);
            }
        """

        val polyfallsJs = """
            (function() {
                if (typeof globalThis === 'object') return;
                try {
                    Object.defineProperty(Object.prototype, '__magic__', {
                        get: function() { return this; },
                        configurable: true
                    });
                    __magic__.globalThis = __magic__;
                    delete Object.prototype.__magic__;
                } catch (e) {
                    try { window.globalThis = window; } catch (err) {}
                }
            })();

            window.requestIdleCallback = window.requestIdleCallback || function(cb) {
                var start = Date.now();
                return setTimeout(function() {
                    cb({
                        didTimeout: false,
                        timeRemaining: function() {
                            return Math.max(0, 50 - (Date.now() - start));
                        }
                    });
                }, 1);
            };
            window.cancelIdleCallback = window.cancelIdleCallback || function(id) {
                clearTimeout(id);
            };

            ${WebSpeechRecognitionBridge.getPolyfillJs()}
            ${WebClipboardBridge.getPolyfillJs()}
            ${com.swift.browser.browserengine.screencapture.WebScreenCaptureBridge.getPolyfillJs()}
            ${com.swift.browser.browserengine.webrtc.WebRtcRuntimeManager.getPolyfillJs((view as? BrowserWebView)?.tabId ?: "unknown")}

            // Background Media Playback Polyfill
            (function() {
                if (window.__SWIFT_BG_MEDIA_POLYFILL__) return;
                window.__SWIFT_BG_MEDIA_POLYFILL__ = true;

                try {
                    Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
                    Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                } catch(e) {}

                var stopVisibilityEvt = function(e) {
                    var mediaList = document.querySelectorAll('video, audio');
                    var isPlayingAny = false;
                    for (var i = 0; i < mediaList.length; i++) {
                        if (!mediaList[i].paused || window.__swiftWasPlaying) {
                            isPlayingAny = true;
                            break;
                        }
                    }
                    if (isPlayingAny) {
                        e.stopImmediatePropagation();
                        if (e.preventDefault) e.preventDefault();
                    }
                };
                window.addEventListener('visibilitychange', stopVisibilityEvt, true);
                document.addEventListener('visibilitychange', stopVisibilityEvt, true);

                window.__swiftWasPlaying = false;
                window.__swiftUserPaused = false;
                var lastReportedPlaying = null;

                function extractPageTitle() {
                    var title = document.title || '';
                    title = title.replace(/^\(\d+\)\s*/, '').trim();
                    return title || 'Web Media';
                }

                function monitorMediaElements() {
                    var mediaList = document.querySelectorAll('video, audio');
                    var anyPlaying = false;

                    for (var i = 0; i < mediaList.length; i++) {
                        var media = mediaList[i];
                        if (!media.__swiftTracked) {
                            media.__swiftTracked = true;

                            media.addEventListener('play', function() {
                                window.__swiftWasPlaying = true;
                                window.__swiftUserPaused = false;
                                reportState(true);
                            }, true);

                            media.addEventListener('playing', function() {
                                window.__swiftWasPlaying = true;
                                window.__swiftUserPaused = false;
                                reportState(true);
                            }, true);

                            media.addEventListener('pause', function() {
                                if (window.__swiftWasPlaying && !window.__swiftUserPaused) {
                                    setTimeout(function() {
                                        if (media.paused && !media.ended) {
                                            media.play().catch(function(){});
                                        }
                                    }, 120);
                                } else {
                                    setTimeout(function() {
                                        var stillPlaying = false;
                                        var all = document.querySelectorAll('video, audio');
                                        for (var k = 0; k < all.length; k++) {
                                            if (!all[k].paused && !all[k].ended) {
                                                stillPlaying = true;
                                                break;
                                            }
                                        }
                                        if (!stillPlaying) {
                                            window.__swiftWasPlaying = false;
                                            reportState(false);
                                        }
                                    }, 200);
                                }
                            }, true);

                            media.addEventListener('ended', function() {
                                window.__swiftWasPlaying = false;
                                window.__swiftUserPaused = false;
                                reportState(false);
                            }, true);
                        }

                        if (!media.paused && !media.ended) {
                            anyPlaying = true;
                        } else if ((window.__swiftBgActive || window.__swiftWasPlaying) && !window.__swiftUserPaused && !media.ended) {
                            try { media.play().catch(function(){}); } catch(e){}
                        }
                    }

                    if (anyPlaying) {
                        window.__swiftWasPlaying = true;
                    }

                    if (!anyPlaying && (window.__swiftBgActive || window.__swiftWasPlaying) && !window.__swiftUserPaused) {
                        return;
                    }

                    if (lastReportedPlaying !== anyPlaying) {
                        reportState(anyPlaying);
                    }
                }

                function reportState(isPlaying) {
                    lastReportedPlaying = isPlaying;
                    var title = extractPageTitle();
                    var currentUrl = window.location.href;
                    if (window.AndroidNotificationBridge && window.AndroidNotificationBridge.onMediaStateChanged) {
                        window.AndroidNotificationBridge.onMediaStateChanged(isPlaying, title, currentUrl);
                    } else if (window.AndroidBridge && window.AndroidBridge.updateState) {
                        window.AndroidBridge.updateState(isPlaying, title, 0, 0, currentUrl);
                    }
                }

                setInterval(monitorMediaElements, 1000);
            })();

            window.__SWIFT_COMPAT_ACTIVE__ = true;
            window.__SWIFT_ENGINE_VERSION__ = "2.1.0";
        """

        val combinedJs = """
            (function() {
                if (!window.__SWIFT_COMPAT_ACTIVE__) {
                    $desktopEmulationJs
                    $polyfallsJs
                }
            })();
        """.trimIndent()

        view.evaluateJavascript(combinedJs, null)

        fun loadAssetString(vararg fileNames: String): String? {
            for (name in fileNames) {
                try {
                    return context.assets.open(name).bufferedReader().use { it.readText() }
                } catch (_: Exception) {
                    // Try next fallback
                }
            }
            return null
        }

        val desktopProbeJs = loadAssetString("swift_desktop_probe.js", "orion_desktop_probe.js")
        if (desktopProbeJs != null) {
            view.evaluateJavascript(desktopProbeJs, null)
            Log.d("SwiftCompatibilityEngine", "Successfully loaded and injected desktop probe!")
        }

        val siteLayoutProbeJs = loadAssetString("swift_site_layout_probe.js", "orion_site_layout_probe.js")
        if (siteLayoutProbeJs != null) {
            view.evaluateJavascript(siteLayoutProbeJs, null)
            Log.d("SwiftCompatibilityEngine", "Successfully loaded and injected layout probe!")
        }

        // WebMediaCompatibilityEngine: Inject probe and record media compatibility diagnostics
        WebMediaCompatibilityEngine.injectCapabilityProbe(view) { caps ->
            WebMediaCompatibilityEngine.logDiagnostics(context, url, caps)
        }

        Log.d("SwiftCompatibilityEngine", "Bypassed ui_interceptor.js to preserve high-performance, native WebRTC streams.")
    }
}
