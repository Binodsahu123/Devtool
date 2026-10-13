package com.swift.browser.extensionengine

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Durable, transport-neutral storage.sync journal + deterministic conflict clock. */
class ExtensionStorageSyncJournal(context: Context) {
    data class SyncDelta(val extensionId:String,val deviceId:String,val firstRevision:Long,val lastRevision:Long,val changes:JSONArray){
        fun toJson():JSONObject=JSONObject().apply{put("extensionId",extensionId);put("deviceId",deviceId);put("firstRevision",firstRevision);put("lastRevision",lastRevision);put("changes",changes)}
    }
    data class SyncStatus(val uploadState:String,val downloadState:String,val retryCount:Int,val lastError:String?,val remoteRevision:Long)

    private val prefs:SharedPreferences=context.applicationContext.getSharedPreferences(PREFS_NAME,Context.MODE_PRIVATE)
    private val locks=ConcurrentHashMap<String,Any>()
    private val deviceId:String by lazy{prefs.getString(KEY_DEVICE_ID,null)?.takeIf{it.isNotBlank()}?:UUID.randomUUID().toString().also{prefs.edit().putString(KEY_DEVICE_ID,it).apply()}}
    fun deviceId():String=deviceId

    fun record(extensionId:String,area:String,changes:JSONObject){
        if(extensionId.isBlank()||area!="sync"||changes.length()==0)return
        synchronized(locks.getOrPut(extensionId.trim().lowercase()){Any()}){
            val (revision,arr)=readState(extensionId);var next=revision;val now=System.currentTimeMillis();val edit=prefs.edit()
            val clocks=readClocks(extensionId)
            for(key in changes.keys()){
                next++;val c=changes.optJSONObject(key)?:JSONObject();val deleted=!c.has("newValue")||c.isNull("newValue")
                val item=JSONObject().apply{put("revision",next);put("timestamp",now);put("deviceId",deviceId);put("key",key);put("deleted",deleted);put("value",if(deleted)JSONObject.NULL else c.opt("newValue"))}
                arr.put(item);clocks[key]=JSONObject().put("timestamp",now).put("deviceId",deviceId).put("revision",next)
            }
            compact(arr);writeState(edit,extensionId,next,arr);writeClocks(edit,extensionId,clocks);edit.apply()
        }
    }

    fun export(extensionId:String,afterRevision:Long=0L,maxItems:Int=MAX_DELTA_ITEMS):SyncDelta{
        val (_,arr)=readState(extensionId);val selected=JSONArray();var first=0L;var last=afterRevision
        for(i in 0 until arr.length()){val item=arr.optJSONObject(i)?:continue;val rev=item.optLong("revision",0);if(rev<=afterRevision)continue;if(selected.length()>=maxItems.coerceIn(1,MAX_DELTA_ITEMS))break;if(first==0L)first=rev;last=rev;selected.put(item)}
        return SyncDelta(extensionId,deviceId,first,last,selected)
    }

    fun acknowledge(extensionId:String,throughRevision:Long){if(throughRevision<=0)return;synchronized(locks.getOrPut(extensionId.trim().lowercase()){Any()}){val(state,arr)=readState(extensionId);val kept=JSONArray();for(i in 0 until arr.length()){val item=arr.optJSONObject(i)?:continue;if(item.optLong("revision",0)>throughRevision)kept.put(item)};val edit=prefs.edit();writeState(edit,extensionId,state.coerceAtLeast(throughRevision),kept);edit.apply()}}

    fun mergeRemote(extensionId:String,changes:JSONArray):JSONArray{
        val winners=JSONArray();if(changes.length()==0)return winners
        synchronized(locks.getOrPut(extensionId.trim().lowercase()){Any()}){
            val clocks=readClocks(extensionId);val edit=prefs.edit()
            for(i in 0 until changes.length()){
                val remote=changes.optJSONObject(i)?:continue;val key=remote.optString("key","");if(key.isBlank())continue
                val candidate=JSONObject().put("timestamp",remote.optLong("timestamp",0)).put("deviceId",remote.optString("deviceId","")).put("revision",remote.optLong("revision",0))
                val current=clocks[key]
                if(current==null||compareClock(candidate,current)>0){clocks[key]=candidate;winners.put(remote)}
            }
            writeClocks(edit,extensionId,clocks);edit.apply()
        }
        return winners
    }

