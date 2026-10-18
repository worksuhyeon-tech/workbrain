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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.workbrain.app.auth.LoginScreen
import com.workbrain.app.record.SharedAudio
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

    // 다른 앱의 '공유'로 받은 음성 파일: 녹음 화면이 가져오기를 이어서 한다(파일 읽기 권한은 이 화면이 살아 있는 동안 유효)
    private fun receiveShared(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val uri = androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java) ?: return
        SharedAudio.pending.value = SharedAudio.Item(uri, intent.type)
    }
}

@Composable
private fun AppRoot() {
    val status by Supa.client.auth.sessionStatus.collectAsStateWithLifecycle()
    when (status) {
        is SessionStatus.Authenticated -> MainShell()
        is SessionStatus.Initializing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> LoginScreen()
    }
}
