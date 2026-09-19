package com.gohiking.app

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.LatLng
import com.gohiking.core.designsystem.theme.GhTheme
import com.gohiking.core.resources.R as CoreR

/**
 * M0 验证壳：①隐私同意门（DEV §1.5 红线：高德 SDK 必须在用户同意后才初始化）；
 * ②同意后展示高德地图验证页——瓦片能加载 = Key + 包名 + SHA1 绑定全部生效（已真机通过 2026-09-20）；
 * ③两项 M0 实测开关：setMapLanguage 切语言 / POI 点击与 OnMapClickListener 冲突（PRD 6.2.1 M0 项）。
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

    // 事件日志（新事件插到最前），最多保留 6 条
    val events = remember { mutableStateListOf<String>() }
    var isChinese by rememberSaveable { mutableStateOf(true) }
    var lastPoiAtMs by remember { mutableStateOf(0L) }
    var lastMapClickAtMs by remember { mutableStateOf(0L) }

    fun log(text: String) {
        events.add(0, text)
        if (events.size > 6) events.removeAt(events.lastIndex)
    }

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

            // ---- M0 实测②：POI 点击时 OnMapClickListener 是否同触发（PRD 6.2.1）----
            map.setOnMapClickListener { latLng ->
                lastMapClickAtMs = System.currentTimeMillis()
                log(
                    context.getString(
                        CoreR.string.map_test_log_map,
                        latLng.latitude.toString(),
                        latLng.longitude.toString(),
                    ),
                )
            }
            map.setOnPOIClickListener { poi ->
                lastPoiAtMs = System.currentTimeMillis()
                log(context.getString(CoreR.string.map_test_log_poi, poi.name ?: "?"))
                // 同一手势的两个回调间隔通常 <100ms；>600ms 视为两次独立点击
                if (lastMapClickAtMs > 0 && kotlin.math.abs(lastPoiAtMs - lastMapClickAtMs) < 600) {
                    log(context.getString(CoreR.string.map_test_log_both))
                }
            }
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
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp),
        ) {
            Text(
                text = stringResource(CoreR.string.map_verify_badge),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        // ---- M0 实测控制区 ----
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                tonalElevation = 4.dp,
                shadowElevation = 2.dp,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(CoreR.string.map_test_hint),
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    events.forEachIndexed { index, line ->
                        Text(
                            text = line,
                            modifier = Modifier.padding(top = if (index == 0) 4.dp else 0.dp),
                        )
                    }
                }
            }
            Button(
                onClick = {
                    isChinese = !isChinese
                    // ---- M0 实测①：setMapLanguage 真机能否切英文（PRD 9.7.7 / F-I18N-31）----
                    mapView.map.setMapLanguage(if (isChinese) AMap.CHINESE else AMap.ENGLISH)
                    log(context.getString(CoreR.string.map_test_log_language))
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(stringResource(CoreR.string.map_test_switch_language))
            }
        }
    }
}
