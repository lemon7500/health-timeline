package com.healthtimeline.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.healthtimeline.app.ui.AppViewModel
import com.healthtimeline.app.ui.HealthTimelineRoot
import com.healthtimeline.app.ui.theme.HealthTimelineTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as HealthTimelineApplication
        setContent {
            HealthTimelineTheme {
                val services by produceState<Result<AppServices>?>(initialValue = null) {
                    value = withContext(Dispatchers.IO) {
                        runCatching { AppServices(app.repository, app.alarmScheduler, app.backupService) }
                    }
                }
                val available = services?.getOrNull()
                if (services == null) {
                    Surface {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (available != null) {
                    val model: AppViewModel = viewModel(
                        factory = AppViewModel.Factory(available.repository, available.scheduler, available.backup)
                    )
                    HealthTimelineRoot(model)
                } else {
                    Surface {
                        Column(
                            Modifier.fillMaxSize().padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("已停止打开数据", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                "本地数据库或加密密钥未通过完整性检查。为避免覆盖现有病程资料，应用没有自动清空数据。请不要卸载应用；确认安装的是最新版本后重试，并保留已有加密备份。",
                                modifier = Modifier.padding(vertical = 16.dp)
                            )
                            Button(onClick = { recreate() }) { Text("重试") }
                        }
                    }
                }
            }
        }
    }

    private data class AppServices(
        val repository: com.healthtimeline.app.data.HealthRepository,
        val scheduler: com.healthtimeline.app.reminders.AlarmScheduler,
        val backup: com.healthtimeline.app.backup.PortableBackupService
    )
}
