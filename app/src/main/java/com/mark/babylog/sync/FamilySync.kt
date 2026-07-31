package com.mark.babylog.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import com.mark.babylog.BabyLogApp
import com.mark.babylog.data.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

data class FamilySyncStatus(
    val running: Boolean = false,
    val error: String? = null,
    val lastSyncedAt: Long? = null,
)

data class FamilyMember(val id: String, val displayName: String)

class FamilySync(private val app: BabyLogApp) {
    val status = MutableStateFlow(FamilySyncStatus())
    val members = MutableStateFlow<List<FamilyMember>>(emptyList())
    private val available
        get() = FirebaseApp.getApps(app).isNotEmpty()

    private val listeners = mutableListOf<ListenerRegistration>()
    private val dao
        get() = app.database.events()

    private val revisionKey = "family_revision"

    suspend fun createFamily(name: String): String {
        require(available) { "Добавьте app/google-services.json" }
        val uid = auth()
        val result =
            FirebaseFunctions.getInstance()
                .getHttpsCallable("createHousehold")
                .call(mapOf("displayName" to name.trim()))
                .await()
                .getData() as Map<*, *>
        val family =
            FamilyMembership(
                householdId = result["householdId"] as String,
                memberId = uid,
                displayName = name.trim(),
                inviteCode = result["inviteCode"] as? String,
            )
        dao.putMembership(family)
        dao.putMetadata(SyncMetadata(revisionKey, ""))
        app.repository.attachToFamily()
        app.reminderRepository.attachToFamily()
        registerCurrentToken()
        startRealtime()
        schedule()
        return family.inviteCode.orEmpty()
    }

    suspend fun joinFamily(code: String, name: String) {
        require(available) { "Добавьте app/google-services.json" }
        val uid = auth()
        val result =
            FirebaseFunctions.getInstance()
                .getHttpsCallable("joinHousehold")
                .call(mapOf("code" to code.trim().uppercase(), "displayName" to name.trim()))
                .await()
                .getData() as Map<*, *>
        dao.putMembership(
            FamilyMembership(
                householdId = result["householdId"] as String,
                memberId = uid,
                displayName = name.trim(),
            )
        )
        dao.putMetadata(SyncMetadata(revisionKey, ""))
        registerCurrentToken()
        pull()
        app.repository.attachToFamily()
        app.reminderRepository.attachToFamily()
        startRealtime()
        schedule()
    }

    suspend fun createInvite(): String {
        val result =
            FirebaseFunctions.getInstance()
                .getHttpsCallable("createInvite")
                .call()
                .await()
                .getData() as Map<*, *>
        val code = result["inviteCode"] as String
        dao.membership()?.let { dao.putMembership(it.copy(inviteCode = code)) }
        return code
    }

