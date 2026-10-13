package com.swift.browser.extensionengine
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
/** MV3 lifecycle is implemented by the Native-V8 service-worker pipeline. */
const val PARTIAL_SERVICE_WORKER_SUPPORT = false
const val WORKER_RUNTIME_CAPABILITY = "NATIVE_V8_SERVICE_WORKER_RUNTIME"
enum class ServiceWorkerState { UNREGISTERED, REGISTERED, STARTING, RUNNING, IDLE, STOPPING, STOPPED, SUSPENDING, SUSPENDED, CRASHED, RESTARTING, RESTART_PENDING, DISABLED, UNINSTALLED, FAILED, NOT_REGISTERED, DORMANT, WAKE, EVENT, ACTIVE, SUSPEND;
 fun canTransitionTo(t: ServiceWorkerState)= when(this){
  UNREGISTERED->t==REGISTERED||t==NOT_REGISTERED; NOT_REGISTERED->t==REGISTERED||t==UNREGISTERED
  REGISTERED->t in setOf(STARTING,STOPPING,STOPPED,DISABLED,UNINSTALLED,DORMANT,WAKE,SUSPEND,RUNNING,SUSPENDED)
  STARTING->t in setOf(RUNNING,STOPPED,STOPPING,CRASHED,FAILED,ACTIVE,EVENT,SUSPENDED,DORMANT)
  RUNNING->t in setOf(IDLE,STOPPING,SUSPENDING,CRASHED,STOPPED,ACTIVE,EVENT,SUSPENDED,DISABLED,UNINSTALLED,FAILED)
  IDLE->t in setOf(SUSPENDING,STOPPING,RUNNING,STARTING,STOPPED,ACTIVE,EVENT,SUSPEND,DORMANT,SUSPENDED,DISABLED)
  STOPPING->t in setOf(RUNNING,IDLE,STOPPED,SUSPENDED,DISABLED,UNINSTALLED,CRASHED)
  STOPPED->t in setOf(STARTING,STOPPING,REGISTERED,DISABLED,UNINSTALLED,DORMANT)
  SUSPENDING->t in setOf(SUSPENDED,RUNNING,STARTING,STOPPED,DORMANT)
  SUSPENDED->t in setOf(STARTING,STOPPING,REGISTERED,STOPPED,DISABLED,UNINSTALLED,DORMANT,WAKE)
  CRASHED->t in setOf(RESTARTING,RESTART_PENDING,STOPPING,STOPPED,FAILED,DISABLED,UNINSTALLED,SUSPENDED,DORMANT)
  RESTARTING,RESTART_PENDING->t in setOf(STARTING,STOPPED,FAILED,CRASHED,SUSPENDED,DORMANT)
  DISABLED->t in setOf(STOPPED,REGISTERED,UNINSTALLED,DORMANT,SUSPENDED); UNINSTALLED->t in setOf(STOPPED,NOT_REGISTERED)
  FAILED->t in setOf(STARTING,STOPPING,STOPPED,REGISTERED,DISABLED,UNINSTALLED)
  DORMANT->t in setOf(WAKE,SUSPEND,REGISTERED,STARTING,ACTIVE,STOPPED,RUNNING,IDLE,SUSPENDED)
  WAKE->t in setOf(EVENT,ACTIVE,DORMANT,SUSPEND,RUNNING,STARTING); EVENT->t in setOf(ACTIVE,IDLE,SUSPEND,DORMANT,RUNNING)
  ACTIVE->t in setOf(IDLE,EVENT,SUSPEND,DORMANT,RUNNING,SUSPENDED); SUSPEND->t in setOf(DORMANT,WAKE,REGISTERED,SUSPENDED,IDLE)
  else->false }
}
enum class WorkerStartupResult { SUCCESS, FAILED, TIMEOUT }
enum class WorkerEventDeliveryState { QUEUED, DELIVERING, DELIVERED, FAILED, EXPIRED }
enum class ServiceWorkerRequestState { PENDING, COMPLETED, FAILED, EXPIRED, CANCELLED }
data class ServiceWorkerRequestRecord(val requestId:String,val extensionId:String,val workerGeneration:Int,val createdAt:Long=System.currentTimeMillis(),val timeoutAt:Long,val owner:String,var state:ServiceWorkerRequestState=ServiceWorkerRequestState.PENDING)
data class KeepaliveReason(val reasonType:String,val handle:String,val timestamp:Long=System.currentTimeMillis(),val owner:String="service-worker",val requestId:String?=null,val eventId:String?=null)
data class ServiceWorkerExecutionContext(val extensionId:String,val manifestVersion:Int=3,val workerGenerationId:Int=1,val registrationId:String=UUID.randomUUID().toString(),val startTime:Long=System.currentTimeMillis(),val wakeReason:String="INITIAL_REGISTRATION",val isPrivate:Boolean=false,val privateSessionId:String?=null,val enabled:Boolean=true,var state:ServiceWorkerState=ServiceWorkerState.REGISTERED)
data class ServiceWorkerRegistration(val extensionId:String,val scriptPath:String,var state:ServiceWorkerState=ServiceWorkerState.REGISTERED,var lastActiveTimestamp:Long=System.currentTimeMillis(),val isMV3:Boolean=true,val pendingEvents:MutableList<QueuedServiceWorkerEvent> = CopyOnWriteArrayList(),var type:String="classic",var enabled:Boolean=true,var privateAllowed:Boolean=false,var workerGenerationId:Int=1,var registeredAt:Long=System.currentTimeMillis(),var lastStartAt:Long=0L,var lastStopAt:Long=0L,var lastEventAt:Long=0L,var crashCount:Int=0,var restartCount:Int=0,var wakeReason:String="",val activeCallbacks:MutableSet<String> = ConcurrentHashMap.newKeySet(),val keepaliveReasons:MutableMap<String,KeepaliveReason> = ConcurrentHashMap(),val pendingRequests:MutableMap<String,ServiceWorkerRequestRecord> = ConcurrentHashMap(),var lastCrashAt:Long=0L,var retryNotBefore:Long=0L,var installEventKey:String?=null,
var lastWakeId:String?=null,var lastWakeAt:Long=0L,var lastSuspendAt:Long=0L,
var nativeRuntimeReady:Boolean=false,var executionContext:ServiceWorkerExecutionContext?=null){ val keepaliveCount get()=keepaliveReasons.size; fun addKeepalive(t:String,h:String,owner:String="service-worker",requestId:String?=null,eventId:String?=null){keepaliveReasons[h]=KeepaliveReason(t,h,System.currentTimeMillis(),owner,requestId,eventId);lastActiveTimestamp=System.currentTimeMillis()};fun removeKeepalive(h:String){keepaliveReasons.remove(h);lastActiveTimestamp=System.currentTimeMillis()};fun hasActiveKeepalives()=keepaliveReasons.isNotEmpty();fun terminateRuntime(reason:String){pendingRequests.values.forEach{it.state=ServiceWorkerRequestState.FAILED};pendingRequests.clear();activeCallbacks.clear();keepaliveReasons.clear();executionContext?.state=if(reason=="WORKER_CRASHED")ServiceWorkerState.CRASHED else ServiceWorkerState.STOPPED}}
data class QueuedServiceWorkerEvent(val eventId:String,val eventName:String,val payload:Any?,val timestamp:Long=System.currentTimeMillis(),var deliveryState:WorkerEventDeliveryState=WorkerEventDeliveryState.QUEUED,var generationId:Int=0,var deliveryAttempts:Int=0,var extensionId:String="",val sourceContext:String="browser",val keepaliveRequirement:String?=null)
