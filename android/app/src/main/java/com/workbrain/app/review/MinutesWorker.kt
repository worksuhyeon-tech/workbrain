package com.workbrain.app.review

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.workbrain.app.Supa
import com.workbrain.app.data.Server
import com.workbrain.app.record.ResultNotifier
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 회의록 만들기(FR-30, DEC-56)를 백그라운드에서 한다. 1~2분 걸려서 화면을 나가도 '만드는 중'이 유지되고,
 * 끝나면 할 일 후보처럼 알림으로 알린다. 같은 기록은 한 번에 하나만 돈다(두 번 눌러도 비용이 두 번 들지 않게).
 */
class MinutesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val recordId = inputData.getString(RECORD_ID) ?: return Result.failure()
        val title = inputData.getString(TITLE).orEmpty()
        Supa.client.auth.awaitInitialization()
        // 실패해도 다시 시도하지 않는다: 서버가 이미 만들었을 수 있고, 다시 만들면 비용이 또 든다. 사용자가 다시 누른다
        val error = runCatching { Server.makeMinutes(recordId) }.getOrElse { "회의록을 만들지 못했어요. 인터넷 연결을 확인해 주세요" }
        val id = ("minutes-$recordId").hashCode()
        return if (error == null) {
            ResultNotifier.notify(applicationContext, id, "회의록이 만들어졌어요", title)
            Result.success()
        } else {
            ResultNotifier.notify(applicationContext, id, "회의록을 만들지 못했어요", title)
            Result.failure(workDataOf(ERROR to error))
        }
    }

    /** 화면에 보일 상태: 만드는 중 / 방금 끝남(성공·실패 문구) */
    sealed interface State {
        data object Idle : State
        data object Making : State
        data object Done : State
        data class Failed(val message: String) : State
    }

    companion object {
        private const val RECORD_ID = "record_id"
        private const val TITLE = "title"
        private const val ERROR = "error"

        private fun workName(recordId: String) = "minutes-$recordId"

        fun start(context: Context, recordId: String, title: String) {
            val request = OneTimeWorkRequestBuilder<MinutesWorker>()
                .setInputData(workDataOf(RECORD_ID to recordId, TITLE to title))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(recordId), ExistingWorkPolicy.KEEP, request)
        }

        fun state(context: Context, recordId: String): Flow<State> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(workName(recordId)).map { infos ->
                val info = infos.lastOrNull() ?: return@map State.Idle
                when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> State.Making
                    WorkInfo.State.SUCCEEDED -> State.Done
                    WorkInfo.State.FAILED -> State.Failed(info.outputData.getString(ERROR) ?: "회의록을 만들지 못했어요")
                    WorkInfo.State.CANCELLED -> State.Idle
                }
            }
    }
}
