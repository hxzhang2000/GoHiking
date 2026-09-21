package com.gohiking.feature.plan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.gohiking.core.map.overlay.PolylineArrowTexture
import com.gohiking.core.common.format.Formatters
import com.gohiking.core.designsystem.theme.GhColors
import com.gohiking.core.database.dao.PlannedRouteDao
import com.gohiking.core.elevation.ElevationRepository
import com.gohiking.core.location.LocationProvider
import com.gohiking.core.map.route.RouteSearchClient
import com.gohiking.core.map.search.AmapSearchClient
import com.gohiking.core.model.Difficulty
import com.gohiking.core.resources.R as CoreR

/**
 * 计划模式页（P-03 选点 + P-04 自动推荐，F-PLAN-01~16）。
 * 选点四种方式（PRD 6.2.1）：① 地图点选（永远可用的兜底）② 搜索选点 ③ 底图 POI ④ 当前位置；
 * 起终点齐后自动推荐 ≤3 条步行线路（DEV §4.8），卡片展示距离/耗时/爬升估算/难度，选定落库为计划线路。
 */
@Composable
fun PlanScreen(
    searchClient: AmapSearchClient,
    routeClient: RouteSearchClient,
    locationProvider: LocationProvider,
    plannedRouteDao: PlannedRouteDao,
    elevationRepository: ElevationRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appContext = LocalContext.current.applicationContext
    val viewModel: PlanViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                PlanViewModel(
                    searchClient,
                    routeClient,
                    locationProvider,
                    plannedRouteDao,
                    elevationRepository::query,
                    appContext,
                )
            }
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
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    var deleteWaypointIdx by remember { mutableStateOf<Int?>(null) }

    val startLabel = stringResource(CoreR.string.plan_target_start)
    val endLabel = stringResource(CoreR.string.plan_target_end)
    val startUnset = stringResource(CoreR.string.plan_point_unset)
    val endUnset = startUnset
    // L-19：POI 无名称时原本硬编码 "?"，与资源约定 common_stat_unknown 不一致
    val unknownName = stringResource(CoreR.string.common_stat_unknown)

    // 起终点 Marker 引用 + marker→target 映射（拖动回调定位用，F-PLAN-06）
    val markers = remember { mutableListOf<Marker>() }
    val markerTargets = remember { mutableMapOf<Marker, SelectTarget>() }

    // 候选线路折线引用（F-PLAN-12/13/15；重画前逐条 remove，防叠加）
    val candidatePolylines = remember { mutableListOf<Polyline>() }

    // 手动模式：途经点 marker→序号（拖动/删除回调定位用）+ 折线引用
    val waypointMarkers = remember { mutableMapOf<Marker, Int>() }
    val manualPolylines = remember { mutableListOf<Polyline>() }
    // 返程折线引用（F-PLAN-36 深蓝虚线；单条）
    val returnPolylines = remember { mutableListOf<Polyline>() } // 线 + 箭头纹理层（F-PLAN-37）

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
                if (coord != null) poiCandidate = (poi.name?.takeIf { it.isNotBlank() } ?: unknownName) to coord
            }
            // F-PLAN-03/04：空白点选；600ms 内 POI 已命中则让位（双回调同触以 POI 为准）
            map.setOnMapClickListener { latLng ->
                if (System.currentTimeMillis() - lastPoiAtMs < POI_WIN_MS) return@setOnMapClickListener
                viewModel.onMapTap(latLng)
            }
            // F-PLAN-27：点击途经点 marker → 删除确认（仅手动模式的 marker 会命中 waypointMarkers）
            map.setOnMarkerClickListener { marker ->
                val idx = waypointMarkers[marker]
                if (idx != null) {
                    deleteWaypointIdx = idx
                    true
                } else {
                    false
                }
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
            // N-19：从上一页返回时 Activity 并未 pause，直接 onDestroy() 会让 AMap
            // 内部 GL 资源与监听器释放顺序错乱（地图黑屏/瓦片不刷新/偶发崩溃）。
            // 必须保证 pause → destroy 的调用顺序（与 MainActivity 的修法一致）。
            mapView.onPause()
            mapView.onDestroy()
        }
    }

    // 标记随 state 重建；选中后相机跟随（单点居中 / 多点取包含框）
    LaunchedEffect(state.start, state.end, state.manualMode, state.manualWaypoints, startLabel, endLabel) {
        val aMap = mapView.map
        markers.forEach { it.remove() }
        markers.clear()
        markerTargets.clear()
        waypointMarkers.clear()
        if (state.manualMode) {
            // F-PLAN-22 序号圆图标；F-PLAN-26 拖动；删除入口见 setOnMarkerClickListener
            state.manualWaypoints.forEachIndexed { i, wp ->
                val m = aMap.addMarker(
                    MarkerOptions()
                        .position(wp.latLng)
                        .title(wp.name ?: (i + 1).toString())
                        .icon(BitmapDescriptorFactory.fromBitmap(numberedMarkerBitmap(i + 1)))
                        .draggable(true),
                )
                waypointMarkers[m] = i
            }
            aMap.setOnMarkerDragListener(object : AMap.OnMarkerDragListener {
                override fun onMarkerDragStart(marker: Marker) = Unit
                override fun onMarkerDrag(marker: Marker) = Unit
                override fun onMarkerDragEnd(marker: Marker) {
                    markerTargets[marker]?.let { viewModel.onPointMoved(it, marker.position) }
                    waypointMarkers[marker]?.let { viewModel.onWaypointMoved(it, marker.position) }
                }
            })
            val wps = state.manualWaypoints.map { it.latLng }
            if (wps.size == 1) {
                aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(wps[0], 15f))
            } else if (wps.isNotEmpty()) {
                val builder = LatLngBounds.Builder()
                wps.forEach { builder.include(it) }
                aMap.moveCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), BOUNDS_PADDING_PX))
            }
        } else {
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
    }

    // F-PLAN-12/13/15：候选折线渲染——3 色候选、点击卡片高亮加粗、选定后蓝实线。
    // 键用 path 列表（数据类逐值相等）：metrics 后台补齐不触发重画；
    // 密度换算必须在组合期捕获（LaunchedEffect 体不在组合作用域）；
    // Polyline 无 setAlpha（D-17 javap 结论）：半透明用 ARGB color int 的 alpha 位。
    val densityPx = with(LocalDensity.current) { 1.dp.toPx() }
    LaunchedEffect(
        state.candidates.map { it.path },
        state.highlightIndex,
        state.chosenIndex,
        densityPx,
    ) {
        val aMap = mapView.map
        candidatePolylines.forEach { it.remove() }
        candidatePolylines.clear()
        val chosen = state.candidates.getOrNull(state.chosenIndex)
        if (chosen != null) {
            candidatePolylines += aMap.addPolyline(
                PolylineOptions()
                    .addAll(chosen.path.points)
                    .width(SELECTED_WIDTH_DP * densityPx)
                    .color(SELECTED_COLOR),
            )
            // F-PLAN-37：方向箭头叠加层（透明底 chevron 纹理，D-23）
            candidatePolylines += aMap.addPolyline(
                PolylineOptions()
                    .addAll(chosen.path.points)
                    .width(SELECTED_WIDTH_DP * densityPx)
                    .setCustomTexture(PolylineArrowTexture.forColor(SELECTED_COLOR))
                    .zIndex(1f),
            )
        } else {
            state.candidates.forEachIndexed { i, c ->
                val highlighted = i == state.highlightIndex
                val base = CANDIDATE_COLORS[i % CANDIDATE_COLORS.size]
                val lineColor = if (highlighted) {
                    base
                } else {
                    (CANDIDATE_DIM_ALPHA shl 24) or (base and 0x00FFFFFF)
                }
                candidatePolylines += aMap.addPolyline(
                    PolylineOptions()
                        .addAll(c.path.points)
                        .width((if (highlighted) HIGHLIGHT_WIDTH_DP else NORMAL_WIDTH_DP) * densityPx)
                        .color(lineColor),
                )
                candidatePolylines += aMap.addPolyline(
                    PolylineOptions()
                        .addAll(c.path.points)
                        .width((if (highlighted) HIGHLIGHT_WIDTH_DP else NORMAL_WIDTH_DP) * densityPx)
                        .setCustomTexture(PolylineArrowTexture.forColor(lineColor))
                        .zIndex(1f),
                )
            }
        }
        // 视野包含全部候选折线（选定后聚焦所选线路）
        val focusPts = chosen?.path?.points ?: state.candidates.flatMap { it.path.points }
        if (focusPts.isNotEmpty()) {
            val builder = LatLngBounds.Builder()
            focusPts.forEach { builder.include(it) }
            aMap.moveCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), BOUNDS_PADDING_PX))
        }
    }

    // F-PLAN-36：返程折线——深蓝 #1B4F9C 虚线（原路返回 = 去程反向）
    LaunchedEffect(state.returnEnabled, state.returnPath, densityPx) {
        val aMap = mapView.map
        returnPolylines.forEach { it.remove() }
        returnPolylines.clear()
        if (state.returnEnabled) {
            state.returnPath?.let { rp ->
                if (rp.points.isNotEmpty()) {
                    returnPolylines += aMap.addPolyline(
                        PolylineOptions()
                            .addAll(rp.points)
                            .width(RETURN_WIDTH_DP * densityPx)
                            .color(RETURN_COLOR)
                            .setDottedLine(true),
                    )
                    // 虚线与纹理互斥（D-23 jlib 实测结论）→ 箭头用叠加层
                    returnPolylines += aMap.addPolyline(
                        PolylineOptions()
                            .addAll(rp.points)
                            .width(RETURN_WIDTH_DP * densityPx)
                            .setCustomTexture(PolylineArrowTexture.forColor(RETURN_COLOR))
                            .zIndex(1f),
                    )
                }
            }
        }
    }

    // F-PLAN-23/24/36：手动线路折线——直线段虚线（setDottedLine，javap 确认 API）、吸附段实线
    LaunchedEffect(state.manualSegments, densityPx) {
        val aMap = mapView.map
        manualPolylines.forEach { it.remove() }
        manualPolylines.clear()
        state.manualSegments.forEach { seg ->
            val opts = PolylineOptions()
                .addAll(seg.points)
                .width(MANUAL_WIDTH_DP * densityPx)
                .color(MANUAL_COLOR)
            if (!seg.snapped) opts.setDottedLine(true)
            manualPolylines += aMap.addPolyline(opts)
            manualPolylines += aMap.addPolyline(
                PolylineOptions()
                    .addAll(seg.points)
                    .width(MANUAL_WIDTH_DP * densityPx)
                    .setCustomTexture(PolylineArrowTexture.forColor(MANUAL_COLOR))
                    .zIndex(1f),
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // 顶部：目标切换 + 搜索联想（F-PLAN-05）
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                // N-11：targetSdk 35 强制 edge-to-edge，状态栏会压住顶部的目标切换与搜索框
                // （点击区域被系统栏抢走）。地图仍铺满全屏，只把这个浮层下移。
                .statusBarsPadding()
                .padding(16.dp),
        ) {
            // ---- P-03 appbar：返回 + 标题 + 起终点互换 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(GhColors.Surface)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(CoreR.string.plan_back),
                        tint = GhColors.TextPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(CoreR.string.plan_select_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = GhColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(GhColors.Surface)
                        .clickable(enabled = state.chosenRouteId == null) { viewModel.swapPoints() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.SwapHoriz,
                        contentDescription = stringResource(CoreR.string.plan_swap),
                        tint = GhColors.TextPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Surface(
                shadowElevation = 2.dp,
                shape = RoundedCornerShape(12.dp),
                color = GhColors.Surface,
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    // 起点终点 seg（原型 pickbar 的 seg 控件）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(GhColors.Surface2)
                            .padding(3.dp),
                    ) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            SegButton(
                                label = startLabel,
                                selected = state.activeTarget == SelectTarget.START,
                                onClick = { viewModel.setActiveTarget(SelectTarget.START) },
                                modifier = Modifier.weight(1f),
                            )
                            SegButton(
                                label = endLabel,
                                selected = state.activeTarget == SelectTarget.END,
                                onClick = { viewModel.setActiveTarget(SelectTarget.END) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = (if (state.activeTarget == SelectTarget.START) startLabel else endLabel) + " · " +
                            (
                                (if (state.activeTarget == SelectTarget.START) state.start else state.end)?.name
                                    ?: stringResource(CoreR.string.plan_pick_hint)
                                ),
                        fontSize = 12.sp,
                        color = GhColors.TextSecondary,
                        maxLines = 1,
                    )
                    OutlinedTextField(
                        value = query,
                        onValueChange = {
                            query = it
                            viewModel.onSearchInput(it, mapCenterOf(mapView))
                        },
                        placeholder = { Text(stringResource(CoreR.string.plan_search_hint)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
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
            if (state.manualMode) {
                // F-PLAN-20/22~29：手动模式面板——连线方式切换 + 实时评估
                Surface(
                    tonalElevation = 4.dp,
                    shadowElevation = 2.dp,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            stringResource(CoreR.string.plan_manual_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(CoreR.string.plan_manual_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            FilterChip(
                                selected = state.manualConnect == ManualConnect.STRAIGHT,
                                onClick = { viewModel.setManualConnect(ManualConnect.STRAIGHT) },
                                label = { Text(stringResource(CoreR.string.plan_manual_connect_straight)) },
                            )
                            FilterChip(
                                selected = state.manualConnect == ManualConnect.SNAP,
                                onClick = { viewModel.setManualConnect(ManualConnect.SNAP) },
                                label = { Text(stringResource(CoreR.string.plan_manual_connect_snap)) },
                            )
                            if (state.manualSnapping) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            }
                        }
                        val mm = state.manualMetrics
                        if (mm != null) {
                            // F-PLAN-29：距离/耗时即时，爬升「估算」（F-PLAN-46）后台补齐
                            val ascent = mm.ascentM // 跨模块属性不能 smart cast，先取局部值
                            Text(
                                text = Formatters.distanceText(mm.distanceM) +
                                    " · " + Formatters.durationText(mm.estimatedDurationSec),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Text(
                                text = if (ascent != null) {
                                    stringResource(CoreR.string.plan_estimated_ascent, ascent.toInt())
                                } else {
                                    stringResource(CoreR.string.common_stat_unknown)
                                } + " · " + (state.manualDifficulty?.let { difficultyLabel(it) }
                                    ?: stringResource(CoreR.string.common_stat_unknown)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (state.manualLimitHit) {
                            Text(
                                stringResource(CoreR.string.plan_manual_waypoint_limit),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        // 底部：选点 pickbar（P-03）/ 候选列表（P-04）/ 手动打点 pickbar（P-05）
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.manualMode) {
                // ---- P-05：手动打点 pickbar（提示/计数/直线-吸附/撤销-生成）----
                Surface(
                    shadowElevation = 2.dp,
                    shape = RoundedCornerShape(12.dp),
                    color = GhColors.Surface,
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            stringResource(CoreR.string.plan_manual_hint),
                            fontSize = 12.sp,
                            color = GhColors.TextSecondary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(CoreR.string.plan_waypoint_count, state.manualWaypoints.size),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(GhColors.Surface2)
                                .padding(3.dp),
                        ) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                SegButton(
                                    label = stringResource(CoreR.string.plan_manual_connect_straight),
                                    selected = state.manualConnect == ManualConnect.STRAIGHT,
                                    onClick = { viewModel.setManualConnect(ManualConnect.STRAIGHT) },
                                    modifier = Modifier.weight(1f),
                                )
                                SegButton(
                                    label = stringResource(CoreR.string.plan_manual_connect_snap),
                                    selected = state.manualConnect == ManualConnect.SNAP,
                                    onClick = { viewModel.setManualConnect(ManualConnect.SNAP) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        if (state.manualSnapping) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(CoreR.string.plan_manual_connect_snap),
                                    fontSize = 12.sp,
                                    color = GhColors.TextSecondary,
                                )
                            }
                        }
                        val mm = state.manualMetrics
                        if (mm != null) {
                            // F-PLAN-29：距离/耗时即时，爬升「估算」（F-PLAN-46）后台补齐
                            val ascent = mm.ascentM // 跨模块属性不能 smart cast，先取局部值
                            Text(
                                text = Formatters.distanceText(mm.distanceM) +
                                    " · " + Formatters.durationText(mm.estimatedDurationSec),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Text(
                                text = if (ascent != null) {
                                    stringResource(CoreR.string.plan_estimated_ascent, ascent.toInt())
                                } else {
                                    stringResource(CoreR.string.common_stat_unknown)
                                } + " · " + (state.manualDifficulty?.let { difficultyLabel(it) }
                                    ?: stringResource(CoreR.string.common_stat_unknown)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (state.manualLimitHit) {
                            Text(
                                stringResource(CoreR.string.plan_manual_waypoint_limit),
                                color = GhColors.Danger,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { viewModel.undoWaypoint() },
                                enabled = state.manualWaypoints.isNotEmpty(), // F-PLAN-25
                            ) {
                                Text(stringResource(CoreR.string.plan_manual_undo))
                            }
                            TextButton(
                                onClick = { viewModel.clearManual() },
                                enabled = state.manualWaypoints.isNotEmpty(),
                            ) {
                                Text(stringResource(CoreR.string.plan_manual_clear))
                            }
                            Button(
                                onClick = { showSaveDialog = true }, // F-PLAN-40 命名保存
                                enabled = state.manualSegments.isNotEmpty() && state.manualSavedRouteId == null,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    stringResource(
                                        if (state.manualSavedRouteId != null) CoreR.string.plan_saved
                                        else CoreR.string.plan_manual_save,
                                    ),
                                )
                            }
                        }
                        TextButton(
                            onClick = { viewModel.exitManualMode() },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        ) {
                            Text(stringResource(CoreR.string.plan_back))
                        }
                    }
                }
            } else if (state.candidates.isNotEmpty()) {
                // ---- P-04：候选线路纵向列表（点击选定，选中卡蓝描边+对勾）----
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.heightIn(max = 250.dp),
                ) {
                    items(state.candidates.size) { i ->
                        CandidateCard(
                            candidate = state.candidates[i],
                            chosen = state.confirming && i == state.chosenIndex,
                            saved = state.chosenRouteId != null,
                            onSelect = { viewModel.chooseCandidate(i) },
                            onSave = { showSaveDialog = true }, // F-PLAN-40 命名保存
                            onClick = { viewModel.onCandidateClicked(i) },
                        )
                    }
                }
                // P-04 操作区：手动打点 / 选择此线路
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = { viewModel.enterManualMode() }) {
                        Text(stringResource(CoreR.string.plan_manual_entry))
                    }
                    Button(
                        onClick = {
                            viewModel.chooseCandidate(
                                if (state.chosenIndex >= 0) state.chosenIndex else state.highlightIndex.coerceAtLeast(0),
                            )
                        },
                        enabled = !state.planning && state.chosenRouteId == null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(CoreR.string.plan_choose_this))
                    }
                }
                if (state.confirming) {
                    // F-PLAN-34/38：确认面板——原路返回开关 + 全程汇总
                    Surface(
                        shadowElevation = 2.dp,
                        shape = RoundedCornerShape(12.dp),
                        color = GhColors.Surface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FilterChip(
                                    selected = state.returnEnabled,
                                    onClick = { viewModel.setReturnEnabled(!state.returnEnabled) },
                                    label = { Text(stringResource(CoreR.string.plan_return_toggle)) },
                                )
                                // F-PLAN-35：返程独立规划（不依赖原路返回）
                                if (state.returnPlanning) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    OutlinedButton(onClick = { viewModel.replanReturn() }) {
                                        Text(stringResource(CoreR.string.plan_replan_return))
                                    }
                                }
                                if (state.chosenRouteId != null) {
                                    Text(
                                        stringResource(CoreR.string.plan_saved),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }
                            val outbound = state.candidates.getOrNull(state.chosenIndex)
                            val ret = if (state.returnEnabled) state.returnPath else null
                            val totalDist = (outbound?.path?.distanceM ?: 0f) + (ret?.distanceM ?: 0f)
                            val totalDur = (outbound?.path?.durationS ?: 0L) + (ret?.durationS ?: 0L)
                            val ascentSum = (outbound?.metrics?.ascentM ?: 0.0) +
                                if (state.returnEnabled) (state.returnMetrics?.ascentM ?: 0.0) else 0.0
                            Text(
                                text = Formatters.distanceText(totalDist.toDouble()) +
                                    " · " + Formatters.durationText(totalDur),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Text(
                                text = if (outbound?.metrics?.ascentM != null) {
                                    stringResource(CoreR.string.plan_estimated_ascent, ascentSum.toInt())
                                } else {
                                    stringResource(CoreR.string.common_stat_unknown)
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            } else {
                // ---- P-03：选点 pickbar ----
                if (state.planning) {
                    Surface(
                        shadowElevation = 2.dp,
                        shape = RoundedCornerShape(12.dp),
                        color = GhColors.Surface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(stringResource(CoreR.string.plan_planning), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                } else if (state.planFailed) {
                    Surface(
                        shadowElevation = 2.dp,
                        shape = RoundedCornerShape(12.dp),
                        color = GhColors.Surface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(stringResource(CoreR.string.plan_route_failed))
                            // F-PLAN-20：自动规划失败 → 引导手动打点兜底
                            Button(
                                onClick = { viewModel.enterManualMode() },
                                modifier = Modifier.padding(top = 8.dp),
                            ) {
                                Text(stringResource(CoreR.string.plan_manual_entry))
                            }
                        }
                    }
                }
                if (state.start != null && state.end != null) {
                    Surface(
                        shadowElevation = 2.dp,
                        shape = RoundedCornerShape(12.dp),
                        color = GhColors.Surface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(CoreR.string.plan_both_set), modifier = Modifier.padding(12.dp))
                    }
                }
                // pickbar：当前位置 / 重新规划·重置 / 下一步（原型 P-03）
                Surface(
                    shadowElevation = 2.dp,
                    shape = RoundedCornerShape(12.dp),
                    color = GhColors.Surface,
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.useCurrentLocationAsStart() },
                            enabled = !state.locating,
                        ) {
                            Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(CoreR.string.plan_use_current))
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { viewModel.replan() },
                                enabled = state.start != null && state.end != null, // F-PLAN-16 重新规划
                            ) {
                                Text(stringResource(CoreR.string.plan_replan))
                            }
                            OutlinedButton(onClick = { viewModel.reset() }) {
                                Text(stringResource(CoreR.string.plan_reset))
                            }
                            Button(
                                onClick = { viewModel.replan() },
                                enabled = state.start != null && state.end != null,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(CoreR.string.plan_next))
                            }
                        }
                    }
                }
            }
        }
    }

    // F-PLAN-08：POI 气泡——选点态「设为起点/终点」；手动态「添加途经点」（VM 按模式分流）
    poiCandidate?.let { (name, latLng) ->
        AlertDialog(
            onDismissRequest = { poiCandidate = null },
            title = { Text(name) },
            text = {
                Text(
                    stringResource(
                        if (state.manualMode) CoreR.string.plan_manual_hint
                        else CoreR.string.plan_poi_dialog_message,
                    ),
                )
            },
            confirmButton = {
                if (state.manualMode) {
                    TextButton(onClick = {
                        viewModel.onPoiChosen(name, latLng, SelectTarget.START) // manualMode 下被分流为 addWaypoint
                        poiCandidate = null
                    }) { Text(stringResource(CoreR.string.plan_poi_add_waypoint)) }
                } else {
                    TextButton(onClick = {
                        viewModel.onPoiChosen(name, latLng, SelectTarget.START)
                        poiCandidate = null
                    }) { Text(stringResource(CoreR.string.plan_poi_set_start)) }
                }
            },
            dismissButton = {
                if (state.manualMode) {
                    TextButton(onClick = { poiCandidate = null }) {
                        Text(stringResource(CoreR.string.common_action_cancel))
                    }
                } else {
                    TextButton(onClick = {
                        viewModel.onPoiChosen(name, latLng, SelectTarget.END)
                        poiCandidate = null
                    }) { Text(stringResource(CoreR.string.plan_poi_set_end)) }
                }
            },
        )
    }

    // F-PLAN-40：保存命名 + 备注（P-06：线路名称 / 备注 / 全程汇总）
    if (showSaveDialog) {
        var nameInput by rememberSaveable { mutableStateOf(state.manualWaypoints.lastOrNull()?.name.orEmpty()) }
        var noteInput by rememberSaveable { mutableStateOf("") }
        val outbound = state.candidates.getOrNull(state.chosenIndex)
        val mm = state.manualMetrics
        val rp = state.returnPath
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text(stringResource(CoreR.string.plan_manual_save_dialog_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        placeholder = { Text(stringResource(CoreR.string.plan_manual_name_hint)) },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = noteInput,
                        onValueChange = { noteInput = it },
                        placeholder = { Text(stringResource(CoreR.string.plan_note_hint)) },
                        minLines = 2,
                    )
                    if (!state.manualMode && outbound != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(CoreR.string.plan_leg_out) + "：" +
                                Formatters.distanceText(outbound.path.distanceM.toDouble()) + " · " +
                                Formatters.durationText(outbound.path.durationS),
                            fontSize = 12.sp,
                            color = GhColors.TextSecondary,
                        )
                        if (state.returnEnabled && rp != null) {
                            Text(
                                text = stringResource(CoreR.string.plan_leg_ret) + "：" +
                                    Formatters.distanceText(rp.distanceM.toDouble()) + " · " +
                                    Formatters.durationText(rp.durationS),
                                fontSize = 12.sp,
                                color = GhColors.TextSecondary,
                            )
                        }
                        Text(
                            text = stringResource(CoreR.string.plan_difficulty_label) + "：" +
                                (outbound.difficulty?.let { difficultyLabel(it) }
                                    ?: stringResource(CoreR.string.common_stat_unknown)),
                            fontSize = 12.sp,
                            color = GhColors.TextSecondary,
                        )
                    } else if (mm != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = Formatters.distanceText(mm.distanceM) +
                                " · " + Formatters.durationText(mm.estimatedDurationSec),
                            fontSize = 12.sp,
                            color = GhColors.TextSecondary,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSaveDialog = false
                    val name = nameInput.trim().ifEmpty { null }
                    val note = noteInput.trim().ifEmpty { null }
                    if (state.manualMode) viewModel.saveManual(name, note) else viewModel.savePlan(name, note)
                }) { Text(stringResource(CoreR.string.common_action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text(stringResource(CoreR.string.common_action_cancel))
                }
            },
        )
    }
    // F-PLAN-27：删除指定途经点确认
    deleteWaypointIdx?.let { idx ->
        AlertDialog(
            onDismissRequest = { deleteWaypointIdx = null },
            title = { Text(stringResource(CoreR.string.plan_manual_delete_waypoint)) },
            text = { Text("#${idx + 1}") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeWaypoint(idx)
                    deleteWaypointIdx = null
                }) { Text(stringResource(CoreR.string.common_action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteWaypointIdx = null }) {
                    Text(stringResource(CoreR.string.common_action_cancel))
                }
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

/** F-PLAN-12/14：单张候选卡片（P-04 原型：白卡、选中蓝描边+对勾、难度标签、距离/耗时/爬升行） */
@Composable
private fun CandidateCard(
    candidate: RouteCandidate,
    chosen: Boolean,
    saved: Boolean,
    onSelect: () -> Unit,
    onSave: () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = GhColors.Surface,
        border = BorderStroke(2.dp, if (chosen) GhColors.Primary else Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = Formatters.distanceText(candidate.path.distanceM.toDouble()),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = GhColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                candidate.difficulty?.let { d ->
                    val hard = d == Difficulty.HARD || d == Difficulty.CHALLENGING
                    Text(
                        text = difficultyLabel(d),
                        fontSize = 10.sp,
                        color = if (hard) GhColors.TagOrangeFg else GhColors.TagGreenFg,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (hard) GhColors.TagOrangeBg else GhColors.TagGreenBg)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (chosen && !saved) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = GhColors.Primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row {
                Text(
                    text = Formatters.durationText(candidate.path.durationS),
                    fontSize = 12.sp,
                    color = GhColors.TextSecondary,
                )
                Spacer(Modifier.width(12.dp))
                val ascent = candidate.metrics?.ascentM
                Text(
                    // F-PLAN-46：高程为 DEM/远程估算，必须标注「估算」；不可用显示「—」，绝不编造
                    text = if (ascent != null) {
                        stringResource(CoreR.string.plan_estimated_ascent, ascent.toInt())
                    } else {
                        stringResource(CoreR.string.common_stat_unknown)
                    },
                    fontSize = 12.sp,
                    color = GhColors.TextSecondary,
                )
            }
            if (saved) {
                Text(
                    text = stringResource(CoreR.string.plan_saved),
                    fontSize = 12.sp,
                    color = GhColors.Primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else if (chosen) {
                TextButton(onClick = onSave) {
                    Text(stringResource(CoreR.string.plan_save_plan))
                }
            }
        }
    }
}
@Composable
private fun difficultyLabel(d: Difficulty): String = when (d) {
    Difficulty.EASY -> stringResource(CoreR.string.diff_easy)
    Difficulty.MODERATE -> stringResource(CoreR.string.diff_moderate)
    Difficulty.HARD -> stringResource(CoreR.string.diff_hard)
    Difficulty.CHALLENGING -> stringResource(CoreR.string.diff_challenging)
}

/** F-PLAN-08 双回调同触窗口（M0 真机实测：POI 与空白点选回调在 600ms 内先后触发） */
private const val POI_WIN_MS = 600L

/** 双点包含框的屏幕边距（px） */
private const val BOUNDS_PADDING_PX = 120

/** 候选线路配色（F-PLAN-12）：橙 / 紫 / 绿。Polyline 无 setAlpha（D-17 javap 结论）：
 *  未高亮线路的半透明用 ARGB color int 的 alpha 位（CANDIDATE_DIM_ALPHA）实现。 */
private val CANDIDATE_COLORS = intArrayOf(0xFFF2994A.toInt(), 0xFF9B51E0.toInt(), 0xFF27AE60.toInt())
private const val CANDIDATE_DIM_ALPHA = 0x66

/** 已选定线路（F-PLAN-15）：蓝色实线 */
private val SELECTED_COLOR = 0xFF2F80ED.toInt()
private const val NORMAL_WIDTH_DP = 6f
private const val HIGHLIGHT_WIDTH_DP = 10f
private const val SELECTED_WIDTH_DP = 8f

/** 手动线路（F-PLAN-23/24）：紫色系与推荐色板区分；直线段虚线 */
private val MANUAL_COLOR = 0xFF9B51E0.toInt()
private const val MANUAL_WIDTH_DP = 6f

/** 返程（F-PLAN-36）：深蓝 #1B4F9C 虚线，与去程蓝实线同色系不同明度 */
private val RETURN_COLOR = 0xFF1B4F9C.toInt()
private const val RETURN_WIDTH_DP = 6f

/** F-PLAN-22：途经点序号圆图标（自绘 bitmap，64px 足够 marker 缩放） */
private fun numberedMarkerBitmap(number: Int): android.graphics.Bitmap {
    val size = 64
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9B51E0.toInt()
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2f, paint)
    paint.color = android.graphics.Color.WHITE
    paint.textSize = size * 0.5f
    paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
    paint.textAlign = android.graphics.Paint.Align.CENTER
    val baseline = size / 2f - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(number.toString(), size / 2f, baseline, paint)
    return bitmap
}

/** P-03/P-05 原型 seg 控件（Surface2 底、选中白底浮起）。 */
@Composable
private fun SegButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) GhColors.Surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) GhColors.TextPrimary else GhColors.TextSecondary,
            maxLines = 1,
        )
    }
}
