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
import com.workbrain.app.data.MinutesAnswer
import com.workbrain.app.data.Server
import com.workbrain.app.record.ResultNotifier
import io.github.jan.supabase.auth.auth
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 회의록 만들기(FR-30, DEC-56)를 백그라운드에서 한다. 1~2분 걸려서 화면을 나가도 '만드는 중'이 유지되고,
 * 끝나면 할 일 후보처럼 알림으로 알린다. 같은 기록은 한 번에 하나만 돈다(두 번 눌러도 비용이 두 번 들지 않게).
 *
 * 이동 중(LTE) 누르고 화면을 끄면 응답 전에 통신이 끊기거나 작업이 멈췄다가 앱을 다시 열 때 처음부터 다시 돈다(11/10 실기기).
 * 그래서 누를 때 만든 요청 열쇠를 다시 돌 때도 그대로 써서 서버가 두 번 만들지 않게 하고(DEC-79),
 * 응답을 못 받으면 실패로 단정하지 않고 서버에 그 요청이 끝났는지 확인한다.
 */
class MinutesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val recordId = inputData.getString(RECORD_ID) ?: return Result.failure()
        val title = inputData.getString(TITLE).orEmpty()
        val key = inputData.getString(KEY) ?: return Result.failure()
        Supa.client.auth.awaitInitialization()
        // 작업이 멈추면(CancellationException) 알림 없이 그대로 멈춘다. WorkManager가 같은 열쇠로 다시 돌린다
        val error = makeOnce(recordId, key)
        val id = ("minutes-$recordId").hashCode()
        return if (error == null) {
            ResultNotifier.notify(applicationContext, id, "회의록이 만들어졌어요", title)
            Result.success()
        } else {
            ResultNotifier.notify(applicationContext, id, "회의록을 만들지 못했어요", title)
            Result.failure(workDataOf(ERROR to error))
        }
    }

    /** 성공이면 null, 실패면 보여 줄 문구 */
    private suspend fun makeOnce(recordId: String, key: String): String? {
        val answer = try {
            Server.makeMinutes(recordId, key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null   // 응답을 못 받음: 서버는 만들고 있을 수 있다
        }
        when (answer) {
            MinutesAnswer.Done -> return null
            is MinutesAnswer.Failed -> return answer.message
            MinutesAnswer.Running, null -> Unit
        }
        repeat(STATUS_CHECKS) {
            delay(STATUS_INTERVAL_MS)
            val status = try {
                Server.minutesStatus(recordId, key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null   // 확인도 못 함: 다음 확인까지 기다린다
            }
            when (val verdict = minutesVerdict(status, lost = answer == null)) {
                MinutesVerdict.Wait -> Unit
                MinutesVerdict.Made -> return null
                is MinutesVerdict.Failed -> return verdict.message
            }
        }
        return NETWORK_ERROR
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
        private const val KEY = "request_key"
        private const val ERROR = "error"

        // 서버 잠금(170초)이 풀릴 때까지 10초마다 확인한다
        private const val STATUS_INTERVAL_MS = 10_000L
        private const val STATUS_CHECKS = 18

        private fun workName(recordId: String) = "minutes-$recordId"

        fun start(context: Context, recordId: String, title: String) {
            val request = OneTimeWorkRequestBuilder<MinutesWorker>()
                .setInputData(workDataOf(RECORD_ID to recordId, TITLE to title, KEY to UUID.randomUUID().toString()))
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

internal const val NETWORK_ERROR = "회의록을 만들지 못했어요. 인터넷 연결을 확인해 주세요"
internal const val RETRY_ERROR = "회의록을 만들지 못했어요. 다시 시도해 주세요"

/** 서버 확인 결과로 정한 다음 걸음: 더 기다림 · 만들어짐 · 실패(문구) */
sealed interface MinutesVerdict {
    data object Wait : MinutesVerdict
    data object Made : MinutesVerdict
    data class Failed(val message: String) : MinutesVerdict
}

/**
 * wb_document_status 값으로 다음 걸음을 정한다. lost = 처음 요청의 응답을 못 받음.
 * NONE은 내 요청이 서버에 닿지 않은 것(응답을 못 받았으면 연결 문제), 확인 실패(null)·RUNNING은 더 기다린다
 */
fun minutesVerdict(status: String?, lost: Boolean): MinutesVerdict = when (status) {
    "DONE" -> MinutesVerdict.Made
    "FAILED" -> MinutesVerdict.Failed(RETRY_ERROR)
    "BUSY" -> MinutesVerdict.Failed("이미 회의록을 만드는 중이에요. 다 되면 화면에 보여요")
    "NONE" -> MinutesVerdict.Failed(if (lost) NETWORK_ERROR else RETRY_ERROR)
    else -> MinutesVerdict.Wait
}