    fun recordUploadAttempt(extensionId:String){val s=readStatus(extensionId);writeStatus(extensionId,s.copy(uploadState="uploading",retryCount=s.retryCount+1,lastError=null))}
    fun recordUploadSuccess(extensionId:String,remoteRevision:Long){val s=readStatus(extensionId);writeStatus(extensionId,s.copy(uploadState="idle",retryCount=0,lastError=null,remoteRevision=maxOf(s.remoteRevision,remoteRevision)))}
    fun recordUploadFailure(extensionId:String,error:String){val s=readStatus(extensionId);writeStatus(extensionId,s.copy(uploadState="failed",lastError=error))}
    fun recordDownloadSuccess(extensionId:String,remoteRevision:Long){val s=readStatus(extensionId);writeStatus(extensionId,s.copy(downloadState="idle",lastError=null,remoteRevision=maxOf(s.remoteRevision,remoteRevision)))}
    fun recordDownloadFailure(extensionId:String,error:String){val s=readStatus(extensionId);writeStatus(extensionId,s.copy(downloadState="failed",lastError=error))}
    fun status(extensionId:String):SyncStatus=readStatus(extensionId)

    fun pendingCount(extensionId:String):Int=readState(extensionId).second.length()
    fun clear(extensionId:String){prefs.edit().remove("$KEY_REVISION_PREFIX$extensionId").remove("$KEY_CHANGES_PREFIX$extensionId").remove("$KEY_CLOCKS_PREFIX$extensionId").remove("$KEY_STATUS_PREFIX$extensionId").apply()}

    private fun compareClock(a:JSONObject,b:JSONObject):Int{val ta=a.optLong("timestamp",0);val tb=b.optLong("timestamp",0);if(ta!=tb)return ta.compareTo(tb);val da=a.optString("deviceId","");val db=b.optString("deviceId","");if(da!=db)return da.compareTo(db);return a.optLong("revision",0).compareTo(b.optLong("revision",0))}
    private fun readClocks(extensionId:String):MutableMap<String,JSONObject>{val raw=prefs.getString("$KEY_CLOCKS_PREFIX$extensionId","{}") ?: "{}";val o=runCatching{JSONObject(raw)}.getOrDefault(JSONObject());return mutableMapOf<String,JSONObject>().apply{o.keys().forEach{key->o.optJSONObject(key)?.let{put(key,it)}}}}
    private fun writeClocks(edit:SharedPreferences.Editor,extensionId:String,clocks:Map<String,JSONObject>){val o=JSONObject();clocks.forEach{(k,v)->o.put(k,v)};edit.putString("$KEY_CLOCKS_PREFIX$extensionId",o.toString())}
    private fun readState(extensionId:String):Pair<Long,JSONArray>{val rev=prefs.getLong("$KEY_REVISION_PREFIX$extensionId",0);val raw=prefs.getString("$KEY_CHANGES_PREFIX$extensionId","[]")?:"[]";return rev to runCatching{JSONArray(raw)}.getOrDefault(JSONArray())}
    private fun writeState(edit:SharedPreferences.Editor,extensionId:String,revision:Long,changes:JSONArray){edit.putLong("$KEY_REVISION_PREFIX$extensionId",revision).putString("$KEY_CHANGES_PREFIX$extensionId",changes.toString())}
    private fun writeStatus(extensionId:String,s:SyncStatus){prefs.edit().putString("$KEY_STATUS_PREFIX$extensionId",JSONObject().put("uploadState",s.uploadState).put("downloadState",s.downloadState).put("retryCount",s.retryCount).put("lastError",s.lastError?:JSONObject.NULL).put("remoteRevision",s.remoteRevision).toString()).apply()}
    private fun readStatus(extensionId:String):SyncStatus{val o=runCatching{JSONObject(prefs.getString("$KEY_STATUS_PREFIX$extensionId","{}")?:"{}")} .getOrDefault(JSONObject());return SyncStatus(o.optString("uploadState","idle"),o.optString("downloadState","idle"),o.optInt("retryCount",0),if(o.isNull("lastError"))null else o.optString("lastError","").ifBlank{null},o.optLong("remoteRevision",0))}
    private fun compact(arr:JSONArray){while(arr.length()>MAX_PENDING_ITEMS)arr.remove(0)}

    companion object{private const val PREFS_NAME="swift_extension_storage_sync_journal";private const val KEY_DEVICE_ID="device_id";private const val KEY_REVISION_PREFIX="revision:";private const val KEY_CHANGES_PREFIX="changes:";private const val KEY_CLOCKS_PREFIX="clocks:";private const val KEY_STATUS_PREFIX="status:";private const val MAX_PENDING_ITEMS=4096;private const val MAX_DELTA_ITEMS=512}
}
