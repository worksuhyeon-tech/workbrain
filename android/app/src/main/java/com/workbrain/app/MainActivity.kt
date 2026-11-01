package com.workbrain.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.workbrain.app.auth.LoginScreen
import com.workbrain.app.record.SharedImport
import com.workbrain.app.ui.WorkBrainTheme
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.status.SessionStatus

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Supa.client.handleDeeplinks(intent)
        receiveShared(intent)
        enableEdgeToEdge()
        setContent { WorkBrainTheme { AppRoot() } }
    }

    // 로그인 뒤 브라우저가 workbrain://login-callback 으로 돌려보내면 여기로 온다(launchMode=singleTask)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Supa.client.handleDeeplinks(intent)
        receiveShared(intent)
    }

    // 다른 앱의 '공유'로 받은 음성·글: 녹음 화면이 가져오기를 이어서 한다(파일 읽기 권한은 이 화면이 살아 있는 동안 유효).
    // 글은 파일(EXTRA_STREAM)로 오기도, 본문(EXTRA_TEXT)으로 오기도 해서 둘 다 받는다(DEC-53).
    // 삼성 음성 녹음은 한 파일도 SEND_MULTIPLE로 보내므로 첫 파일만 가져온다(여러 개를 한 번에 올리는 기능은 아직 없음)
    private fun receiveShared(intent: Intent) {
        val uri = when (intent.action) {
            Intent.ACTION_SEND -> androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java)
            Intent.ACTION_SEND_MULTIPLE -> androidx.core.content.IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java)?.firstOrNull()
            else -> return
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        SharedImport.pending.value = when {
            uri != null && SharedImport.isSkillZip(this, uri, intent.type) -> SharedImport.Item.SkillZip(uri)
            uri != null -> SharedImport.Item.File(uri, intent.type)
            !text.isNullOrBlank() -> SharedImport.Item.Text(text, intent.getStringExtra(Intent.EXTRA_SUBJECT))
            else -> return
        }
    }
}

@Composable
private fun AppRoot() {
    val status by Supa.client.auth.sessionStatus.collectAsStateWithLifecycle()
    // 앱이 다시 앞으로 올 때(파일 고르기에서 돌아올 때 등) 로그인 라이브러리가 저장된 세션을 다시 읽느라 잠깐 Initializing이 된다.
    // 이때 MainShell을 내리면 화면 상태와 진행 중인 작업(가져온 파일의 올리기 창 열기 등)이 모두 취소되므로,
    // 한 번 로그인된 뒤의 Initializing은 화면을 그대로 둔다
    var wasAuthenticated by remember { mutableStateOf(false) }
    if (status is SessionStatus.Authenticated) wasAuthenticated = true
    else if (status !is SessionStatus.Initializing) wasAuthenticated = false
    when {
        status is SessionStatus.Authenticated || (status is SessionStatus.Initializing && wasAuthenticated) -> MainShell()
        status is SessionStatus.Initializing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> LoginScreen()
    }
}
