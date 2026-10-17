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
import com.workbrain.app.record.HomeScreen
import com.workbrain.app.ui.WorkBrainTheme
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.status.SessionStatus

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Supa.client.handleDeeplinks(intent)
        enableEdgeToEdge()
        setContent { WorkBrainTheme { AppRoot() } }
    }

    // 로그인 뒤 브라우저가 workbrain://login-callback 으로 돌려보내면 여기로 온다(launchMode=singleTask)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Supa.client.handleDeeplinks(intent)
    }
}

@Composable
private fun AppRoot() {
    val status by Supa.client.auth.sessionStatus.collectAsStateWithLifecycle()
    when (status) {
        is SessionStatus.Authenticated -> HomeScreen()
        is SessionStatus.Initializing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> LoginScreen()
    }
}
