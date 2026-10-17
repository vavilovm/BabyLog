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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

data class FamilySyncStatus(
    val running: Boolean = false,
    val error: String? = null,
    val lastSyncedAt: Long? = null,
)

data class FamilyMember(val id: String, val displayName: String)

class FamilySync(private val app: BabyLogApp) {
    private val preferences = app.getSharedPreferences("family_sync", Context.MODE_PRIVATE)
    val status =
        MutableStateFlow(
            FamilySyncStatus(
                lastSyncedAt = preferences.getLong("last_successful_sync", 0).takeIf { it > 0 }
            )
        )
    val members = MutableStateFlow<List<FamilyMember>>(emptyList())
    private val available
        get() = FirebaseApp.getApps(app).isNotEmpty()

    private val listeners = mutableListOf<ListenerRegistration>()
    private val dao
        get() = app.database.events()

    private val syncMutex = Mutex()
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
        pull(adoptSharedProfile = true)
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

    suspend fun sync(): Boolean =
        syncMutex.withLock {
            if (!available || dao.membership() == null) return@withLock true
            runFamilySync(status) {
                auth()
                registerCurrentToken()
                while (true) {
                    val pending = dao.pending()
                    if (pending.isEmpty()) break
                    pushBatch(pending)
                }
                pull()
                preferences
                    .edit()
                    .putLong("last_successful_sync", System.currentTimeMillis())
                    .apply()
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
        pushWithLegacyFallback(
            operations,
            batch = {
                FirebaseFunctions.getInstance()
                    .getHttpsCallable("processCommandsV2")
                    .call(mapOf("commands" to commands))
                    .await()
                operations.forEach { dao.removeOperation(it.id) }
            },
            legacy = { op ->
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
            },
            failed = { op, error ->
                dao.failOperation(op.id, error.message ?: "Ошибка синхронизации")
            },
        )
    }

    suspend fun pull(adoptSharedProfile: Boolean = false) {
        val owner = dao.membership() ?: return
        val family =
            FirebaseFirestore.getInstance().collection("households").document(owner.householdId)
        val cursor = dao.metadata(revisionKey)?.toLongOrNull()
        val profileSnapshot = family.collection("profiles").document("baby").get().await()
        profileSnapshot.data?.let {
            app.repository.applyProfile(it, adoptShared = adoptSharedProfile)
        }
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
            family.collection("profiles").document("baby").addSnapshotListener { snapshot, _ ->
                snapshot?.data?.let { raw ->
                    app.appScope.launch { app.repository.applyProfile(raw) }
                }
            }
        listeners +=
            changes("events").addSnapshotListener { snapshot, error ->
                if (error != null) {
                    status.value = status.value.copy(error = "Не удалось синхронизировать")
                    return@addSnapshotListener
                }
                snapshot ?: return@addSnapshotListener
                app.appScope.launch {
                    try {
                        app.repository.applyRemote(
                            snapshot.documentChanges.map { it.document.data }
                        )
                        AppSurfaceSync.refresh(app)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        status.value = status.value.copy(error = "Не удалось синхронизировать")
                    }
                }
            }
        listeners +=
            changes("reminders").addSnapshotListener { snapshot, error ->
                if (error != null) {
                    status.value = status.value.copy(error = "Не удалось синхронизировать")
                    return@addSnapshotListener
                }
                snapshot ?: return@addSnapshotListener
                app.appScope.launch {
                    try {
                        app.reminderRepository.applyRemoteReminders(
                            snapshot.documentChanges.map { it.document.data }
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        status.value = status.value.copy(error = "Не удалось синхронизировать")
                    }
                }
            }
        listeners +=
            changes("reminderCompletions").addSnapshotListener { snapshot, error ->
                if (error != null) {
                    status.value = status.value.copy(error = "Не удалось синхронизировать")
                    return@addSnapshotListener
                }
                snapshot ?: return@addSnapshotListener
                app.appScope.launch {
                    try {
                        app.reminderRepository.applyRemoteCompletions(
                            snapshot.documentChanges.map { it.document.data }
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        status.value = status.value.copy(error = "Не удалось синхронизировать")
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
            // Ordinary queued work also runs on pre-Android 12 without a foreground service.
            val request =
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
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

internal suspend fun runFamilySync(
    status: MutableStateFlow<FamilySyncStatus>,
    now: () -> Long = System::currentTimeMillis,
    work: suspend () -> Unit,
): Boolean {
    status.value = status.value.copy(running = true, error = null)
    return try {
        work()
        status.value = FamilySyncStatus(lastSyncedAt = now())
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w("BabyLog", "Family sync failed", error)
        status.value = status.value.copy(error = "Не удалось синхронизировать")
        false
    } finally {
        status.value = status.value.copy(running = false)
    }
}

internal suspend fun <T> pushWithLegacyFallback(
    operations: List<T>,
    batch: suspend () -> Unit,
    legacy: suspend (T) -> Unit,
    failed: suspend (T, Exception) -> Unit,
) {
    try {
        batch()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (batchError: Exception) {
        // Retain compatibility with the legacy endpoint during rollout.
        operations.forEach { op ->
            try {
                legacy(op)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed(op, error)
                if (error !== batchError) error.addSuppressed(batchError)
                throw error
            }
        }
    }
}

internal suspend fun <T> familyResult(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
