package com.gohiking.app

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.LatLng
import com.gohiking.core.designsystem.theme.GhTheme
import com.gohiking.core.resources.R as CoreR

/**
 * M0 验证壳：①隐私同意门（DEV §1.5 红线：高德 SDK 必须在用户同意后才初始化）；
 * ②同意后展示高德地图验证页——瓦片能加载 = Key + 包名 + SHA1 绑定全部生效（M0 真机验证项）。
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
    val context = LocalContext.current
    var agreed by rememberSaveable { mutableStateOf(false) }
    if (agreed) {
        MapVerifyScreen(modifier)
    } else {
        PrivacyGate(
            onAgree = { agreed = true },
            onDecline = { (context as? Activity)?.finish() },
        )
    }
}

@Composable
private fun PrivacyGate(onAgree: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(stringResource(CoreR.string.common_privacy_title)) },
        text = { Text(stringResource(CoreR.string.common_privacy_message)) },
        confirmButton = {
            TextButton(onClick = onAgree) { Text(stringResource(CoreR.string.common_privacy_agree)) }
        },
        dismissButton = {
            TextButton(onClick = onDecline) { Text(stringResource(CoreR.string.common_privacy_decline)) }
        },
    )
}

@Composable
private fun MapVerifyScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 隐私合规必须【早于 MapView 创建】调用（3D SDK ≥8.1.0 强制，否则白屏不渲染瓦片）。
    // 放在 remember 里恰好保证：①先于地图创建；②进程内只调一次；③rememberSaveable 记住同意状态的
    // 二次启动也覆盖（此时不走弹窗，但「已同意」状态成立，红线只要求同意后调用）。
    val mapView = remember {
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)
        MapView(context).apply {
            onCreate(null)
            map.uiSettings.isZoomControlsEnabled = true
            // 默认视角：中国全域；M1 首页再改为定位到当前位置
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(35.86, 104.19), 3.8f))
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        Surface(
            tonalElevation = 4.dp,
            shadowElevation = 2.dp,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp),
        ) {
            Text(
                text = stringResource(CoreR.string.map_verify_badge),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
