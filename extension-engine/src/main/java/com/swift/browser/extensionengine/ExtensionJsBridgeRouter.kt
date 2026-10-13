package com.swift.browser.extensionengine

import org.json.JSONArray
import org.json.JSONObject

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
/**
 * Canonical Router for JavaScript Extension Bridge API calls.
 * Routes raw JS calls safely to canonical adapter implementations.
 */
class ExtensionJsBridgeRouter(
    private val registry: ExtensionRegistry,
    private val permissionAdapter: ExtensionPermissionAdapter,
    private val storageBackend: ExtensionStorageBackend,
    private val messageBus: MessageBus,
    private val eventManager: EventManager,
    private val tabsAdapter: ExtensionTabsAdapter,
    private val windowsAdapter: ExtensionWindowsAdapter,
    private val tabGroupsAdapter: ExtensionTabGroupsAdapter,
    private val sessionsAdapter: ExtensionSessionsAdapter,
    private val cookieAdapter: ExtensionCookieAdapter,
    private val bookmarksAdapter: ExtensionBookmarksAdapter,
    private val historyAdapter: ExtensionHistoryAdapter,
    private val downloadsAdapter: ExtensionDownloadsAdapter,
    private val dnrAdapter: ExtensionDnrAdapter,
    val webRequestAdapter: ExtensionWebRequestAdapter,
    private val scriptingAdapter: ExtensionScriptingAdapter,
    private val actionAdapter: ExtensionActionAdapter,
    private val contextMenusAdapter: ExtensionContextMenusAdapter,
    private val commandsAdapter: ExtensionCommandsAdapter,
    private val omniboxAdapter: ExtensionOmniboxAdapter,
    private val sidePanelAdapter: ExtensionSidePanelAdapter,
    private val managementAdapter: ExtensionManagementAdapter,
    private val topSitesAdapter: ExtensionTopSitesAdapter,
    private val idleAdapter: ExtensionIdleAdapter,
    private val ttsAdapter: ExtensionTtsAdapter,
    private val searchAdapter: ExtensionSearchAdapter,
    private val alarmsAdapter: ExtensionAlarmsAdapter,
    private val systemAdapter: ExtensionSystemAdapter,
    private val notificationsAdapter: ExtensionNotificationsAdapter,
    private val browsingDataAdapter: ExtensionBrowsingDataAdapter? = null,
    private val context: android.content.Context? = null,
    private val delegate: BrowserDelegate? = null,
    val webNavigationAdapter: ExtensionWebNavigationAdapter = ExtensionWebNavigationAdapter(permissionAdapter, registry, eventManager),
    private val i18nAdapter: ExtensionI18nAdapter? = null,
    private val privacyAdapter: ExtensionPrivacyAdapter? = null,
    private val contentSettingsAdapter: ExtensionContentSettingsAdapter? = null,
    private val offscreenAdapter: ExtensionOffscreenAdapter? = null,
    private val userScriptsAdapter: ExtensionUserScriptsAdapter? = null,
    private val powerAdapter: ExtensionPowerAdapter? = null,
    private val debuggerAdapter: com.swift.browser.extensionengine.chromedebugger.ChromeDebuggerAdapter? = null
) {
    private val appCtx: android.content.Context by lazy {
        context ?: try {
            val at = Class.forName("android.app.ActivityThread")
            at.getMethod("currentApplication").invoke(null) as android.content.Context
        } catch (_: Throwable) {
            android.app.Application()
        }
    }
    private val resolvedPermissionManager: PermissionManager by lazy { PermissionManager(appCtx, permissionAdapter) }
    private val resolvedI18nAdapter: ExtensionI18nAdapter by lazy { i18nAdapter ?: ExtensionI18nAdapter(appCtx, registry) }
    private val resolvedPrivacyAdapter: ExtensionPrivacyAdapter by lazy { privacyAdapter ?: ExtensionPrivacyAdapter(appCtx, resolvedPermissionManager, registry) }
    private val resolvedContentSettingsAdapter: ExtensionContentSettingsAdapter by lazy { contentSettingsAdapter ?: ExtensionContentSettingsAdapter(appCtx, resolvedPermissionManager, registry) }
    private val resolvedOffscreenAdapter: ExtensionOffscreenAdapter by lazy { offscreenAdapter ?: ExtensionOffscreenAdapter(appCtx, resolvedPermissionManager, registry) }
    private val resolvedUserScriptsAdapter: ExtensionUserScriptsAdapter by lazy {
        userScriptsAdapter ?: ExtensionUserScriptsAdapter(appCtx, resolvedPermissionManager, registry, scriptingAdapter.worldAwareScriptExecutor)
    }
    private val resolvedPowerAdapter: ExtensionPowerAdapter by lazy { powerAdapter ?: ExtensionPowerAdapter(appCtx, resolvedPermissionManager, registry) }
    private val resolvedDebuggerAdapter: com.swift.browser.extensionengine.chromedebugger.ChromeDebuggerAdapter by lazy {
        debuggerAdapter ?: com.swift.browser.extensionengine.chromedebugger.ChromeDebuggerAdapter(
            tabEngine = tabsAdapter.tabEngine,
            registry = registry,
            permissionManager = resolvedPermissionManager,
            permissionAdapter = permissionAdapter,
            eventManager = eventManager
        )
    }

    private fun verifyApiPermission(extensionId: String, requiredPermission: String) {
        val ext = registry.getExtension(extensionId)
            ?: throw SecurityException("SecurityError: Extension $extensionId not found")
        if (!registry.isExtensionEnabled(extensionId)) {
            throw SecurityException("SecurityError: Extension $extensionId is disabled")
        }
        val reqClean = requiredPermission.trim().lowercase()
        if (reqClean == "storage" || reqClean == "runtime" || reqClean == "tabs" || reqClean == "activetab") return
        if (reqClean == "sidepanel" && ext.sidePanelPath.isNotBlank()) return
        if (reqClean == "action" && (ext.actionSpec.hasAction || ext.popupPath.isNotBlank() || ext.actionPopup.isNotBlank())) return

        if (!permissionAdapter.hasApiPermission(extensionId, requiredPermission)) {
            val manifestPerms = try {
                val manifest = JSONObject(ext.manifestJson)
                val perms = mutableListOf<String>()
                val pArr = manifest.optJSONArray("permissions")
                if (pArr != null) {
                    for (i in 0 until pArr.length()) perms.add(pArr.optString(i, ""))
                }
                val oArr = manifest.optJSONArray("host_permissions")
                if (oArr != null) {
                    for (i in 0 until oArr.length()) perms.add(oArr.optString(i, ""))
                }
                if (manifest.has("side_panel")) perms.add("sidePanel")
                perms
            } catch (e: Exception) {
                emptyList<String>()
            }
            if (!manifestPerms.any { it.equals(requiredPermission, ignoreCase = true) || (reqClean == "sidepanel" && it.equals("side_panel", ignoreCase = true)) }) {
                throw SecurityException("SecurityError: Extension does not have '$requiredPermission' permission in manifest.")
            }
        }
    }

    suspend fun handleCall(
        sender: ExtensionSender,
        api: String,
        args: JSONArray,
        isPrivate: Boolean = false,
        privateSessionId: String? = null,
        browserDelegate: BrowserDelegate? = null
    ): Any? {
        val extId = sender.extensionId

        // Verify private mode access if in private session
        if (isPrivate && !permissionAdapter.isAllowedInPrivate(extId)) {
            throw SecurityException("SecurityError: Extension $extId is not allowed in private mode.")
        }

        return when {
            // Storage API
            api.startsWith("storage.") -> {
                verifyApiPermission(extId, "storage")
                val area = args.optString(0, "local")
                when {
                    api.endsWith(".get") -> storageBackend.get(extId, area, args.opt(1), isPrivate, privateSessionId)
                    api.endsWith(".set") -> {
                        val items = args.optJSONObject(1) ?: JSONObject()
                        storageBackend.set(extId, area, items, isPrivate, privateSessionId)
                        null
                    }
                    api.endsWith(".remove") -> {
                        val keysArray = args.optJSONArray(1)
                        val keysList = mutableListOf<String>()
                        if (keysArray != null) {
                            for (i in 0 until keysArray.length()) keysList.add(keysArray.getString(i))
                        } else {
                            val singleKey = args.optString(1, "")
                            if (singleKey.isNotBlank()) keysList.add(singleKey)
                        }
                        storageBackend.remove(extId, area, keysList, isPrivate, privateSessionId)
                        null
                    }
                    api.endsWith(".clear") -> {
                        storageBackend.clear(extId, area, isPrivate, privateSessionId)
                        null
                    }
                    api.endsWith(".getBytesInUse") -> {
                        storageBackend.getBytesInUse(extId, area, args.opt(1), isPrivate, privateSessionId)
                    }
                    api.endsWith(".getKeys") -> {
                        JSONArray(storageBackend.getKeys(extId, area, isPrivate, privateSessionId))
                    }
                    api.endsWith(".setAccessLevel") -> {
                        val level = args.optString(1, "TRUSTED_AND_UNTRUSTED_CONTEXTS")
                        storageBackend.setAccessLevel(extId, area, level)
                        null
                    }
                    api.endsWith(".getAccessLevel") -> {
                        storageBackend.getAccessLevel(extId, area)
                    }
                    else -> throw IllegalArgumentException("Unsupported storage operation: $api")
                }
            }

            // Tabs API
            api == "tabs.get" -> tabsAdapter.getTab(sender, args.opt(0))
            api == "tabs.query" -> tabsAdapter.queryTabs(sender, args.optJSONObject(0) ?: JSONObject())
            api == "tabs.create" -> tabsAdapter.createTab(sender, args.optJSONObject(0) ?: JSONObject())
            api == "tabs.remove" -> {
                val ids = args.opt(0)
                val targets = if (ids is JSONArray) {
                    buildList {
                        for (i in 0 until ids.length()) add(ids.opt(i))
                    }
                } else {
                    listOf(ids)
                }
                tabsAdapter.removeTabs(sender, targets)
            }
            api == "tabs.reload" -> {
                tabsAdapter.reloadTab(sender, args.opt(0))
                null
            }
            api == "tabs.update" -> tabsAdapter.updateTab(sender, args.opt(0), args.optJSONObject(1) ?: JSONObject())
            api == "tabs.duplicate" -> tabsAdapter.duplicateTab(sender, args.opt(0))
            api == "tabs.move" -> {
                val ids = args.opt(0)
                val moveProperties = args.optJSONObject(1) ?: JSONObject()
                if (ids is JSONArray) {
                    val list = mutableListOf<Any>()
                    for (i in 0 until ids.length()) list.add(ids.get(i))
                    tabsAdapter.moveTabs(sender, list, moveProperties)
                } else {
                    tabsAdapter.moveTab(sender, ids, moveProperties)
                }
            }
            api == "tabs.highlight" -> tabsAdapter.highlight(sender, args.optJSONObject(0) ?: JSONObject())
            api == "tabs.detectLanguage" -> tabsAdapter.detectLanguage(sender, if (args.length() > 0) args.optInt(0) else null)
            api == "tabs.discard" -> tabsAdapter.discard(sender, if (args.length() > 0) args.optInt(0) else null)
            api == "tabs.getZoomSettings" -> tabsAdapter.getZoomSettings(sender, if (args.length() > 0) args.optInt(0) else null)
            api == "tabs.setZoomSettings" -> tabsAdapter.setZoomSettings(sender, if (args.length() > 0) args.optInt(0) else null, args.optJSONObject(1) ?: JSONObject())
            api == "tabs.getZoom" -> tabsAdapter.getZoom(sender, if (args.length() > 0) args.opt(0) else null)
            api == "tabs.setZoom" -> {
                val tabId = if (args.length() > 1) args.opt(0) else null
                val factor = if (args.length() > 1) args.optDouble(1, 1.0) else args.optDouble(0, 1.0)
                tabsAdapter.setZoom(sender, tabId, factor)
            }
            api == "tabs.group" -> tabsAdapter.group(sender, args.optJSONObject(0) ?: JSONObject())
            api == "tabs.ungroup" -> {
                val ids = args.opt(0)
                val list = mutableListOf<Int>()
                if (ids is JSONArray) {
                    for (i in 0 until ids.length()) list.add(ids.getInt(i))
                } else if (ids is Number) {
                    list.add(ids.toInt())
                }
                tabsAdapter.ungroup(sender, list)
            }
            api == "tabs.executeScript" -> {
                val tabId = if (args.opt(0) is Number) args.optInt(0) else null
                val details = (if (args.opt(0) is JSONObject) args.optJSONObject(0) else args.optJSONObject(1)) ?: JSONObject()
                val (res, err) = suspendCancellableCoroutine<Pair<JSONArray?, String?>> { continuation ->
                    tabsAdapter.executeScript(sender, tabId, details) { r, e ->
                        if (continuation.isActive) continuation.resume(r to e)
                    }
                }
                if (err != null) throw IllegalArgumentException(err)
                res ?: JSONArray()
            }
            api == "tabs.insertCSS" -> {
                val tabId = if (args.opt(0) is Number) args.optInt(0) else null
                val details = (if (args.opt(0) is JSONObject) args.optJSONObject(0) else args.optJSONObject(1)) ?: JSONObject()
                val (res, err) = suspendCancellableCoroutine<Pair<Boolean, String?>> { continuation ->
                    tabsAdapter.insertCSS(sender, tabId, details) { r, e ->
                        if (continuation.isActive) continuation.resume(r to e)
                    }
                }
                if (err != null) throw IllegalArgumentException(err)
                JSONObject().put("status", "success")
            }
            api == "tabs.removeCSS" -> {
                val tabId = if (args.opt(0) is Number) args.optInt(0) else null
                val details = (if (args.opt(0) is JSONObject) args.optJSONObject(0) else args.optJSONObject(1)) ?: JSONObject()
                val (res, err) = suspendCancellableCoroutine<Pair<Boolean, String?>> { continuation ->
                    tabsAdapter.removeCSS(sender, tabId, details) { r, e ->
                        if (continuation.isActive) continuation.resume(r to e)
                    }
                }
                if (err != null) throw IllegalArgumentException(err)
                JSONObject().put("status", "success")
            }
            api == "tabs.sendMessage" -> {
                val targetTabId = args.opt(0)
                val message = args.opt(1) ?: JSONObject()
                val callbackId = null
                tabsAdapter.sendMessageToTab(sender, targetTabId, if (message is JSONObject) message else JSONObject().put("value", message), callbackId)
                null
            }
            api == "tabs.connect" -> {
                val targetTabId = args.opt(0)
                val connectInfo = args.optJSONObject(1)
                tabsAdapter.connectToTab(sender, targetTabId, connectInfo).let { connection ->
                    JSONObject().apply {
                        put("channelId", connection.channelId)
                        put("name", connection.name)
                    }
                }
            }
            api == "tabs.captureVisibleTab" -> {
                val windowId = args.optInt(0, -1)
                val options = args.optJSONObject(1) ?: JSONObject()
                tabsAdapter.captureVisibleTab(sender, windowId, options)
            }
            api == "tabs.goBack" -> {
                tabsAdapter.goBack(sender, args.opt(0))
                null
            }
            api == "tabs.goForward" -> {
                tabsAdapter.goForward(sender, args.opt(0))
                null
            }

            // Windows API
            api == "windows.get" -> windowsAdapter.getWindow(sender, args.optInt(0, 1), args.optBoolean(1, false))
            api == "windows.getCurrent" -> windowsAdapter.getCurrentWindow(sender, args.optBoolean(0, false))
            api == "windows.getLastFocused" -> windowsAdapter.getLastFocusedWindow(sender, args.optBoolean(0, false))
            api == "windows.getAll" -> windowsAdapter.getAllWindows(sender, args.optBoolean(0, false))
            api == "windows.create" -> windowsAdapter.createWindow(sender, args.optJSONObject(0) ?: JSONObject())
            api == "windows.update" -> windowsAdapter.updateWindow(sender, args.optInt(0, 1), args.optJSONObject(1) ?: JSONObject())
            api == "windows.remove" -> {
                windowsAdapter.removeWindow(sender, args.optInt(0, 1))
                null
            }

            // TabGroups API
            api.startsWith("tabGroups.") -> {
                verifyApiPermission(extId, "tabGroups")
                when {
                    api.endsWith(".query") -> tabGroupsAdapter.queryGroups(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".get") -> tabGroupsAdapter.getGroup(sender, args.opt(0))
                    api.endsWith(".update") -> tabGroupsAdapter.updateGroup(sender, args.opt(0), args.optJSONObject(1) ?: JSONObject())
                    api.endsWith(".move") -> tabGroupsAdapter.moveGroup(sender, args.opt(0), args.optJSONObject(1) ?: JSONObject())
                    else -> throw IllegalArgumentException("Unsupported tabGroups API: $api")
                }
            }

            // Sessions API
            api.startsWith("sessions.") -> {
                verifyApiPermission(extId, "sessions")
                when {
                    api.endsWith(".restore") -> {
                        val sessionId = if (args.length() > 0) args.optString(0, null) else null
                        sessionsAdapter.restore(sender, sessionId)
                    }
                    api.endsWith(".getDevices") -> sessionsAdapter.getDevices(sender)
                    api.endsWith(".getRecentlyClosed") -> sessionsAdapter.getRecentlyClosed(sender, args.optJSONObject(0))
                    else -> throw IllegalArgumentException("Unsupported sessions API: $api")
                }
            }

            // Scripting API
            api.startsWith("scripting.") -> {
                verifyApiPermission(extId, "scripting")
                val activeContext = context ?: throw IllegalStateException("Context required for scripting API")
                val activeDelegate = browserDelegate ?: delegate
                when {
                    api.endsWith(".executeScript") -> {
                        val (scriptResult, scriptError) = suspendCancellableCoroutine<Pair<JSONArray?, String?>> { continuation ->
                            scriptingAdapter.executeScript(
                                sender = sender,
                                spec = args.optJSONObject(0) ?: JSONObject(),
                                delegate = activeDelegate,
                                context = activeContext,
                                callback = { res, err ->
                                    if (continuation.isActive) continuation.resume(res to err)
                                }
                            )
                        }
                        if (scriptError != null) throw IllegalArgumentException(scriptError)
                        scriptResult ?: JSONArray()
                    }
                    api.endsWith(".insertCSS") -> scriptingAdapter.insertCSS(sender, args.optJSONObject(0) ?: JSONObject(), activeDelegate, activeContext)
                    api.endsWith(".removeCSS") -> scriptingAdapter.removeCSS(sender, args.optJSONObject(0) ?: JSONObject(), activeDelegate, activeContext)
                    api.endsWith(".registerContentScripts") -> scriptingAdapter.registerContentScripts(sender, args.optJSONArray(0) ?: JSONArray())
                    api.endsWith(".unregisterContentScripts") -> scriptingAdapter.unregisterContentScripts(sender, if (args.opt(0) is JSONArray) args.optJSONArray(0) else null)
                    api.endsWith(".updateContentScripts") -> scriptingAdapter.updateContentScripts(sender, args.optJSONArray(0) ?: JSONArray())
                    api.endsWith(".getRegisteredContentScripts") -> scriptingAdapter.getRegisteredContentScripts(sender)
                    else -> throw IllegalArgumentException("Unsupported scripting API: $api")
                }
            }

            // Cookies API
            api.startsWith("cookies.") -> {
                verifyApiPermission(extId, "cookies")
                when {
                    api.endsWith(".get") -> cookieAdapter.get(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".getAll") -> cookieAdapter.getAll(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".set") -> withContext(Dispatchers.IO) { cookieAdapter.set(sender, args.optJSONObject(0) ?: JSONObject()) }
                    api.endsWith(".remove") -> withContext(Dispatchers.IO) { cookieAdapter.remove(sender, args.optJSONObject(0) ?: JSONObject()) }
                    api.endsWith(".getAllCookieStores") -> cookieAdapter.getAllCookieStores(sender)
                    else -> throw IllegalArgumentException("Unsupported cookies API: $api")
                }
            }

            // Bookmarks API
            api.startsWith("bookmarks.") -> {
                verifyApiPermission(extId, "bookmarks")
                when {
                    api.endsWith(".get") -> bookmarksAdapter.get(sender, args.opt(0))
                    api.endsWith(".getChildren") -> bookmarksAdapter.getChildren(sender, args.optString(0, "1"))
                    api.endsWith(".getRecent") -> bookmarksAdapter.getRecent(sender, args.optInt(0, 10))
                    api.endsWith(".getTree") -> bookmarksAdapter.getTree(sender)
                    api.endsWith(".getSubTree") -> bookmarksAdapter.getSubTree(sender, args.optString(0, "1"))
                    api.endsWith(".search") -> bookmarksAdapter.search(sender, args.opt(0))
                    api.endsWith(".create") -> bookmarksAdapter.create(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".update") -> bookmarksAdapter.update(sender, args.optString(0, ""), args.optJSONObject(1) ?: JSONObject())
                    api.endsWith(".move") -> bookmarksAdapter.move(sender, args.optString(0, ""), args.optJSONObject(1) ?: JSONObject())
                    api.endsWith(".remove") -> bookmarksAdapter.remove(sender, args.optString(0, ""))
                    api.endsWith(".removeTree") -> bookmarksAdapter.removeTree(sender, args.optString(0, ""))
                    else -> throw IllegalArgumentException("Unsupported bookmarks API: $api")
                }
            }

            // History API
            api.startsWith("history.") -> {
                verifyApiPermission(extId, "history")
                when {
                    api.endsWith(".search") -> historyAdapter.search(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".getVisits") -> historyAdapter.getVisits(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".addUrl") -> historyAdapter.addUrl(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".deleteUrl") -> historyAdapter.deleteUrl(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".deleteRange") -> historyAdapter.deleteRange(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".deleteAll") -> historyAdapter.deleteAll(sender)
                    else -> throw IllegalArgumentException("Unsupported history API: $api")
                }
            }

            // Downloads API
            api.startsWith("downloads.") -> {
                verifyApiPermission(extId, "downloads")
                when {
                    api.endsWith(".download") -> downloadsAdapter.download(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".search") -> downloadsAdapter.search(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".pause") -> downloadsAdapter.pause(sender, args.optLong(0, -1L))
                    api.endsWith(".resume") -> downloadsAdapter.resume(sender, args.optLong(0, -1L))
                    api.endsWith(".cancel") -> downloadsAdapter.cancel(sender, args.optLong(0, -1L))
                    api.endsWith(".removeFile") -> downloadsAdapter.removeFile(sender, args.optLong(0, -1L))
                    api.endsWith(".erase") -> downloadsAdapter.erase(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".open") -> {
                        verifyApiPermission(extId, "downloads.open")
                        downloadsAdapter.open(sender, args.optLong(0, -1L))
                    }
                    api.endsWith(".show") -> downloadsAdapter.show(sender, args.optLong(0, -1L))
                    api.endsWith(".showDefaultFolder") -> downloadsAdapter.showDefaultFolder(sender)
                    api.endsWith(".setShelfEnabled") -> downloadsAdapter.setShelfEnabled(sender, args.optBoolean(0, true))
                    api.endsWith(".acceptDanger") -> downloadsAdapter.acceptDanger(sender, args.optLong(0, -1L))
                    api.endsWith(".getFileIcon") -> downloadsAdapter.getFileIcon(sender, args.optLong(0, -1L), args.optJSONObject(1))
                    else -> throw IllegalArgumentException("Unsupported downloads API: $api")
                }
            }

            // DeclarativeNetRequest API
            api.startsWith("declarativeNetRequest.") -> {
                verifyApiPermission(extId, "declarativeNetRequest")
                when {
                    api.endsWith(".updateDynamicRules") -> dnrAdapter.updateDynamicRules(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".getDynamicRules") -> dnrAdapter.getDynamicRules(sender)
                    api.endsWith(".updateSessionRules") -> dnrAdapter.updateSessionRules(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".getSessionRules") -> dnrAdapter.getSessionRules(sender)
                    api.endsWith(".getMatchedRules") -> dnrAdapter.getMatchedRules(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".isRegexSupported") -> dnrAdapter.isRegexSupported(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".setExtensionActionOptions") -> dnrAdapter.setExtensionActionOptions(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".getEnabledRulesets") -> dnrAdapter.getEnabledRulesets(sender)
                    api.endsWith(".updateEnabledRulesets") -> dnrAdapter.updateEnabledRulesets(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".testMatchOutcome") -> dnrAdapter.testMatchOutcome(sender, args.optJSONObject(0) ?: JSONObject())
                    else -> throw IllegalArgumentException("Unsupported declarativeNetRequest API: $api")
                }
            }

            // Permissions API
            api.startsWith("permissions.") -> {
                val requested = args.optJSONObject(0) ?: JSONObject()
                val permsObj = ExtensionPermissionsObject.fromJsonObject(requested)
                val requestedPermissions = permsObj.permissions
                val requestedOrigins = permsObj.origins
                when {
                    api.endsWith(".contains") -> permissionAdapter.contains(
                        extensionId = extId,
                        permissions = requestedPermissions,
                        origins = requestedOrigins,
                        isPrivate = sender.isPrivate
                    )
                    api.endsWith(".getAll") -> permissionAdapter.getAll(
                        extensionId = extId,
                        isPrivate = sender.isPrivate
                    ).toJsonObject()
                    api.endsWith(".request") -> suspendCancellableCoroutine<Any?> { continuation ->
                        permissionAdapter.request(
                            ExtensionPermissionRequest(
                                extensionId = extId,
                                permissions = requestedPermissions,
                                origins = requestedOrigins,
                                sourceContext = sender.contextType.name,
                                tabId = sender.tabId?.let { TabIdMapper.getIntId(it) },
                                documentId = sender.documentId,
                                frameId = sender.frameId,
                                origin = sender.origin,
                                isPrivate = sender.isPrivate,
                                privateSessionId = sender.privateSessionId,
                                isUserGesture = requested.optBoolean("userGesture", false)
                            )
                        ) { result ->
                            if (!continuation.isActive) return@request
                            continuation.resume(
                                when (result) {
                                    is ExtensionPermissionResult.Granted ->
                                        JSONObject()
                                            .put("granted", true)
                                            .put("permissions", JSONArray(result.permissions))
                                            .put("origins", JSONArray(result.origins))
                                    is ExtensionPermissionResult.Denied ->
                                        JSONObject().put("granted", false).put("reason", result.reason)
                                    is ExtensionPermissionResult.Error ->
                                        JSONObject().put("granted", false)
                                            .put("error", result.message)
                                            .put("code", result.code)
                                }
                            )
                        }
                    }
                    api.endsWith(".remove") -> suspendCancellableCoroutine<Any?> { continuation ->
                        permissionAdapter.remove(
                            extensionId = extId,
                            permissions = requestedPermissions,
                            origins = requestedOrigins,
                            isPrivate = sender.isPrivate
                        ) { result ->
                            if (!continuation.isActive) return@remove
                            continuation.resume(
                                when (result) {
                                    is ExtensionPermissionResult.Granted ->
                                        JSONObject().put("removed", true)
                                    is ExtensionPermissionResult.Denied ->
                                        JSONObject().put("removed", false).put("reason", result.reason)
                                    is ExtensionPermissionResult.Error ->
                                        JSONObject().put("removed", false)
                                            .put("error", result.message)
                                            .put("code", result.code)
                                }
                            )
                        }
                    }
                    else -> throw IllegalArgumentException("Unsupported permissions API: $api")
                }
            }

            // Action / BrowserAction / PageAction API
            api.startsWith("action.") || api.startsWith("browserAction.") || api.startsWith("pageAction.") -> {
                when {
                    api.endsWith(".setIcon") -> actionAdapter.setIcon(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".setTitle") -> actionAdapter.setTitle(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".getTitle") -> actionAdapter.getTitle(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".setPopup") -> actionAdapter.setPopup(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".getPopup") -> actionAdapter.getPopup(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".setBadgeText") -> actionAdapter.setBadgeText(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".getBadgeText") -> actionAdapter.getBadgeText(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".setBadgeBackgroundColor") -> actionAdapter.setBadgeBackgroundColor(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".getBadgeBackgroundColor") -> actionAdapter.getBadgeBackgroundColor(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".setBadgeTextColor") -> actionAdapter.setBadgeTextColor(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".getBadgeTextColor") -> actionAdapter.getBadgeTextColor(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".isEnabled") -> actionAdapter.isEnabled(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".getUserSettings") -> actionAdapter.getUserSettings(sender)
                    api.endsWith(".enable") -> actionAdapter.enable(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".disable") -> actionAdapter.disable(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".openPopup") -> actionAdapter.openPopup(sender, args.optJSONObject(0) ?: JSONObject(), browserDelegate)
                    else -> throw IllegalArgumentException("Unsupported action API: $api")
                }
            }

            // ContextMenus API
            api.startsWith("contextMenus.") -> {
                when {
                    api.endsWith(".create") -> contextMenusAdapter.create(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".update") -> contextMenusAdapter.update(sender, args.optString(0, ""), args.optJSONObject(1) ?: JSONObject())
                    api.endsWith(".remove") -> contextMenusAdapter.remove(sender, args.optString(0, ""))
                    api.endsWith(".removeAll") -> contextMenusAdapter.removeAll(sender)
                    else -> throw IllegalArgumentException("Unsupported contextMenus API: $api")
                }
            }

            // Commands API
            api == "commands.getAll" -> commandsAdapter.getAll(sender)

            // Omnibox API
            api == "omnibox.setDefaultSuggestion" -> omniboxAdapter.setDefaultSuggestion(sender, args.optJSONObject(0) ?: JSONObject())

            // SidePanel API
            api.startsWith("sidePanel.") -> {
                verifyApiPermission(extId, "sidePanel")
                when {
                    api.endsWith(".setOptions") -> sidePanelAdapter.setOptions(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".getOptions") -> sidePanelAdapter.getOptions(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".setPanelBehavior") -> sidePanelAdapter.setPanelBehavior(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".getPanelBehavior") -> sidePanelAdapter.getPanelBehavior(sender)
                    api.endsWith(".open") -> sidePanelAdapter.open(sender, args.optJSONObject(0) ?: JSONObject(), appCtx)
                    else -> throw IllegalArgumentException("Unsupported sidePanel API: $api")
                }
            }

            // Management API
            api.startsWith("management.") -> {
                verifyApiPermission(extId, "management")
                when {
                    api.endsWith(".get") -> managementAdapter.get(sender, args.optString(0, ""))
                    api.endsWith(".getSelf") -> managementAdapter.getSelf(sender)
                    api.endsWith(".getAll") -> managementAdapter.getAll(sender)
                    api.endsWith(".setEnabled") -> managementAdapter.setEnabled(sender, args.optString(0, ""), args.optBoolean(1, true))
                    api.endsWith(".uninstall") -> managementAdapter.uninstall(sender, args.optString(0, ""), args.optJSONObject(1))
                    api.endsWith(".uninstallSelf") -> managementAdapter.uninstallSelf(sender, args.optJSONObject(0))
                    api.endsWith(".getPermissionWarningsById") -> managementAdapter.getPermissionWarningsById(sender, args.optString(0, ""))
                    api.endsWith(".getPermissionWarningsByManifest") -> managementAdapter.getPermissionWarningsByManifest(sender, args.optString(0, ""))
                    else -> throw IllegalArgumentException("Unsupported management API: $api")
                }
            }

            // TopSites API
            api == "topSites.get" -> {
                verifyApiPermission(extId, "topSites")
                topSitesAdapter.get(sender, browserDelegate ?: delegate)
            }

            // Idle API
            api.startsWith("idle.") -> {
                verifyApiPermission(extId, "idle")
                when {
                    api.endsWith(".queryState") -> idleAdapter.queryState(sender, args.optInt(0, 60), context)
                    api.endsWith(".setDetectionInterval") -> idleAdapter.setDetectionInterval(sender, args.optInt(0, 60))
                    else -> throw IllegalArgumentException("Unsupported idle API: $api")
                }
            }

            // TTS API
            api.startsWith("tts.") -> {
                verifyApiPermission(extId, "tts")
                when {
                    api.endsWith(".speak") -> ttsAdapter.speak(sender, args.optString(0, ""), args.optJSONObject(1) ?: JSONObject(), context)
                    api.endsWith(".stop") -> ttsAdapter.stop(sender)
                    api.endsWith(".pause") -> ttsAdapter.pause(sender)
                    api.endsWith(".resume") -> ttsAdapter.resume(sender)
                    api.endsWith(".isSpeaking") -> ttsAdapter.isSpeaking(sender)
                    api.endsWith(".getVoices") -> ttsAdapter.getVoices(sender)
                    else -> throw IllegalArgumentException("Unsupported tts API: $api")
                }
            }

            // Search API
            api == "search.query" -> searchAdapter.query(sender, args.optJSONObject(0) ?: JSONObject(), browserDelegate ?: delegate)

            // Alarms API
            api.startsWith("alarms.") -> {
                verifyApiPermission(extId, "alarms")
                when {
                    api.endsWith(".create") -> alarmsAdapter.create(sender, args.optString(0, ""), args.optJSONObject(1) ?: JSONObject())
                    api.endsWith(".get") -> alarmsAdapter.get(sender, args.optString(0, ""))
                    api.endsWith(".getAll") -> alarmsAdapter.getAll(sender)
                    api.endsWith(".clear") -> alarmsAdapter.clear(sender, args.optString(0, ""))
                    api.endsWith(".clearAll") -> alarmsAdapter.clearAll(sender)
                    else -> throw IllegalArgumentException("Unsupported alarms API: $api")
                }
            }

            // System API
            api.startsWith("system.") -> {
                verifyApiPermission(extId, "system")
                when {
                    api.endsWith(".cpu.getInfo") -> systemAdapter.getCpuInfo(sender, context)
                    api.endsWith(".memory.getInfo") -> systemAdapter.getMemoryInfo(sender, context)
                    api.endsWith(".storage.getInfo") -> systemAdapter.getStorageInfo(sender, context)
                    else -> throw IllegalArgumentException("Unsupported system API: $api")
                }
            }

            // Notifications API
            api.startsWith("notifications.") -> {
                verifyApiPermission(extId, "notifications")
                val activeDelegate = browserDelegate ?: delegate
                when {
                    api.endsWith(".create") -> notificationsAdapter.create(sender, if (args.opt(0) is String) args.optString(0) else null, args.optJSONObject(if (args.opt(0) is String) 1 else 0) ?: JSONObject(), activeDelegate)
                    api.endsWith(".update") -> notificationsAdapter.update(sender, args.optString(0, ""), args.optJSONObject(1) ?: JSONObject())
                    api.endsWith(".clear") -> notificationsAdapter.clear(sender, args.optString(0, ""))
                    api.endsWith(".getAll") -> notificationsAdapter.getAll(sender)
                    api.endsWith(".getPermissionLevel") -> notificationsAdapter.getPermissionLevel(sender)
                    else -> throw IllegalArgumentException("Unsupported notifications API: $api")
                }
            }

            // WebNavigation API
            api.startsWith("webNavigation.") -> {
                verifyApiPermission(extId, "webNavigation")
                when {
                    api.endsWith(".getFrame") -> webNavigationAdapter.getFrame(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".getAllFrames") -> webNavigationAdapter.getAllFrames(sender, args.optJSONObject(0) ?: JSONObject())
                    else -> throw IllegalArgumentException("Unsupported webNavigation API method: $api")
                }
            }

            // Runtime API helpers
            api == "runtime.getURL" -> {
                val rawPath = args.optString(0, "")
                buildCanonicalExtensionUrl(extId, rawPath)
            }
            api == "runtime.getManifest" -> {
                val ext = registry.getExtension(extId)
                if (ext != null) JSONObject(ext.manifestJson) else JSONObject()
            }
            api == "runtime.getPlatformInfo" -> {
                JSONObject().apply {
                    put("os", "android")
                    put("arch", "arm64")
                    put("nacl_arch", "arm")
                }
            }
            api == "runtime.openOptionsPage" -> {
                val ext = registry.getExtension(extId) ?: throw IllegalArgumentException("EXTENSION_NOT_FOUND: $extId")
                val activeContext = context ?: throw IllegalStateException("Context required to resolve options surface")
                val activeDelegate = browserDelegate ?: delegate ?: throw IllegalStateException("Browser delegate required to open tab")

                val surface = ExtensionSurfaceResolver.resolveOptionsSurface(activeContext, ext)
                if (surface.surfaceType == ExtensionSurfaceType.OPTIONS_PAGE && !surface.fullUrl.isNullOrEmpty()) {
                    activeDelegate.createTab(surface.fullUrl, true)
                    JSONObject().put("status", "success")
                } else {
                    throw IllegalArgumentException("NO_OPTIONS_PAGE: Extension $extId has no valid options page file")
                }
            }
            api == "runtime.setUninstallURL" -> {
                val uninstallUrl = args.optString(0, "")
                if (uninstallUrl.isNotBlank() && context != null) {
                    context.getSharedPreferences("extension_uninstall_urls", android.content.Context.MODE_PRIVATE)
                        .edit().putString(extId, uninstallUrl).apply()
                }
                JSONObject().put("status", "success")
            }
            api == "runtime.reload" -> {
                val parsed = registry.getExtension(extId)
                if (parsed != null) {
                    eventManager.triggerEventForExtension(extId, "runtime.onRestartRequired", JSONObject().put("reason", "extension_reload"))
                }
                JSONObject().put("status", "success")
            }
            api == "runtime.getContexts" -> {
                val arr = JSONArray()
                arr.put(JSONObject().apply {
                    put("contextType", sender.contextType.name)
                    put("extensionId", extId)
                    put("frameId", sender.frameId)
                    put("documentId", sender.documentId ?: "")
                    put("documentUrl", sender.origin ?: "")
                })
                arr
            }

            // i18n API
            api.startsWith("i18n.") -> {
                when {
                    api.endsWith(".getMessage") -> {
                        val messageName = args.optString(0, "")
                        val substitutions = args.opt(1)
                        resolvedI18nAdapter.getMessage(sender, messageName, substitutions)
                    }
                    api.endsWith(".getUILanguage") -> resolvedI18nAdapter.getUILanguage()
                    api.endsWith(".getAcceptLanguages") -> resolvedI18nAdapter.getAcceptLanguages()
                    api.endsWith(".detectLanguage") -> resolvedI18nAdapter.detectLanguage(args.optString(0, ""))
                    else -> throw IllegalArgumentException("Unsupported i18n API: $api")
                }
            }

            // Privacy API
            api.startsWith("privacy.") -> {
                verifyApiPermission(extId, "privacy")
                val settingKey = api.removePrefix("privacy.").substringBeforeLast(".")
                when {
                    api.endsWith(".get") -> resolvedPrivacyAdapter.getSetting(sender, settingKey, args.optJSONObject(0))
                    api.endsWith(".set") -> resolvedPrivacyAdapter.setSetting(sender, settingKey, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".clear") -> resolvedPrivacyAdapter.clearSetting(sender, settingKey, args.optJSONObject(0))
                    else -> throw IllegalArgumentException("Unsupported privacy API: $api")
                }
            }

            // ContentSettings API
            api.startsWith("contentSettings.") -> {
                verifyApiPermission(extId, "contentSettings")
                val contentType = api.removePrefix("contentSettings.").substringBeforeLast(".")
                when {
                    api.endsWith(".get") -> resolvedContentSettingsAdapter.getSetting(sender, contentType, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".set") -> resolvedContentSettingsAdapter.setSetting(sender, contentType, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".clear") -> resolvedContentSettingsAdapter.clearSetting(sender, contentType, args.optJSONObject(0))
                    api.endsWith(".getResourceIdentifiers") -> resolvedContentSettingsAdapter.getResourceIdentifiers(sender, contentType)
                    else -> throw IllegalArgumentException("Unsupported contentSettings API: $api")
                }
            }

            // Offscreen API
            api.startsWith("offscreen.") -> {
                verifyApiPermission(extId, "offscreen")
                when {
                    api.endsWith(".createDocument") -> resolvedOffscreenAdapter.createDocument(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".closeDocument") -> resolvedOffscreenAdapter.closeDocument(sender)
                    api.endsWith(".hasDocument") -> resolvedOffscreenAdapter.hasDocument(sender)
                    else -> throw IllegalArgumentException("Unsupported offscreen API: $api")
                }
            }

            // UserScripts API
            api.startsWith("userScripts.") -> {
                verifyApiPermission(extId, "userScripts")
                when {
                    api.endsWith(".register") -> resolvedUserScriptsAdapter.register(sender, args.optJSONArray(0) ?: JSONArray())
                    api.endsWith(".unregister") -> resolvedUserScriptsAdapter.unregister(sender, args.optJSONObject(0))
                    api.endsWith(".update") -> resolvedUserScriptsAdapter.update(sender, args.optJSONArray(0) ?: JSONArray())
                    api.endsWith(".getScripts") -> resolvedUserScriptsAdapter.getScripts(sender, args.optJSONObject(0))
                    api.endsWith(".execute") -> {
                        val (result, error) = kotlinx.coroutines.suspendCancellableCoroutine<Pair<JSONArray?, String?>> { continuation ->
                            resolvedUserScriptsAdapter.execute(sender, args.optJSONObject(0) ?: JSONObject(), browserDelegate ?: delegate) { r, e ->
                                if (continuation.isActive) continuation.resume(r to e)
                            }
                        }
                        if (error != null) throw IllegalStateException(error)
                        result ?: JSONArray()
                    }
                    api.endsWith(".configureWorld") -> resolvedUserScriptsAdapter.configureWorld(sender, args.optJSONObject(0) ?: JSONObject())
                    api.endsWith(".getWorldConfigurations") -> resolvedUserScriptsAdapter.getWorldConfigurations(sender)
                    api.endsWith(".resetWorldConfiguration") -> {
                        val properties = args.optJSONObject(0)
                        val worldId = properties?.optString("worldId", "default")
                            ?: args.optString(0, "default")
                        resolvedUserScriptsAdapter.resetWorldConfiguration(sender, worldId)
                    }
                    else -> throw IllegalArgumentException("Unsupported userScripts API: $api")
                }
            }

            // Identity API: no native OAuth/account broker exists in this Android architecture.
            // Do not synthesize tokens, profile IDs, or chromiumapp.org redirects.
            api.startsWith("identity.") -> {
                throw UnsupportedOperationException("Chrome Identity API '$api' requires a real OAuth/account broker")
            }

            // BrowsingData API: clear only through canonical Orion engines.
            api.startsWith("browsingData.") -> {
                val bda = browsingDataAdapter ?: throw UnsupportedOperationException("browsingData backend is not registered")
                when {
                    api == "browsingData.remove" -> bda.remove(sender, args.optJSONObject(0) ?: JSONObject())
                    api == "browsingData.removeHistory" -> bda.removeHistory(sender, args.optJSONObject(0) ?: JSONObject())
                    api == "browsingData.removeDownloads" -> bda.removeDownloads(sender, args.optJSONObject(0) ?: JSONObject())
                    api == "browsingData.removeCookies" -> bda.removeCookies(sender, args.optJSONObject(0) ?: JSONObject())
                    api == "browsingData.removeBookmarks" -> bda.removeBookmarks(sender, args.optJSONObject(0) ?: JSONObject())
                    api == "browsingData.removeCache" -> bda.removeCache(sender, args.optJSONObject(0) ?: JSONObject())
                    api == "browsingData.settings" -> bda.settings(sender)
                    else -> throw IllegalArgumentException("Unsupported browsingData API: $api")
                }
            }

            // Android power API: uses the canonical WakeLock-backed adapter.
            api.startsWith("power.") -> {
                verifyApiPermission(extId, "power")
                when {
                    api.endsWith(".requestKeepAwake") -> resolvedPowerAdapter.requestKeepAwake(
                        sender, args.optString(0, "")
                    )
                    api.endsWith(".releaseKeepAwake") -> resolvedPowerAdapter.releaseKeepAwake(sender)
                    else -> throw IllegalArgumentException("Unsupported power API: $api")
                }
            }

            // Chrome Debugger API
            api.startsWith("debugger.") -> {
                resolvedDebuggerAdapter.handleBridgeCall(sender, api, args)
            }

            // APIs that require browser/OS services which Orion does not expose to the
            // extension runtime are deliberately rejected. Never fabricate tokens, stream IDs,
            // debugger targets, or success objects: a rejected promise/callback with a real
            // runtime error is safer and closer to Chrome's runtime.lastError contract.
            api.startsWith("desktopCapture.") ||
            api.startsWith("devtools.") ||
            api.startsWith("gcm.") ||
            api.startsWith("instanceID.") ||
            api.startsWith("fontSettings.") ||
            api.startsWith("wallpaper.") ||
            api.startsWith("proxy.") ||
            api.startsWith("vpnProvider.") -> {
                throw UnsupportedOperationException("Chrome API '$api' has no native Orion backend")
            }

            // Extension / Permissions Queries
            api == "extension.isAllowedIncognitoAccess" -> permissionAdapter.isAllowedInPrivate(extId)
            api == "extension.isAllowedFileSchemeAccess" -> {
                val ext = registry.getExtension(extId)
                if (ext != null) {
                    permissionAdapter.hasHostPermission(extId, "file://*")
                } else false
            }

            else -> {
                throw UnsupportedOperationException("Chrome API '$api' is not implemented by the canonical Orion adapter graph")
            }
        }
    }

    private fun buildCanonicalExtensionUrl(extId: String, rawPath: String?): String {
        if (rawPath.isNullOrBlank()) return "chrome-extension://$extId/"
        val trimmed = rawPath.trim()
        if (trimmed.startsWith("chrome-extension://$extId/")) return trimmed
        if (trimmed.startsWith("chrome-extension://")) return trimmed

        val queryHashIdx = trimmed.indexOfFirst { it == '?' || it == '#' }
        val (basePath, suffix) = if (queryHashIdx != -1) {
            Pair(trimmed.substring(0, queryHashIdx), trimmed.substring(queryHashIdx))
        } else {
            Pair(trimmed, "")
        }

        val segments = basePath.split('/').filter { it.isNotEmpty() && it != "." }
        val normalizedStack = mutableListOf<String>()
        for (segment in segments) {
            if (segment == "..") {
                if (normalizedStack.isNotEmpty()) {
                    normalizedStack.removeAt(normalizedStack.size - 1)
                }
            } else {
                normalizedStack.add(segment)
            }
        }
        val cleanPath = normalizedStack.joinToString("/")
        return "chrome-extension://$extId/$cleanPath$suffix"
    }
}
