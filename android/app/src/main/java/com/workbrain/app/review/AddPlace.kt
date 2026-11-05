package com.workbrain.app.review

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.workbrain.app.data.Place
import com.workbrain.app.data.PlaceType
import com.workbrain.app.tasks.PlaceField
import com.workbrain.app.tasks.UNSORTED
import com.workbrain.app.ui.Wb
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// '추가할 곳'(DEC-68·69): 승인하는 할 일 후보가 들어갈 프로젝트·반복실행. 기록 상세와 검토함이 같이 쓴다

/** 아래 버튼 줄 바로 위 한 줄: '추가할 곳' [고르기 ▾] (목업 S05 xt-bar) */
@Composable
fun AddPlaceRow(place: Place?, places: List<Place>, onPick: (Place?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("추가할 곳", style = MaterialTheme.typography.labelLarge, color = Wb.colors.muted)
        PlaceField(
            label = place?.name ?: UNSORTED,
            type = place?.type,
            places = places,
            modifier = Modifier.weight(1f).padding(start = 12.dp).height(40.dp),
            onPick = onPick,
        )
    }
}

/** 프로젝트 ID → 고를 수 있는 곳(보관된 프로젝트는 목록에 없어서 null) */
fun projectPlace(projectId: String?, places: List<Place>): Place? =
    projectId?.let { id -> places.firstOrNull { it.type == PlaceType.PROJECT && it.id == id } }

/** 할 일이 들어갈 곳의 키(task 표·approve_proposal 공통): 프로젝트면 project_id, 반복실행이면 area_id, 정리 안 됨이면 없음 */
fun placeKeys(place: Place?): Map<String, JsonPrimitive> {
    if (place == null) return emptyMap()
    val key = if (place.type == PlaceType.PROJECT) "project_id" else "area_id"
    return mapOf(key to JsonPrimitive(place.id))
}

/** '추가할 곳'을 골랐으면 승인 함수가 읽는 위치 키를 고친 값에 더한다(approve_proposal이 project_id·area_id를 읽음) */
fun withPlace(edits: JsonObject?, place: Place?): JsonObject? =
    if (place == null) edits else JsonObject(edits.orEmpty() + placeKeys(place))