    private suspend fun auth(): String {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) auth.signInAnonymously().await()
        return requireNotNull(auth.currentUser).uid
    }

    private suspend fun registerCurrentToken() {
        val token = FirebaseMessaging.getInstance().token.await()
        val householdId = dao.membership()?.householdId ?: return
        val preferences = app.getSharedPreferences("family_sync", Context.MODE_PRIVATE)
        if (
            preferences.getString("registered_token", null) == token &&
                preferences.getString("registered_household", null) == householdId
        )
            return
        FirebaseFunctions.getInstance()
            .getHttpsCallable("registerDevice")
            .call(mapOf("token" to token))
            .await()
        preferences
            .edit()
            .putString("registered_token", token)
            .putString("registered_household", householdId)
            .apply()
    }

    fun invalidateRegisteredToken() {
        app.getSharedPreferences("family_sync", Context.MODE_PRIVATE)
            .edit()
            .remove("registered_token")
            .remove("registered_household")
            .apply()
    }

    suspend fun sync(): Boolean {
        if (!available || dao.membership() == null) return true
        status.value = FamilySyncStatus(running = true)
        return try {
            auth()
            registerCurrentToken()
            while (true) {
                val pending = dao.pending()
                if (pending.isEmpty()) break
                pushBatch(pending)
            }
            pull()
            status.value = FamilySyncStatus(lastSyncedAt = System.currentTimeMillis())
            true
        } catch (error: Exception) {
            Log.w("BabyLog", "Family sync failed", error)
            status.value = FamilySyncStatus(error = error.message ?: "Ошибка синхронизации")
            false
        }
    }

    private suspend fun pushBatch(operations: List<SyncOperation>) {
        val commands =
            operations.map { op ->
                mapOf(
                    "commandId" to op.id,
                    "command" to op.command,
                    "payload" to jsonMap(JSONObject(op.payload)),
                    "occurredAt" to op.occurredAt,
                )
            }
        try {
            FirebaseFunctions.getInstance()
                .getHttpsCallable("processCommandsV2")
                .call(mapOf("commands" to commands))
                .await()
            operations.forEach { dao.removeOperation(it.id) }
        } catch (batchError: Exception) {
            // The backend is deployed before the app, but keeping this fallback
            // also lets an update drain legacy outbox rows during rollout.
            operations.forEach { op ->
                try {
                    FirebaseFunctions.getInstance()
                        .getHttpsCallable("processCommand")
                        .call(
                            mapOf(
                                "commandId" to op.id,
                                "command" to op.command,
                                "payload" to jsonMap(JSONObject(op.payload)),
                                "occurredAt" to op.occurredAt,
                            )
                        )
                        .await()
                    dao.removeOperation(op.id)
                } catch (error: Exception) {
                    dao.failOperation(op.id, error.message ?: "Ошибка синхронизации")
                    error.addSuppressed(batchError)
                    throw error
                }
            }
        }
    }

    suspend fun pull() {
        val owner = dao.membership() ?: return
        val family =
            FirebaseFirestore.getInstance().collection("households").document(owner.householdId)
        val cursor = dao.metadata(revisionKey)?.toLongOrNull()
        val targetRevision = family.get().await().getLong("revision")
        if (cursor != null && targetRevision != null && targetRevision <= cursor) return
        val (eventSnapshot, reminderSnapshot, completionSnapshot) =
            coroutineScope {
                suspend fun changes(collection: String) =
                    if (cursor == null || targetRevision == null)
                        family.collection(collection).get().await()
                    else
                        family
                            .collection(collection)
                            .whereGreaterThan("revision", cursor)
                            .whereLessThanOrEqualTo("revision", targetRevision)
                            .get()
                            .await()
                val events = async { changes("events") }
                val reminders = async { changes("reminders") }
                val completions = async { changes("reminderCompletions") }
                Triple(events.await(), reminders.await(), completions.await())
            }
        app.repository.applyRemote(eventSnapshot.documents.mapNotNull { it.data })
        app.reminderRepository.applyRemoteReminders(
            reminderSnapshot.documents.mapNotNull { it.data }
        )
        app.reminderRepository.applyRemoteCompletions(
            completionSnapshot.documents.mapNotNull { it.data }
        )
        // Households created by the legacy backend have no revision field.
        // Keep them on full sync until the first command processed by v2
        // establishes a cursor.
        dao.putMetadata(SyncMetadata(revisionKey, targetRevision?.toString().orEmpty()))
        AppSurfaceSync.refresh(app)
    }

    suspend fun startRealtime() {
        if (!available) return
        val owner = dao.membership() ?: return
        listeners.forEach { it.remove() }
        listeners.clear()
        val family =
            FirebaseFirestore.getInstance().collection("households").document(owner.householdId)
        val cursor = dao.metadata(revisionKey)?.toLongOrNull()
        fun changes(collection: String) =
            cursor?.let { family.collection(collection).whereGreaterThan("revision", it) }
                ?: family.collection(collection)
        listeners +=
            changes("events").addSnapshotListener { snapshot, error ->
                if (error != null) {
                    status.value = FamilySyncStatus(error = error.message)
                    return@addSnapshotListener
                }
                snapshot ?: return@addSnapshotListener
                app.appScope.launch {
                    try {
                        app.repository.applyRemote(
                            snapshot.documentChanges.map { it.document.data }
                        )
                        AppSurfaceSync.refresh(app)
                    } catch (error: Throwable) {
                        status.value =
                            FamilySyncStatus(
                                error = error.message ?: "Ошибка realtime-синхронизации"
                            )
                    }
                }
            }
        listeners +=
            changes("reminders").addSnapshotListener { snapshot, error ->
                if (error != null) {
                    status.value = FamilySyncStatus(error = error.message)
                    return@addSnapshotListener
                }
                snapshot ?: return@addSnapshotListener
                app.appScope.launch {
                    try {
                        app.reminderRepository.applyRemoteReminders(
                            snapshot.documentChanges.map { it.document.data }
                        )
                    } catch (error: Throwable) {
                        status.value =
                            FamilySyncStatus(
                                error = error.message ?: "Ошибка синхронизации напоминаний"
                            )
                    }
                }
            }
        listeners +=
            changes("reminderCompletions").addSnapshotListener { snapshot, error ->
                if (error != null) {
                    status.value = FamilySyncStatus(error = error.message)
                    return@addSnapshotListener
                }
                snapshot ?: return@addSnapshotListener
                app.appScope.launch {
                    try {
                        app.reminderRepository.applyRemoteCompletions(
                            snapshot.documentChanges.map { it.document.data }
                        )
                    } catch (error: Throwable) {
                        status.value =
                            FamilySyncStatus(
                                error = error.message ?: "Ошибка синхронизации выполненного"
                            )
                    }
                }
            }
        listeners +=
            family.collection("members").addSnapshotListener { snapshot, _ ->
                members.value =
                    snapshot
                        ?.documents
                        ?.map { FamilyMember(it.id, it.getString("displayName").orEmpty()) }
                        .orEmpty()
            }
    }

    fun schedule() {
        SyncWorker.enqueue(app)
    }

    private fun jsonMap(value: JSONObject): Map<String, Any?> =
        value.keys().asSequence().associateWith { key ->
            when (val v = value.get(key)) {
                is JSONObject -> jsonMap(v)
                is org.json.JSONArray ->
                    (0 until v.length()).map { i ->
                        val item = v.get(i)
                        if (item is JSONObject) jsonMap(item) else item
                    }
                JSONObject.NULL -> null
                else -> v
            }
        }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if ((applicationContext as BabyLogApp).familySync.sync()) Result.success()
        else Result.retry()

    companion object {
        fun enqueue(context: Context) {
            val constraints =
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request =
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("babylog-sync", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun periodic(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "babylog-periodic-sync",
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build()
                        )
                        .build(),
                )
        }
    }
}

class BabyLogMessagingService : com.google.firebase.messaging.FirebaseMessagingService() {
    override fun onMessageReceived(message: com.google.firebase.messaging.RemoteMessage) {
        SyncWorker.enqueue(this)
    }

    override fun onDeletedMessages() {
        SyncWorker.enqueue(this)
    }

    override fun onNewToken(token: String) {
        (applicationContext as BabyLogApp).familySync.invalidateRegisteredToken()
        SyncWorker.enqueue(this)
    }
}
