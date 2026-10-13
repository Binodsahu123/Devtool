package com.swift.browser.extensionengine

import android.webkit.WebView

object ChromeWebStoreScript {

    fun isWebStoreUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        return lower.contains("chromewebstore.google.com") ||
                lower.contains("chrome.google.com/webstore") ||
                lower.contains("microsoftedge.microsoft.com/addons")
    }

    fun injectWebStoreBridge(webView: WebView, url: String) {
        if (!isWebStoreUrl(url)) return

        val script = """
            (function() {
                if (window.__swift_webstore_injected) return;
                window.__swift_webstore_injected = true;

                // 1. Polyfill chrome.webstore and chrome.app
                window.chrome = window.chrome || {};
                window.chrome.webstore = window.chrome.webstore || {
                    install: function(url, onSuccess, onFailure) {
                        var idMatch = (url || location.href).match(/([a-p]{32})/i);
                        var extId = idMatch ? idMatch[1] : "";
                        var title = document.title.replace(" - Chrome Web Store", "").trim();
                        if (window.SwiftWebStoreBridge && extId) {
                            window.SwiftWebStoreBridge.promptInstall(extId, title, "");
                            if (onSuccess) onSuccess();
                        } else if (onFailure) {
                            onFailure("Bridge not found");
                        }
                    }
                };

                window.chrome.app = window.chrome.app || {
                    isInstalled: false,
                    InstallState: { DISABLED: "disabled", INSTALLED: "installed", NOT_INSTALLED: "not_installed" },
                    RunningState: { CANNOT_RUN: "cannot_run", READY_TO_RUN: "ready_to_run", RUNNING: "running" },
                    getIsInstalled: function() { return false; },
                    getInstallState: function(cb) { if (cb) cb("not_installed"); }
                };

                function getExtensionIdFromUrl() {
                    var match = location.href.match(/(?:detail\/|detail\/[^\/]+\/)?([a-p]{32})/i);
                    return match ? match[1].toLowerCase() : null;
                }

                function getExtensionName() {
                    var h1 = document.querySelector('h1');
                    if (h1 && h1.innerText && h1.innerText.trim().length > 0) {
                        return h1.innerText.trim();
                    }
                    var title = document.title || "";
                    title = title.replace(/\s*-\s*Chrome\s*Web\s*Store.*/i, "").trim();
                    title = title.replace(/\s*-\s*Chrome\s*वेब\s*स्टोर.*/i, "").trim();
                    return title || "Chrome Extension";
                }

                function getExtensionIcon() {
                    var img = document.querySelector('img[src*="googleusercontent.com"], img[src*="chromewebstore"]');
                    return img ? img.src : "";
                }

                // 2. Intercept Add to Chrome / Add to Desktop buttons and enhance with native trigger
                function attachListeners() {
                    var extId = getExtensionIdFromUrl();
                    var isInstalled = extId && window.SwiftWebStoreBridge && window.SwiftWebStoreBridge.isInstalled(extId);

                    var buttons = document.querySelectorAll('button, div[role="button"], a[role="button"]');
                    buttons.forEach(function(btn) {
                        var rawText = (btn.innerText || btn.textContent || btn.getAttribute('aria-label') || "").trim();
                        var text = rawText.toLowerCase();
                        var isAddBtn = text.includes('add to chrome') || 
                                       text.includes('add to desktop') ||
                                       text.includes('chrome में जोड़ें') || 
                                       text.includes('desktop में जोड़ें') ||
                                       text.includes('añadir a chrome') || 
                                       text.includes('añadir a escritorio') ||
                                       text.includes('ajouter à chrome') || 
                                       text.includes('ajouter à desktop') ||
                                       text.includes('zum desktop hinzufügen') ||
                                       text.includes('zu chrome hinzufügen') ||
                                       text.includes('instalar') ||
                                       text.includes('install') ||
                                       text.includes('get extension');
                        
                        if (isAddBtn) {
                            if (isInstalled) {
                                btn.innerText = "✓ Added to Chrome";
                                btn.style.backgroundColor = "#1E8E3E";
                                btn.style.color = "#FFFFFF";
                            }

                            if (!btn.__swift_hooked) {
                                btn.__swift_hooked = true;
                                btn.addEventListener('click', function(e) {
                                    var currentId = getExtensionIdFromUrl();
                                    if (currentId && window.SwiftWebStoreBridge) {
                                        e.preventDefault();
                                        e.stopPropagation();
                                        window.SwiftWebStoreBridge.promptInstall(currentId, getExtensionName(), getExtensionIcon());
                                    }
                                }, true);
                            }
                        }
                    });
                }

                // Run observer to catch dynamically rendered buttons
                var observer = new MutationObserver(function() {
                    attachListeners();
                    injectFloatingInstallBanner();
                });

                function injectFloatingInstallBanner() {
                    var extId = getExtensionIdFromUrl();
                    if (!extId) {
                        var existing = document.getElementById('__swift_floating_install_banner');
                        if (existing) existing.remove();
                        return;
                    }
                    var banner = document.getElementById('__swift_floating_install_banner');
                    var isInstalled = window.SwiftWebStoreBridge && window.SwiftWebStoreBridge.isInstalled(extId);
                    var name = getExtensionName();

                    if (!banner) {
                        banner = document.createElement('div');
                        banner.id = '__swift_floating_install_banner';
                        banner.style.position = 'fixed';
                        banner.style.bottom = '16px';
                        banner.style.left = '16px';
                        banner.style.right = '16px';
                        banner.style.zIndex = '9999999';
                        banner.style.backgroundColor = '#1A73E8';
                        banner.style.color = '#FFFFFF';
                        banner.style.padding = '12px 18px';
                        banner.style.borderRadius = '28px';
                        banner.style.boxShadow = '0 4px 16px rgba(0,0,0,0.35)';
                        banner.style.display = 'flex';
                        banner.style.alignItems = 'center';
                        banner.style.justifyContent = 'space-between';
                        banner.style.fontFamily = 'system-ui, -apple-system, Roboto, sans-serif';
                        banner.style.fontSize = '14px';
                        banner.style.fontWeight = '500';

                        var textSpan = document.createElement('span');
                        textSpan.id = '__swift_banner_text';
                        textSpan.style.overflow = 'hidden';
                        textSpan.style.textOverflow = 'ellipsis';
                        textSpan.style.whiteSpace = 'nowrap';
                        textSpan.style.marginRight = '12px';

                        var actionBtn = document.createElement('button');
                        actionBtn.id = '__swift_banner_btn';
                        actionBtn.style.border = 'none';
                        actionBtn.style.padding = '8px 16px';
                        actionBtn.style.borderRadius = '20px';
                        actionBtn.style.fontWeight = 'bold';
                        actionBtn.style.cursor = 'pointer';
                        actionBtn.style.fontSize = '13px';
                        actionBtn.style.flexShrink = '0';

                        banner.appendChild(textSpan);
                        banner.appendChild(actionBtn);
                        (document.body || document.documentElement).appendChild(banner);
                    }

                    var textSpan = document.getElementById('__swift_banner_text');
                    var actionBtn = document.getElementById('__swift_banner_btn');
                    if (textSpan && actionBtn) {
                        textSpan.innerText = isInstalled ? '✓ ' + name + ' (Installed)' : name;
                        actionBtn.style.backgroundColor = isInstalled ? '#1E8E3E' : '#FFFFFF';
                        actionBtn.style.color = isInstalled ? '#FFFFFF' : '#1A73E8';
                        actionBtn.innerText = isInstalled ? 'Installed' : 'Add to Browser';
                        actionBtn.onclick = function(e) {
                            e.preventDefault();
                            e.stopPropagation();
                            if (window.SwiftWebStoreBridge) {
                                window.SwiftWebStoreBridge.promptInstall(extId, name, getExtensionIcon());
                            }
                        };
                    }
                }

                if (document.body) {
                    observer.observe(document.body, { childList: true, subtree: true });
                    attachListeners();
                    injectFloatingInstallBanner();
                } else {
                    document.addEventListener('DOMContentLoaded', function() {
                        observer.observe(document.body, { childList: true, subtree: true });
                        attachListeners();
                        injectFloatingInstallBanner();
                    });
                }

                // Handle initial pass
                attachListeners();
                injectFloatingInstallBanner();
                window.addEventListener('popstate', function() {
                    setTimeout(function() {
                        attachListeners();
                        injectFloatingInstallBanner();
                    }, 500);
                });
            })();
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(script, null)
        }
    }
}
