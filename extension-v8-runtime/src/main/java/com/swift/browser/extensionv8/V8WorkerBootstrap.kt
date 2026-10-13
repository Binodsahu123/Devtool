package com.swift.browser.extensionv8

import org.json.JSONObject

/**
 * Canonical worker-global bootstrap used exclusively by the Native V8 MV3 runtime.
 *
 * The JVM WebView worker implementation is retained only under test sources.
 */
object V8WorkerBootstrap {
    fun buildDefaultBootstrapScript(extensionId: String, manifestJson: String = "{}"): String {
            val manifestLiteral = JSONObject.quote(if (manifestJson.isBlank()) "{}" else manifestJson)
            return """
                (function(global) {
                    global.self = global;
                    global.globalThis = global;

                    // 1. Base64 encoding/decoding utilities (Web Worker standards)
                    var _b64chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                    global.btoa = global.btoa || function(input) {
                        var str = String(input);
                        var output = '';
                        for (var block = 0, charCode, i = 0, map = _b64chars;
                             str.charAt(i | 0) || (map = '=', i % 1);
                             output += map.charAt(63 & block >> 8 - i % 1 * 8)) {
                            charCode = str.charCodeAt(i += 3/4);
                            if (charCode > 0xFF) {
                                throw new Error("'btoa' failed: The string to be encoded contains characters outside of the Latin1 range.");
                            }
                            block = block << 8 | charCode;
                        }
                        return output;
                    };
                    global.atob = global.atob || function(input) {
                        var str = String(input).replace(/[=]+$/, '');
                        if (str.length % 4 === 1) {
                            throw new Error("'atob' failed: The string to be decoded is not correctly encoded.");
                        }
                        var output = '';
                        for (var bc = 0, bs = 0, buffer, i = 0;
                             buffer = str.charAt(i++);
                             ~buffer && (bs = bc % 4 ? bs * 64 + buffer : buffer,
                               bc++ % 4) ? output += String.fromCharCode(255 & bs >> (-2 * bc & 6)) : 0
                            ) {
                            buffer = _b64chars.indexOf(buffer);
                        }
                        return output;
                    };

                    // 2. Console implementation
                    global.console = {
                        log: function() {
                            var args = Array.prototype.slice.call(arguments);
                            __orionNativeConsole('log', args.map(function(a) { return typeof a === 'object' ? JSON.stringify(a) : String(a); }).join(' '));
                        },
                        info: function() {
                            var args = Array.prototype.slice.call(arguments);
                            __orionNativeConsole('info', args.map(function(a) { return typeof a === 'object' ? JSON.stringify(a) : String(a); }).join(' '));
                        },
                        warn: function() {
                            var args = Array.prototype.slice.call(arguments);
                            __orionNativeConsole('warn', args.map(function(a) { return typeof a === 'object' ? JSON.stringify(a) : String(a); }).join(' '));
                        },
                        error: function() {
                            var args = Array.prototype.slice.call(arguments);
                            __orionNativeConsole('error', args.map(function(a) { return typeof a === 'object' ? JSON.stringify(a) : String(a); }).join(' '));
                        },
                        debug: function() {
                            var args = Array.prototype.slice.call(arguments);
                            __orionNativeConsole('debug', args.map(function(a) { return typeof a === 'object' ? JSON.stringify(a) : String(a); }).join(' '));
                        }
                    };

                    // 3. Timers
                    var __nextTimerId = 1;
                    var __timerMap = {};

                    global.setTimeout = function(fn, delay) {
                        var extraArgs = Array.prototype.slice.call(arguments, 2);
                        var id = __nextTimerId++;
                        __timerMap[id] = { fn: fn, args: extraArgs, isInterval: false };
                        __orionNativeSetTimer(false, Math.max(0, delay || 0), id);
                        return id;
                    };

                    global.clearTimeout = function(id) {
                        delete __timerMap[id];
                        __orionNativeClearTimer(id);
                    };

                    global.setInterval = function(fn, period) {
                        var extraArgs = Array.prototype.slice.call(arguments, 2);
                        var id = __nextTimerId++;
                        __timerMap[id] = { fn: fn, args: extraArgs, isInterval: true };
                        __orionNativeSetTimer(true, Math.max(0, period || 0), id);
                        return id;
                    };

                    global.clearInterval = function(id) {
                        global.clearTimeout(id);
                    };

                    global.__orionFireTimer = function(id) {
                        var item = __timerMap[id];
                        if (!item) return;
                        if (!item.isInterval) {
                            delete __timerMap[id];
                        }
                        try {
                            if (typeof item.fn === 'function') {
                                item.fn.apply(null, item.args);
                            } else if (typeof item.fn === 'string') {
                                (new Function(item.fn))();
                            }
                        } catch (e) {
                            console.error('Timer execution error: ' + (e.stack || e.message || e));
                        }
                    };

                    // 4. Microtasks
                    // Use V8's native Promise microtask queue. The previous JS-side array was
                    // never guaranteed to drain at every checkpoint and could reorder Promise jobs.
                    global.queueMicrotask = function(fn) {
                        if (typeof fn !== 'function') throw new TypeError('queueMicrotask callback must be a function');
                        Promise.resolve().then(fn);
                    };

                    // Kept as a compatibility no-op for older Orion bridge callers. Native V8
                    // checkpoints are driven by the C++ context after each worker turn/event.
                    global.__orionDrainMicrotasks = function() {};

                    // 5. Host bridge async request/response
                    var __reqCounter = 1;
                    var __pendingCalls = {};

                    function __callHost(apiName, argsArray, callback) {
                        var reqId = 'v8_req_' + (__reqCounter++) + '_' + Date.now();
                        var isPromise = false;
                        var resolveFn = null;
                        var rejectFn = null;

                        if (typeof callback === 'function') {
                            __pendingCalls[reqId] = { callback: callback, isPromise: false };
                        } else {
                            isPromise = true;
                            var p = new Promise(function(resolve, reject) {
                                resolveFn = resolve;
                                rejectFn = reject;
                            });
                            __pendingCalls[reqId] = { resolve: resolveFn, reject: rejectFn, isPromise: true };
                        }

                        var normalizedArgs = Array.isArray(argsArray) ? argsArray : (argsArray !== undefined && argsArray !== null ? [argsArray] : []);
                        var payloadStr = JSON.stringify({ args: normalizedArgs });

                        try {
                            __orionNativeHostBridge(apiName, reqId, payloadStr);
                        } catch (e) {
                            delete __pendingCalls[reqId];
                            if (isPromise) {
                                rejectFn(e);
                            } else if (typeof callback === 'function') {
                                if (global.chrome && global.chrome.runtime) {
                                    global.chrome.runtime.lastError = { message: e.message || String(e) };
                                }
                                callback(undefined);
                            }
                        }

                        if (isPromise) {
                            return p;
                        }
                    }

                    global.__orionResolveHostCall = function(reqId, resultJson, errorMsg) {
                        var pending = __pendingCalls[reqId];
                        if (!pending) return;
                        delete __pendingCalls[reqId];

                        var parsed = null;
                        if (resultJson) {
                            try { parsed = JSON.parse(resultJson); } catch (e) { parsed = resultJson; }
                        }

                        if (errorMsg) {
                            if (global.chrome && global.chrome.runtime) {
                                global.chrome.runtime.lastError = { message: errorMsg };
                            }
                            if (pending.isPromise) {
                                pending.reject(new Error(errorMsg));
                            } else {
                                pending.callback(undefined);
                            }
                        } else {
                            if (global.chrome && global.chrome.runtime) {
                                global.chrome.runtime.lastError = null;
                            }
                            if (pending.isPromise) {
                                pending.resolve(parsed);
                            } else {
                                pending.callback(parsed);
                            }
                        }
                    };

                    // 6. ExtendableEvent and waitUntil implementation
                    function ExtendableEvent(type) {
                        this.type = type;
                        var waitId = 'wait_' + (++__reqCounter) + '_' + Date.now();
                        this.waitUntil = function(promise) {
                            if (promise && typeof promise.then === 'function') {
                                __callHost('runtime.keepalive', ['startWaitUntil', waitId, 'waitUntil_' + type]);
                                promise.then(function() {
                                    __callHost('runtime.keepalive', ['endWaitUntil', waitId]);
                                }, function(err) {
                                    __callHost('runtime.keepalive', ['endWaitUntil', waitId]);
                                });
                            }
                        };
                    }
                    global.ExtendableEvent = ExtendableEvent;

                    var __workerListeners = {};
                    global.addEventListener = function(type, fn) {
                        if (typeof fn !== 'function') return;
                        __workerListeners[type] = __workerListeners[type] || [];
                        if (__workerListeners[type].indexOf(fn) === -1) {
                            __workerListeners[type].push(fn);
                        }
                    };
                    global.removeEventListener = function(type, fn) {
                        var list = __workerListeners[type];
                        if (!list) return;
                        var idx = list.indexOf(fn);
                        if (idx !== -1) list.splice(idx, 1);
                    };

                    // 7. Chrome Event implementation
                    var __eventObjects = {};
                    function __createEvent(eventName) {
                        if (eventName && __eventObjects[eventName]) return __eventObjects[eventName];
                        var listeners = [];
                        var registered = false;
                        var registerWithHost = function() {
                            if (!registered && eventName) {
                                registered = true;
                                try { __callHost('runtime.registerEventListener', [eventName]); }
                                catch (e) { registered = false; console.error('Failed to register event ' + eventName + ': ' + e); }
                            }
                        };
                        var unregisterFromHost = function() {
                            if (registered && eventName) {
                                registered = false;
                                try { __callHost('runtime.unregisterEventListener', [eventName]); }
                                catch (e) { console.error('Failed to unregister event ' + eventName + ': ' + e); }
                            }
                        };
                        var eventObject = {
                            addListener: function(fn) {
                                if (typeof fn === 'function' && listeners.indexOf(fn) === -1) {
                                    listeners.push(fn);
                                    registerWithHost();
                                }
                            },
                            removeListener: function(fn) {
                                var idx = listeners.indexOf(fn);
                                if (idx !== -1) listeners.splice(idx, 1);
                                if (listeners.length === 0) unregisterFromHost();
                            },
                            hasListener: function(fn) { return listeners.indexOf(fn) !== -1; },
                            hasListeners: function() { return listeners.length > 0; },
                            _dispatch: function() {
                                var args = Array.prototype.slice.call(arguments);
                                var asyncExpected = false;
                                var copy = listeners.slice();
                                for (var i = 0; i < copy.length; i++) {
                                    try {
                                        var res = copy[i].apply(null, args);
                                        if (res === true) {
                                            asyncExpected = true;
                                        } else if (res && typeof res.then === 'function') {
                                            asyncExpected = true;
                                            let eventKeepId = 'event_' + (++__reqCounter) + '_' + Date.now();
                                            __callHost('runtime.keepalive', ['startKeepalive', eventKeepId, 'EVENT_PROMISE:' + (eventName || 'unknown')]);
                                            Promise.resolve(res).then(function() {
                                                __callHost('runtime.keepalive', ['endKeepalive', eventKeepId]);
                                            }, function() {
                                                __callHost('runtime.keepalive', ['endKeepalive', eventKeepId]);
                                            });
                                        }
                                    } catch (e) {
                                        console.error('Event listener error: ' + (e.stack || e.message || e));
                                    }
                                }
                                return asyncExpected;
                            }
                        };
                        if (eventName) __eventObjects[eventName] = eventObject;
                        return eventObject;
                    }

                    // 8. Port implementation
                    var __activePorts = {};
                    function Port(channelId, name, sender) {
                        this.channelId = channelId;
                        this.name = name || '';
                        this.sender = sender || { id: '${extensionId}' };
                        this.onMessage = __createEvent();
                        this.onDisconnect = __createEvent();
                        var self = this;
                        this.postMessage = function(msg) {
                            __callHost('runtime.portPostMessage', [channelId, msg]);
                        };
                        this.disconnect = function() {
                            delete __activePorts[channelId];
                            __callHost('runtime.portDisconnect', [channelId]);
                        };
                    }

                    // 9. Chrome / Browser API surface
                    var extId = '${extensionId}';
                    var __manifestJson = ${manifestLiteral};
                    global.chrome = {
                        runtime: {
                            id: extId,
                            lastError: null,
                            getURL: function(path) {
                                return 'chrome-extension://' + extId + '/' + (path ? String(path).replace(/^\/+/, '') : '');
                            },
                            getManifest: function() {
                                try { return JSON.parse(__manifestJson); } catch (e) { return {}; }
                            },
                            getVersion: function() {
                                try { var m = JSON.parse(__manifestJson); return m.version || ''; } catch (e) { return ''; }
                            },
                            getPlatformInfo: function(cb) { return __callHost('runtime.getPlatformInfo', [], cb); },
                            openOptionsPage: function(cb) { return __callHost('runtime.openOptionsPage', [], cb); },
                            reload: function() { return __callHost('runtime.reload', []); },

                            sendMessage: function(arg1, arg2, arg3, arg4) {
                                var targetId = extId;
                                var message = arg1;
                                var options = {};
                                var cb = null;
                                if (typeof arg1 === 'string' && arguments.length > 1 && typeof arg2 !== 'function') {
                                    targetId = arg1;
                                    message = arg2;
                                    if (typeof arg3 === 'function') {
                                        cb = arg3;
                                    } else if (typeof arg3 === 'object' && arg3 !== null) {
                                        options = arg3;
                                        if (typeof arg4 === 'function') cb = arg4;
                                    }
                                } else {
                                    if (typeof arg2 === 'function') {
                                        cb = arg2;
                                    } else if (typeof arg2 === 'object' && arg2 !== null) {
                                        options = arg2;
                                        if (typeof arg3 === 'function') cb = arg3;
                                    }
                                }
                                return __callHost('runtime.sendMessage', [targetId, message, options], cb);
                            },
                            connect: function(arg1, arg2) {
                                var targetId = extId;
                                var name = '';
                                if (typeof arg1 === 'string') {
                                    targetId = arg1;
                                    if (arg2 && arg2.name) name = arg2.name;
                                } else if (arg1 && typeof arg1 === 'object') {
                                    if (arg1.name) name = arg1.name;
                                }
                                var channelId = 'channel_' + (++__reqCounter) + '_' + Date.now();
                                var port = new Port(channelId, name, { id: extId });
                                __activePorts[channelId] = port;
                                __callHost('runtime.portConnect', [targetId, channelId, name]);
                                return port;
                            },
                            onMessage: __createEvent('runtime.onMessage'),
                            onConnect: __createEvent('runtime.onConnect'),
                            onInstalled: __createEvent('runtime.onInstalled'),
                            onStartup: __createEvent('runtime.onStartup'),
                            onSuspend: __createEvent('runtime.onSuspend'),
                            onSuspendCanceled: __createEvent('runtime.onSuspendCanceled'),
                            onMessageExternal: __createEvent('runtime.onMessageExternal'),
                            onConnectExternal: __createEvent('runtime.onConnectExternal'),
                            onUpdateAvailable: __createEvent('runtime.onUpdateAvailable')
                        },
                        storage: {
                            local: {
                                get: function(keys, cb) { return __callHost('storage.local.get', ['local', keys], cb); },
                                set: function(items, cb) { return __callHost('storage.local.set', ['local', items], cb); },
                                remove: function(keys, cb) { return __callHost('storage.local.remove', ['local', keys], cb); },
                                clear: function(cb) { return __callHost('storage.local.clear', ['local'], cb); },
                                getBytesInUse: function(keys, cb) { return __callHost('storage.local.getBytesInUse', ['local', keys], cb); },
                                getKeys: function(cb) { return __callHost('storage.local.getKeys', ['local'], cb); },
                                setAccessLevel: function(details, cb) { return __callHost('storage.local.setAccessLevel', ['local', details && details.accessLevel ? details.accessLevel : 'TRUSTED_AND_UNTRUSTED_CONTEXTS'], cb); },
                                getAccessLevel: function(cb) { return __callHost('storage.local.getAccessLevel', ['local'], cb); }
                            },
                            sync: {
                                get: function(keys, cb) { return __callHost('storage.sync.get', ['sync', keys], cb); },
                                set: function(items, cb) { return __callHost('storage.sync.set', ['sync', items], cb); },
                                remove: function(keys, cb) { return __callHost('storage.sync.remove', ['sync', keys], cb); },
                                clear: function(cb) { return __callHost('storage.sync.clear', ['sync'], cb); },
                                getBytesInUse: function(keys, cb) { return __callHost('storage.sync.getBytesInUse', ['sync', keys], cb); },
                                getKeys: function(cb) { return __callHost('storage.sync.getKeys', ['sync'], cb); },
                                setAccessLevel: function(details, cb) { return __callHost('storage.sync.setAccessLevel', ['sync', details && details.accessLevel ? details.accessLevel : 'TRUSTED_AND_UNTRUSTED_CONTEXTS'], cb); },
                                getAccessLevel: function(cb) { return __callHost('storage.sync.getAccessLevel', ['sync'], cb); }
                            },
                            session: {
                                get: function(keys, cb) { return __callHost('storage.session.get', ['session', keys], cb); },
                                set: function(items, cb) { return __callHost('storage.session.set', ['session', items], cb); },
                                remove: function(keys, cb) { return __callHost('storage.session.remove', ['session', keys], cb); },
                                clear: function(cb) { return __callHost('storage.session.clear', ['session'], cb); },
                                getBytesInUse: function(keys, cb) { return __callHost('storage.session.getBytesInUse', ['session', keys], cb); },
                                getKeys: function(cb) { return __callHost('storage.session.getKeys', ['session'], cb); },
                                setAccessLevel: function(details, cb) { return __callHost('storage.session.setAccessLevel', ['session', details && details.accessLevel ? details.accessLevel : 'TRUSTED_AND_UNTRUSTED_CONTEXTS'], cb); },
                                getAccessLevel: function(cb) { return __callHost('storage.session.getAccessLevel', ['session'], cb); }
                            },
                            onChanged: __createEvent('storage.onChanged')
                        },
                        tabs: {
                            query: function(queryInfo, cb) { return __callHost('tabs.query', [queryInfo || {}], cb); },
                            get: function(tabId, cb) { return __callHost('tabs.get', [tabId], cb); },
                            sendMessage: function(tabId, message, optionsOrCallback, responseCallback) {
                                var opts = {};
                                var cb = null;
                                if (typeof optionsOrCallback === 'function') {
                                    cb = optionsOrCallback;
                                } else if (typeof optionsOrCallback === 'object' && optionsOrCallback !== null) {
                                    opts = optionsOrCallback;
                                    if (typeof responseCallback === 'function') cb = responseCallback;
                                }
                                return __callHost('tabs.sendMessage', [tabId, message, opts], cb);
                            },
                            connect: function(tabId, connectInfo) {
                                var name = (connectInfo && connectInfo.name) ? connectInfo.name : '';
                                var channelId = 'tab_channel_' + (++__reqCounter) + '_' + Date.now();
                                var port = new Port(channelId, name, { id: extId, tabId: tabId });
                                __activePorts[channelId] = port;
                                __callHost('tabs.connect', [tabId, channelId, name, (connectInfo && connectInfo.frameId !== undefined) ? connectInfo.frameId : -1]);
                                return port;
                            },
                            create: function(createProperties, cb) { return __callHost('tabs.create', [createProperties || {}], cb); },
                            update: function(tabId, updateProperties, cb) { return __callHost('tabs.update', [tabId, updateProperties || {}], cb); },
                            remove: function(tabIds, cb) { return __callHost('tabs.remove', [tabIds], cb); },
                            onUpdated: __createEvent('tabs.onUpdated'),
                            onCreated: __createEvent('tabs.onCreated'),
                            onRemoved: __createEvent('tabs.onRemoved'),
                            onActivated: __createEvent('tabs.onActivated'),
                            onAttached: __createEvent('tabs.onAttached'),
                            onDetached: __createEvent('tabs.onDetached'),
                            onHighlighted: __createEvent('tabs.onHighlighted'),
                            onMoved: __createEvent('tabs.onMoved'),
                            onReplaced: __createEvent('tabs.onReplaced'),
                            onZoomChange: __createEvent('tabs.onZoomChange')
                        },
                        alarms: {
                            create: function(nameOrInfo, alarmInfo) {
                                var name = '';
                                var info = {};
                                if (typeof nameOrInfo === 'string') {
                                    name = nameOrInfo;
                                    info = alarmInfo || {};
                                } else if (typeof nameOrInfo === 'object' && nameOrInfo !== null) {
                                    info = nameOrInfo;
                                }
                                return __callHost('alarms.create', [name, info]);
                            },
                            get: function(name, cb) { return __callHost('alarms.get', [name], cb); },
                            getAll: function(cb) { return __callHost('alarms.getAll', [], cb); },
                            clear: function(name, cb) { return __callHost('alarms.clear', [name], cb); },
                            clearAll: function(cb) { return __callHost('alarms.clearAll', [], cb); },
                            onAlarm: __createEvent('alarms.onAlarm')
                        },
                        action: {
                            setIcon: function(details, cb) { return __callHost('action.setIcon', [details], cb); },
                            setTitle: function(details, cb) { return __callHost('action.setTitle', [details], cb); },
                            setBadgeText: function(details, cb) { return __callHost('action.setBadgeText', [details], cb); },
                            setBadgeBackgroundColor: function(details, cb) { return __callHost('action.setBadgeBackgroundColor', [details], cb); },
                            getBadgeText: function(details, cb) { return __callHost('action.getBadgeText', [details], cb); },
                            getBadgeBackgroundColor: function(details, cb) { return __callHost('action.getBadgeBackgroundColor', [details], cb); },
                            getTitle: function(details, cb) { return __callHost('action.getTitle', [details], cb); },
                            onClicked: __createEvent('action.onClicked')
                        },
                        cookies: {
                            get: function(details, cb) { return __callHost('cookies.get', [details], cb); },
                            getAll: function(details, cb) { return __callHost('cookies.getAll', [details], cb); },
                            set: function(details, cb) { return __callHost('cookies.set', [details], cb); },
                            remove: function(details, cb) { return __callHost('cookies.remove', [details], cb); },
                            onChanged: __createEvent('cookies.onChanged')
                        },
                        scripting: {
                            executeScript: function(injection, cb) { return __callHost('scripting.executeScript', [injection], cb); },
                            insertCSS: function(injection, cb) { return __callHost('scripting.insertCSS', [injection], cb); },
                            removeCSS: function(injection, cb) { return __callHost('scripting.removeCSS', [injection], cb); }
                        },
                        permissions: {
                            contains: function(permissions, cb) { return __callHost('permissions.contains', [permissions], cb); },
                            request: function(permissions, cb) { return __callHost('permissions.request', [permissions], cb); },
                            remove: function(permissions, cb) { return __callHost('permissions.remove', [permissions], cb); },
                            getAll: function(cb) { return __callHost('permissions.getAll', [], cb); }
                        },
                        contextMenus: {
                            create: function(props, cb) { return __callHost('contextMenus.create', [props], cb); },
                            update: function(id, props, cb) { return __callHost('contextMenus.update', [id, props], cb); },
                            remove: function(id, cb) { return __callHost('contextMenus.remove', [id], cb); },
                            removeAll: function(cb) { return __callHost('contextMenus.removeAll', [], cb); },
                            onClicked: __createEvent('contextMenus.onClicked')
                        },
                        bookmarks: {
                            get: function(ids, cb) { return __callHost('bookmarks.get', [ids], cb); },
                            getChildren: function(id, cb) { return __callHost('bookmarks.getChildren', [id], cb); },
                            getRecent: function(numberOfItems, cb) { return __callHost('bookmarks.getRecent', [numberOfItems], cb); },
                            getTree: function(cb) { return __callHost('bookmarks.getTree', [], cb); },
                            getSubTree: function(id, cb) { return __callHost('bookmarks.getSubTree', [id], cb); },
                            search: function(query, cb) { return __callHost('bookmarks.search', [query], cb); },
                            create: function(details, cb) { return __callHost('bookmarks.create', [details], cb); },
                            update: function(id, changes, cb) { return __callHost('bookmarks.update', [id, changes], cb); },
                            move: function(id, destination, cb) { return __callHost('bookmarks.move', [id, destination], cb); },
                            remove: function(id, cb) { return __callHost('bookmarks.remove', [id], cb); },
                            removeTree: function(id, cb) { return __callHost('bookmarks.removeTree', [id], cb); },
                            onCreated: __createEvent('bookmarks.onCreated'),
                            onRemoved: __createEvent('bookmarks.onRemoved'),
                            onChanged: __createEvent('bookmarks.onChanged'),
                            onMoved: __createEvent('bookmarks.onMoved'),
                            onChildrenReordered: __createEvent('bookmarks.onChildrenReordered'),
                            onImportBegan: __createEvent('bookmarks.onImportBegan'),
                            onImportEnded: __createEvent('bookmarks.onImportEnded')
                        },
                        history: {
                            search: function(details, cb) { return __callHost('history.search', [details || {}], cb); },
                            getVisits: function(details, cb) { return __callHost('history.getVisits', [details || {}], cb); },
                            addUrl: function(details, cb) { return __callHost('history.addUrl', [details || {}], cb); },
                            deleteUrl: function(details, cb) { return __callHost('history.deleteUrl', [details || {}], cb); },
                            deleteRange: function(range, cb) { return __callHost('history.deleteRange', [range || {}], cb); },
                            deleteAll: function(cb) { return __callHost('history.deleteAll', [], cb); },
                            onVisited: __createEvent('history.onVisited'),
                            onVisitRemoved: __createEvent('history.onVisitRemoved')
                        },
                        downloads: {
                            download: function(options, cb) { return __callHost('downloads.download', [options || {}], cb); },
                            search: function(query, cb) { return __callHost('downloads.search', [query || {}], cb); },
                            pause: function(id, cb) { return __callHost('downloads.pause', [id], cb); },
                            resume: function(id, cb) { return __callHost('downloads.resume', [id], cb); },
                            cancel: function(id, cb) { return __callHost('downloads.cancel', [id], cb); },
                            removeFile: function(id, cb) { return __callHost('downloads.removeFile', [id], cb); },
                            erase: function(query, cb) { return __callHost('downloads.erase', [query || {}], cb); },
                            open: function(id, cb) { return __callHost('downloads.open', [id], cb); },
                            onCreated: __createEvent('downloads.onCreated'),
                            onChanged: __createEvent('downloads.onChanged'),
                            onDeterminingFilename: __createEvent('downloads.onDeterminingFilename'),
                            onErased: __createEvent('downloads.onErased')
                        },
                        windows: {
                            get: function(windowId, cb) { return __callHost('windows.get', [windowId], cb); },
                            getCurrent: function(populate, cb) { return __callHost('windows.getCurrent', [populate], cb); },
                            getLastFocused: function(populate, cb) { return __callHost('windows.getLastFocused', [populate], cb); },
                            getAll: function(getInfo, cb) { return __callHost('windows.getAll', [getInfo], cb); },
                            update: function(windowId, updateInfo, cb) { return __callHost('windows.update', [windowId, updateInfo || {}], cb); },
                            remove: function(windowId, cb) { return __callHost('windows.remove', [windowId], cb); },
                            onCreated: __createEvent('windows.onCreated'),
                            onRemoved: __createEvent('windows.onRemoved'),
                            onFocusChanged: __createEvent('windows.onFocusChanged')
                        },
                        tabGroups: {
                            query: function(queryInfo, cb) { return __callHost('tabGroups.query', [queryInfo || {}], cb); },
                            get: function(groupId, cb) { return __callHost('tabGroups.get', [groupId], cb); },
                            update: function(groupId, updateInfo, cb) { return __callHost('tabGroups.update', [groupId, updateInfo || {}], cb); },
                            move: function(groupId, moveInfo, cb) { return __callHost('tabGroups.move', [groupId, moveInfo || {}], cb); }
                        },
                        sessions: {
                            restore: function(sessionId, cb) { return __callHost('sessions.restore', [sessionId], cb); },
                            getDevices: function(filter, cb) { return __callHost('sessions.getDevices', [filter || {}], cb); },
                            getRecentlyClosed: function(filter, cb) { return __callHost('sessions.getRecentlyClosed', [filter || {}], cb); }
                        },
                        declarativeNetRequest: {
                            updateDynamicRules: function(options, cb) { return __callHost('declarativeNetRequest.updateDynamicRules', [options || {}], cb); },
                            getDynamicRules: function(options, cb) { return __callHost('declarativeNetRequest.getDynamicRules', [options || {}], cb); },
                            updateSessionRules: function(options, cb) { return __callHost('declarativeNetRequest.updateSessionRules', [options || {}], cb); },
                            getSessionRules: function(options, cb) { return __callHost('declarativeNetRequest.getSessionRules', [options || {}], cb); },
                            getMatchedRules: function(filter, cb) { return __callHost('declarativeNetRequest.getMatchedRules', [filter || {}], cb); },
                            isRegexSupported: function(details, cb) { return __callHost('declarativeNetRequest.isRegexSupported', [details || {}], cb); },
                            setExtensionActionOptions: function(details, cb) { return __callHost('declarativeNetRequest.setExtensionActionOptions', [details || {}], cb); }
                        },
                        management: {
                            get: function(id, cb) { return __callHost('management.get', [id], cb); },
                            getSelf: function(cb) { return __callHost('management.getSelf', [], cb); },
                            getAll: function(cb) { return __callHost('management.getAll', [], cb); },
                            setEnabled: function(id, enabled, cb) { return __callHost('management.setEnabled', [id, enabled], cb); },
                            uninstall: function(id, options, cb) { return __callHost('management.uninstall', [id, options || {}], cb); },
                            onInstalled: __createEvent('management.onInstalled'),
                            onUninstalled: __createEvent('management.onUninstalled'),
                            onEnabled: __createEvent('management.onEnabled'),
                            onDisabled: __createEvent('management.onDisabled')
                        },
                        idle: {
                            queryState: function(detectionIntervalInSeconds, cb) { return __callHost('idle.queryState', [detectionIntervalInSeconds], cb); },
                            setDetectionInterval: function(intervalInSeconds, cb) { return __callHost('idle.setDetectionInterval', [intervalInSeconds], cb); },
                            onStateChanged: __createEvent('idle.onStateChanged')
                        },
                        tts: {
                            speak: function(utterance, options, cb) { return __callHost('tts.speak', [utterance, options || {}], cb); },
                            stop: function(cb) { return __callHost('tts.stop', [], cb); },
                            pause: function(cb) { return __callHost('tts.pause', [], cb); },
                            resume: function(cb) { return __callHost('tts.resume', [], cb); },
                            isSpeaking: function(cb) { return __callHost('tts.isSpeaking', [], cb); },
                            getVoices: function(cb) { return __callHost('tts.getVoices', [], cb); },
                            onEvent: __createEvent('tts.onEvent')
                        },
                        search: {
                            query: function(queryInfo, cb) { return __callHost('search.query', [queryInfo || {}], cb); }
                        },
                        notifications: {
                            create: function(idOrOptions, optionsOrCb, cb) {
                                if (typeof idOrOptions === 'string') return __callHost('notifications.create', [idOrOptions, optionsOrCb || {}], cb);
                                return __callHost('notifications.create', [idOrOptions || {}], optionsOrCb);
                            },
                            update: function(id, options, cb) { return __callHost('notifications.update', [id, options || {}], cb); },
                            clear: function(id, cb) { return __callHost('notifications.clear', [id], cb); },
                            getAll: function(cb) { return __callHost('notifications.getAll', [], cb); },
                            onClosed: __createEvent('notifications.onClosed'),
                            onClicked: __createEvent('notifications.onClicked'),
                            onButtonClicked: __createEvent('notifications.onButtonClicked')
                        },
                        sidePanel: {
                            setOptions: function(options, cb) { return __callHost('sidePanel.setOptions', [options || {}], cb); },
                            getOptions: function(options, cb) { return __callHost('sidePanel.getOptions', [options || {}], cb); },
                            setPanelBehavior: function(options, cb) { return __callHost('sidePanel.setPanelBehavior', [options || {}], cb); },
                            getPanelBehavior: function(cb) { return __callHost('sidePanel.getPanelBehavior', [], cb); },
                            open: function(options, cb) { return __callHost('sidePanel.open', [options || {}], cb); }
                        },
                        omnibox: {
                            setDefaultSuggestion: function(suggestion, cb) { return __callHost('omnibox.setDefaultSuggestion', [suggestion || {}], cb); },
                            onInputStarted: __createEvent('omnibox.onInputStarted'),
                            onInputChanged: __createEvent('omnibox.onInputChanged'),
                            onInputEntered: __createEvent('omnibox.onInputEntered'),
                            onInputCancelled: __createEvent('omnibox.onInputCancelled')
                        },
                        commands: {
                            getAll: function(cb) { return __callHost('commands.getAll', [], cb); },
                            onCommand: __createEvent('commands.onCommand')
                        },
                        topSites: {
                            get: function(cb) { return __callHost('topSites.get', [], cb); }
                        },
                        system: {
                            cpu: { getInfo: function(cb) { return __callHost('system.cpu.getInfo', [], cb); } },
                            memory: { getInfo: function(cb) { return __callHost('system.memory.getInfo', [], cb); } },
                            storage: { getInfo: function(cb) { return __callHost('system.storage.getInfo', [], cb); } }
                        },
                        webRequest: {
                            onBeforeRequest: __createEvent('webRequest.onBeforeRequest'),
                            onBeforeSendHeaders: __createEvent('webRequest.onBeforeSendHeaders'),
                            onHeadersReceived: __createEvent('webRequest.onHeadersReceived'),
                            onCompleted: __createEvent('webRequest.onCompleted'),
                            onErrorOccurred: __createEvent('webRequest.onErrorOccurred')
                        }
                    };
                    // MV2 compatibility aliases. Chrome exposes browserAction/pageAction for
                    // legacy manifests; internally both map to the action host adapter.
                    global.chrome.browserAction = global.chrome.action;
                    global.chrome.pageAction = global.chrome.action;
                    global.chrome.extension = {
                        inIncognitoContext: false,
                        getURL: global.chrome.runtime.getURL,
                        getManifest: global.chrome.runtime.getManifest,
                        getBackgroundPage: function(cb) {
                            var p = Promise.resolve(undefined);
                            if (typeof cb === 'function') p.then(cb);
                            return p;
                        }
                    };
                    global.browser = global.chrome;

                    // Web-platform primitives commonly used by service-worker extensions.
                    // Provide the small, deterministic subset that can be backed by the Android host.
                    if (typeof TextEncoder === 'undefined') {
                        global.TextEncoder = function() {};
                        global.TextEncoder.prototype.encode = function(input) {
                            var s = unescape(encodeURIComponent(String(input)));
                            var out = new Uint8Array(s.length);
                            for (var i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
                            return out;
                        };
                    }
                    if (typeof TextDecoder === 'undefined') {
                        global.TextDecoder = function() {};
                        global.TextDecoder.prototype.decode = function(input) {
                            if (!input) return '';
                            var bytes = input instanceof Uint8Array ? input : new Uint8Array(input);
                            var s = '';
                            for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
                            return decodeURIComponent(escape(s));
                        };
                    }

                    global.fetch = function(input, init) {
                        var url = (typeof input === 'string') ? input : (input && input.url ? input.url : String(input));
                        init = init || {};
                        var headers = {};
                        var h = init.headers || {};
                        if (h.forEach) h.forEach(function(v, k) { headers[k] = v; });
                        else for (var hk in h) if (Object.prototype.hasOwnProperty.call(h, hk)) headers[hk] = h[hk];
                        var body = init.body === undefined || init.body === null ? null : String(init.body);
                        return __callHost('runtime.fetch', [{
                            url: url,
                            method: String(init.method || 'GET').toUpperCase(),
                            headers: headers,
                            body: body,
                            credentials: init.credentials || 'include'
                        }]).then(function(raw) {
                            raw = raw || {};
                            var responseHeaders = raw.headers || {};
                            return {
                                ok: !!raw.ok,
                                status: Number(raw.status || 0),
                                statusText: String(raw.statusText || ''),
                                url: String(raw.url || url),
                                headers: {
                                    get: function(name) {
                                        var key = String(name).toLowerCase();
                                        for (var k in responseHeaders) if (String(k).toLowerCase() === key) return String(responseHeaders[k]);
                                        return null;
                                    },
                                    has: function(name) { return this.get(name) !== null; },
                                    forEach: function(fn) { for (var k in responseHeaders) fn(String(responseHeaders[k]), k, this); }
                                },
                                text: function() { return Promise.resolve(raw.body || ''); },
                                json: function() { return Promise.resolve(JSON.parse(raw.body || 'null')); },
                                arrayBuffer: function() {
                                    var bin = atob(raw.bodyBase64 || '');
                                    var arr = new Uint8Array(bin.length);
                                    for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
                                    return Promise.resolve(arr.buffer);
                                }
                            };
                        });
                    };

                    // 9b. Forward-compatible namespace shim. Explicitly implemented APIs
                    // remain authoritative; unknown Chrome namespaces/methods are routed
                    // to the host instead of failing with 'undefined is not a function'.
                    // Events are registered lazily with the same host event registry used
                    // by the explicit namespace definitions above.
                    function __dynamicNamespace(path) {
                        var callable = function() {
                            return __callHost(path, Array.prototype.slice.call(arguments));
                        };
                        if (typeof Proxy !== 'undefined') {
                            return new Proxy(callable, {
                                get: function(target, prop) {
                                    if (prop === 'then' || prop === 'catch' || prop === 'finally') return undefined;
                                    if (typeof prop !== 'string') return target[prop];
                                    if (/^on[A-Z]/.test(prop)) return __createEvent(path + '.' + prop);
                                    return __dynamicNamespace(path + '.' + prop);
                                },
                                apply: function(target, thisArg, args) {
                                    return __callHost(path, args);
                                }
                            });
                        }
                        return callable;
                    }
                    if (typeof Proxy !== 'undefined') {
                        global.chrome = new Proxy(global.chrome, {
                            get: function(target, prop) {
                                if (prop in target || typeof prop !== 'string') return target[prop];
                                if (/^on[A-Z]/.test(prop)) return __createEvent(prop);
                                return __dynamicNamespace(prop);
                            }
                        });
                        global.browser = global.chrome;
                    }

                    // 10. importScripts implementation
                    // This primitive is synchronous by design. The native callback evaluates each
                    // script in-order in the same isolate/context before returning to JavaScript.
                    global.importScripts = function() {
                        return __orionNativeImportScripts.apply(global, arguments);
                    };

                    // 11. Event Dispatcher
                    global.__orionDispatchEvent = function(eventName, payloadJson) {
                        var payload = null;
                        if (payloadJson) {
                            try { payload = JSON.parse(payloadJson); } catch (e) { payload = payloadJson; }
                        }

                        if (eventName === 'runtime.onMessage' || eventName === 'onMessage') {
                            var message = (payload && payload.message !== undefined) ? payload.message : payload;
                            var sender = (payload && payload.sender) ? payload.sender : { id: global.chrome.runtime.id };
                            var callbackId = (payload && payload.callbackId) ? payload.callbackId : (payload ? payload.messageId : null);
                            var keepaliveHandle = callbackId || ('msg_' + Date.now());
                            var responseSent = false;
                            var sendResponse = function(response) {
                                if (responseSent) return;
                                responseSent = true;
                                __callHost('runtime.sendResponse', [payload ? payload.messageId : null, callbackId, response]);
                                __callHost('runtime.keepalive', ['endMessageResponse', keepaliveHandle]);
                            };

                            var asyncExpected = global.chrome.runtime.onMessage._dispatch(message, sender, sendResponse);
                            if (asyncExpected) {
                                __callHost('runtime.keepalive', ['startMessageResponse', keepaliveHandle, 'onMessageAsync']);
                            }
                            return asyncExpected;
                        }

                        if (eventName === 'runtime.onConnect' || eventName === 'onConnect') {
                            var channelId = (payload && payload.channelId) ? payload.channelId : ('ch_' + Date.now());
                            var portName = (payload && payload.name) ? payload.name : '';
                            var sender = (payload && payload.sender) ? payload.sender : { id: global.chrome.runtime.id };
                            var port = new Port(channelId, portName, sender);
                            __activePorts[channelId] = port;
                            return global.chrome.runtime.onConnect._dispatch(port);
                        }

                        if (eventName === 'port.onMessage') {
                            var chId = payload ? payload.channelId : null;
                            var port = chId ? __activePorts[chId] : null;
                            if (port) {
                                var msg = (payload && payload.message !== undefined) ? payload.message : payload;
                                return port.onMessage._dispatch(msg, port);
                            }
                            return false;
                        }

                        if (eventName === 'port.onDisconnect') {
                            var chId = payload ? payload.channelId : null;
                            var port = chId ? __activePorts[chId] : null;
                            if (port) {
                                delete __activePorts[chId];
                                return port.onDisconnect._dispatch(port);
                            }
                            return false;
                        }

                        if (eventName === 'install') {
                            var installEvt = new ExtendableEvent('install');
                            var installListeners = __workerListeners['install'] || [];
                            var installHandled = false;
                            for (var ii = 0; ii < installListeners.length; ii++) {
                                try { installListeners[ii](installEvt); installHandled = true; } catch (e) { console.error(e); }
                            }
                            return installHandled;
                        }

                        if (eventName === 'runtime.onInstalled' || eventName === 'onInstalled') {
                            var details = payload || { reason: 'install' };
                            if (typeof details === 'object' && !details.waitUntil) {
                                var extEvt = new ExtendableEvent('runtime.onInstalled');
                                details.waitUntil = extEvt.waitUntil;
                            }
                            return global.chrome.runtime.onInstalled._dispatch(details);
                        }

                        if (eventName === 'activate') {
                            var activateEvt = new ExtendableEvent('activate');
                            var activateListeners = __workerListeners['activate'] || [];
                            var activateHandled = false;
                            for (var ai = 0; ai < activateListeners.length; ai++) {
                                try { activateListeners[ai](activateEvt); activateHandled = true; } catch (e) { console.error(e); }
                            }
                            return activateHandled;
                        }

                        if (eventName === 'runtime.onStartup' || eventName === 'onStartup') {
                            return global.chrome.runtime.onStartup._dispatch();
                        }

                        if (eventName === 'runtime.onSuspend') {
                            return global.chrome.runtime.onSuspend._dispatch();
                        }

                        if (eventName === 'runtime.onSuspendCanceled') {
                            return global.chrome.runtime.onSuspendCanceled._dispatch();
                        }

                        if (eventName === 'storage.onChanged' || eventName === 'onChanged') {
                            var changes = payload ? (payload.changes || payload) : {};
                            var areaName = payload ? (payload.areaName || 'local') : 'local';
                            return global.chrome.storage.onChanged._dispatch(changes, areaName);
                        }

                        if (eventName === 'alarms.onAlarm' || eventName === 'onAlarm') {
                            if (payload && typeof payload === 'object' && !payload.waitUntil) {
                                var extEvt = new ExtendableEvent('onAlarm');
                                payload.waitUntil = extEvt.waitUntil;
                            }
                            return global.chrome.alarms.onAlarm._dispatch(payload || {});
                        }

                        if (eventName === 'tabs.onUpdated') {
                            return global.chrome.tabs.onUpdated._dispatch(payload ? payload.tabId : 0, payload ? payload.changeInfo : {}, payload ? payload.tab : {});
                        }

                        if (eventName === 'tabs.onCreated') {
                            return global.chrome.tabs.onCreated._dispatch(payload ? payload.tab : {});
                        }

                        if (eventName === 'tabs.onRemoved') {
                            return global.chrome.tabs.onRemoved._dispatch(payload ? payload.tabId : 0, payload ? payload.removeInfo : {});
                        }

                        if (eventName === 'tabs.onActivated') {
                            return global.chrome.tabs.onActivated._dispatch(payload || {});
                        }

                        if (eventName === 'action.onClicked') {
                            return global.chrome.action.onClicked._dispatch(payload ? payload.tab : {});
                        }

                        // Generic Chrome event objects (history, downloads, bookmarks,
                        // permissions, webRequest, notifications, commands, etc.). Each
                        // dynamically created event is cached by its full API path, so the
                        // object received by addListener() is the same object dispatched here.
                        var dynamicEvent = __eventObjects[eventName];
                        if (dynamicEvent) {
                            var dynamicPayload = payload || {};
                            if (dynamicPayload && Array.isArray(dynamicPayload.args)) {
                                return dynamicEvent._dispatch.apply(null, dynamicPayload.args);
                            }
                            // Match the most common WebExtension event signatures while
                            // retaining a safe generic fallback for forward-compatible APIs.
                            if (eventName === 'history.onVisited') {
                                return dynamicEvent._dispatch(dynamicPayload.historyItem || dynamicPayload);
                            }
                            if (eventName === 'history.onVisitRemoved') {
                                return dynamicEvent._dispatch(dynamicPayload.removed || dynamicPayload);
                            }
                            if (eventName === 'downloads.onCreated' || eventName === 'downloads.onChanged' || eventName === 'downloads.onErased') {
                                return dynamicEvent._dispatch(dynamicPayload.downloadItem || dynamicPayload.downloadDelta || dynamicPayload.downloadId || dynamicPayload);
                            }
                            if (eventName.indexOf('bookmarks.') === 0) {
                                return dynamicEvent._dispatch(dynamicPayload.bookmark || dynamicPayload);
                            }
                            if (eventName.indexOf('permissions.') === 0) {
                                return dynamicEvent._dispatch(dynamicPayload.permissions || dynamicPayload);
                            }
                            if (eventName.indexOf('webRequest.') === 0 || eventName.indexOf('webNavigation.') === 0) {
                                return dynamicEvent._dispatch(dynamicPayload.details || dynamicPayload);
                            }
                            if (eventName === 'commands.onCommand') {
                                return dynamicEvent._dispatch(dynamicPayload.command || dynamicPayload.name || dynamicPayload);
                            }
                            return dynamicEvent._dispatch(dynamicPayload);
                        }

                        // Generic DOM / worker event listeners fallback
                        if (__workerListeners[eventName] && __workerListeners[eventName].length > 0) {
                            var evt = new ExtendableEvent(eventName);
                            var listeners = __workerListeners[eventName];
                            for (var i = 0; i < listeners.length; i++) {
                                try { listeners[i](evt); } catch (e) { console.error(e); }
                            }
                            return true;
                        }

                        return false;
                    };
                })(this);
            """.trimIndent()
        }
}
