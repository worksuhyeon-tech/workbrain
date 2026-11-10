package com.workbrain.app.projects

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
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
import com.workbrain.app.data.LinkedRecord
import com.workbrain.app.data.Server
import com.workbrain.app.review.ReviewBadge
import io.github.jan.supabase.auth.auth
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 프로젝트 현재 상태 제안(FR-09, DEC-86)을 백그라운드에서 만든다. 기록을 프로젝트에 연결(확정)한 뒤, 또는 빈 카드의
 * [연결된 기록으로 정리하기]에서 부른다. 회의 하나에 몇 초~수십 초 걸려 화면을 나가도 이어지고, 끝나면 카드가 다시 읽는다.
 * 알림은 띄우지 않는다(제안은 프로젝트 상세·검토함에 조용히 쌓임).
 *
 * 같은 프로젝트는 차례로 돈다(대기 제안을 고쳐 쓰므로 앞 호출이 끝나야 다음 회의를 합칠 수 있음).
 * 서버가 이미 반영한 기록은 건너뛰어(ALREADY) 다시 돌아도 비용이 두 번 들지 않는다. 통신이 끊기면 나중에 다시 한다
 */
class StateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val projectId = inputData.getString(PROJECT_ID) ?: return Result.failure()
        val records = inputData.getStringArray(RECORD_IDS).orEmpty()
        Supa.client.auth.awaitInitialization()
        for (recordId in records) {
            val state = try {
                Server.projectState(recordId, projectId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "project_state call failed: ${e.message}")
                return if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
            }
            Log.i(TAG, "project_state $state")
            // 예산 대기·키 없음은 다시 해도 같다. 일시 오류(FAILED)는 나중에 처음부터 다시(반영한 기록은 서버가 건너뜀)
            when (state) {
                // 새 제안이 검토함에 올라갔으니 배지를 다시 세게 한다(앱이 떠 있을 때만 효과, 아니면 다음에 열 때 센다)
                "PROPOSED" -> ReviewBadge.changed()
                "BUDGET_WAIT", "WAITING_KEY" -> return Result.failure(workDataOf(ERROR to state))
                "FAILED" -> return if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
            }
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "WorkBrainState"
        private const val PROJECT_ID = "project_id"
        private const val RECORD_IDS = "record_ids"
        private const val ERROR = "error"
        private const val MAX_RETRIES = 3
        /** [연결된 기록으로 정리하기]가 보는 최근 회의 수(비용: 회의 하나에 약 1~3원) */
        const val INIT_RECORDS = 3

        private fun workName(projectId: String) = "state-$projectId"

        /** 기록들을 순서대로 반영한다(오래된 회의부터 주면 나중 회의가 위에 쌓임) */
        fun start(context: Context, projectId: String, recordIds: List<String>) {
            if (recordIds.isEmpty()) return
            val request = OneTimeWorkRequestBuilder<StateWorker>()
                .setInputData(workDataOf(PROJECT_ID to projectId, RECORD_IDS to recordIds.toTypedArray()))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // 앞 작업이 돌고 있으면 뒤에 붙이고, 앞 작업이 실패·취소로 끝났으면 새로 시작한다
            WorkManager.getInstance(context).enqueueUniqueWork(workName(projectId), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /** 빈 카드 [연결된 기록으로 정리하기]: 연결된 최근 회의 몇 개를 오래된 것부터 */
        fun startFromLinked(context: Context, projectId: String, linked: List<LinkedRecord>) =
            start(context, projectId, initRecords(linked))

        /** 최근 INIT_RECORDS개를 고르고 오래된 것부터 줄 세운다(시각을 모르는 기록은 가장 오래된 것으로) */
        fun initRecords(linked: List<LinkedRecord>): List<String> =
            linked.sortedByDescending { it.at.orEmpty() }.take(INIT_RECORDS).reversed().map { it.recordId }

        /** 이 프로젝트의 제안을 만드는 중인지(카드 '정리하는 중…'). 끝나면 false로 바뀌어 카드가 다시 읽는다 */
        fun running(context: Context, projectId: String): Flow<Boolean> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(workName(projectId)).map { infos ->
                infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED }
            }
    }
}
