package com.gohiking.feature.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.gohiking.core.location.LocationProvider
import com.gohiking.core.map.search.AmapSearchClient
import com.gohiking.core.resources.R as CoreR

/**
 * 计划模式选点页（P-03，F-PLAN-01~05/07/08）。
 * 四种选点方式（PRD 6.2.1）：① 地图点选（永远可用的兜底）② 搜索选点 ③ 底图 POI ④ 当前位置。
 */
@Composable
fun PlanScreen(
    searchClient: AmapSearchClient,
    locationProvider: LocationProvider,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: PlanViewModel = viewModel(
        factory = viewModelFactory {
            initializer { PlanViewModel(searchClient, locationProvider) }
        },
    )
    PlanContent(viewModel = viewModel, onBack = onBack, modifier = modifier)
}

@Composable
private fun PlanContent(
    viewModel: PlanViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var query by rememberSaveable { mutableStateOf("") }
    var poiCandidate by remember { mutableStateOf<Pair<String, LatLng>?>(null) }
    var lastPoiAtMs by remember { mutableLongStateOf(0L) }

    val startLabel = stringResource(CoreR.string.plan_target_start)
    val endLabel = stringResource(CoreR.string.plan_target_end)
    val startUnset = stringResource(CoreR.string.plan_point_unset)
    val endUnset = startUnset

    // 起终点 Marker 引用 + marker→target 映射（拖动回调定位用，F-PLAN-06）
    val markers = remember { mutableListOf<Marker>() }
    val markerTargets = remember { mutableMapOf<Marker, SelectTarget>() }

    // 隐私合规必须早于 MapView 创建（红线，重复调用幂等；与首页/记录页同一模式）
    val mapView = remember {
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)
        MapView(context).apply {
            onCreate(null)
            map.uiSettings.isZoomControlsEnabled = true
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(35.86, 104.19), 3.8f))

            // F-PLAN-08：底图 POI 命中 → 弹气泡「设为起点 / 设为终点」
            map.setOnPOIClickListener { poi ->
                lastPoiAtMs = System.currentTimeMillis()
                val coord = poi.coordinate
                if (coord != null) poiCandidate = (poi.name ?: "?") to coord
            }
            // F-PLAN-03/04：空白点选；600ms 内 POI 已命中则让位（双回调同触以 POI 为准）
            map.setOnMapClickListener { latLng ->
                if (System.currentTimeMillis() - lastPoiAtMs < POI_WIN_MS) return@setOnMapClickListener
                viewModel.onMapTap(latLng)
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> mapView.onResume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    // 起终点标记随 state 重建；选中后相机跟随（单点居中 / 双点取包含框）
    LaunchedEffect(state.start, state.end, startLabel, endLabel) {
        val aMap = mapView.map
        markers.forEach { it.remove() }
        markers.clear()
        markerTargets.clear()
        state.start?.let { p ->
            val m = aMap.addMarker(markerOptions(p, startLabel, BitmapDescriptorFactory.HUE_GREEN))
            markers += m
            markerTargets[m] = SelectTarget.START
        }
        state.end?.let { p ->
            val m = aMap.addMarker(markerOptions(p, endLabel, BitmapDescriptorFactory.HUE_RED))
            markers += m
            markerTargets[m] = SelectTarget.END
        }
        aMap.setOnMarkerDragListener(object : AMap.OnMarkerDragListener {
            override fun onMarkerDragStart(marker: Marker) = Unit
            override fun onMarkerDrag(marker: Marker) = Unit
            override fun onMarkerDragEnd(marker: Marker) {
                markerTargets[marker]?.let { viewModel.onPointMoved(it, marker.position) }
            }
        })
        val pts = listOfNotNull(state.start?.latLng, state.end?.latLng)
        when (pts.size) {
            1 -> aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pts[0], 15f))
            2 -> {
                val builder = LatLngBounds.Builder()
                pts.forEach { builder.include(it) }
                aMap.moveCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), BOUNDS_PADDING_PX))
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // 顶部：目标切换 + 搜索联想（F-PLAN-05）
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Surface(
                tonalElevation = 4.dp,
                shadowElevation = 2.dp,
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.activeTarget == SelectTarget.START,
                            onClick = { viewModel.setActiveTarget(SelectTarget.START) },
                            label = {
                                Text("$startLabel: ${state.start?.name ?: startUnset}")
                            },
                        )
                        FilterChip(
                            selected = state.activeTarget == SelectTarget.END,
                            onClick = { viewModel.setActiveTarget(SelectTarget.END) },
                            label = {
                                Text("$endLabel: ${state.end?.name ?: endUnset}")
                            },
                        )
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = {
                            query = it
                            viewModel.onSearchInput(it, mapCenterOf(mapView))
                        },
                        placeholder = { Text(stringResource(CoreR.string.plan_search_hint)) },
                        singleLine = true,
                        trailingIcon = {
                            if (state.searching) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
            }
            if (state.suggestions.isNotEmpty()) {
                Surface(
                    tonalElevation = 4.dp,
                    shadowElevation = 2.dp,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                        items(state.suggestions.size) { i ->
                            val s = state.suggestions[i]
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.onSuggestionPicked(s, mapCenterOf(mapView))
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                Text(s.name)
                                if (s.district.isNotBlank()) {
                                    Text(
                                        s.district,
                                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            val hintText = when (state.hint) {
                SearchHint.NO_RESULT -> stringResource(CoreR.string.plan_hint_no_result)
                SearchHint.OFFLINE -> stringResource(CoreR.string.plan_hint_offline)
                SearchHint.LOCATE_FAILED -> stringResource(CoreR.string.plan_hint_locate_failed)
                SearchHint.NONE -> null
            }
            if (hintText != null) {
                Surface(
                    tonalElevation = 4.dp,
                    shadowElevation = 2.dp,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Text(hintText, modifier = Modifier.padding(12.dp))
                }
            }
        }

        // 底部：当前位置为起点（F-PLAN-07）+ 重选 + 返回
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.start != null && state.end != null) {
                Surface(
                    tonalElevation = 4.dp,
                    shadowElevation = 2.dp,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(CoreR.string.plan_both_set),
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            Button(
                onClick = { viewModel.useCurrentLocationAsStart() },
                enabled = !state.locating,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(stringResource(CoreR.string.plan_use_current))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.CenterHorizontally)) {
                OutlinedButton(onClick = { viewModel.reset() }) {
                    Text(stringResource(CoreR.string.plan_reset))
                }
                OutlinedButton(onClick = onBack) {
                    Text(stringResource(CoreR.string.plan_back))
                }
            }
        }
    }

    // F-PLAN-08：POI 气泡——设为起点 / 设为终点
    poiCandidate?.let { (name, latLng) ->
        AlertDialog(
            onDismissRequest = { poiCandidate = null },
            title = { Text(name) },
            text = { Text(stringResource(CoreR.string.plan_poi_dialog_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onPoiChosen(name, latLng, SelectTarget.START)
                    poiCandidate = null
                }) { Text(stringResource(CoreR.string.plan_poi_set_start)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.onPoiChosen(name, latLng, SelectTarget.END)
                    poiCandidate = null
                }) { Text(stringResource(CoreR.string.plan_poi_set_end)) }
            },
        )
    }
}

private fun markerOptions(point: PlanPoint, fallbackTitle: String, hue: Float): MarkerOptions =
    MarkerOptions()
        .position(point.latLng)
        .title(point.name ?: fallbackTitle)
        .icon(BitmapDescriptorFactory.defaultMarker(hue))
        .draggable(true) // F-PLAN-06：拖动调整位置（P1）

private fun mapCenterOf(mapView: MapView): LatLng? =
    mapView.map.cameraPosition?.target

/** F-PLAN-08 双回调同触窗口（M0 真机实测：POI 与空白点选回调在 600ms 内先后触发） */
private const val POI_WIN_MS = 600L

/** 双点包含框的屏幕边距（px） */
private const val BOUNDS_PADDING_PX = 120

