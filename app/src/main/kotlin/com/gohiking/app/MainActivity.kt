package com.gohiking.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.gohiking.core.designsystem.theme.GhTheme
import com.gohiking.core.resources.R as CoreR

/**
 * M0 壳 Activity：仅验证「Compose 主题 + 集中字符串资源」链路。
 * 导航宿主（P-02 首页地图等）在 M1 起按 DEV §5.4 导航图逐页落地。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GhTheme {
                Root()
            }
        }
    }
}

@Composable
private fun Root(modifier: Modifier = Modifier) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Text(
            text = stringResource(CoreR.string.app_name),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}
