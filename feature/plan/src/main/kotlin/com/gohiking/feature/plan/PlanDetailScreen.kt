package com.gohiking.feature.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import com.gohiking.core.common.format.Formatters
import com.gohiking.core.common.geo.PolylineJson
import com.gohiking.core.database.dao.PlannedRouteDao
import com.gohiking.core.designsystem.theme.GhColors
import com.gohiking.core.elevation.ElevationRepository
import com.gohiking.core.map.route.RouteSearchClient
import com.gohiking.core.resources.R as CoreR

/**
 * 计划详情编辑页（P-07→详情，需求③）：
 * 地图上显示该计划去程（蓝实线）/ 返程（深蓝虚线）与可拖动起终点 marker；
 * 调整后「重新规划去程 / 原路返回 / 重新规划返程」重算；开关添加途经点后
 * 点地图加中间点；保存同事务替换 legs（爬升 DEM 重评，H-06 不可用为 null）。
 */
@Composable
fun PlanDetailScreen(
    routeId: String,
    routeDao: PlannedRouteDao,
    routeClient: RouteSearchClient,
    elevationRepository: ElevationRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewModel: PlanDetailViewModel = viewModel(
        key = routeId,
        factory = viewModelFactory {
            initializer {
                PlanDetailViewModel(
                    routeId = routeId,
                    routeDao = routeDao,
                    routeClient = routeClient,
                    queryElevation = elevationRepository::query,
                )
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 隐私合规必须早于 MapView 创建（红线，重复调用幂等）
    val trackWidthPx = with(LocalDensity.current) { 8.dp.toPx() }
    val planWidthPx = with(LocalDensity.current) { 6.dp.toPx() }
    var waypointMode by rememberSaveable { mutableStateOf(false) }

    val mapView = remember {
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)
        MapView(context).apply {
            onCreate(null)
            map.uiSettings.isZoomControlsEnabled = false
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
            // N-19：pause → destroy 顺序（与首页修法一致）
            mapView.onPause()
            mapView.onDestroy()
        }
    }

    // 折线：去程蓝实线 + 返程深蓝虚线（同 P-03/P-08 配色语义）
    LaunchedEffect(state.outboundPoints, state.returnPoints, trackWidthPx, planWidthPx) {
        val aMap = mapView.map
        aMap.clear()
        if (state.outboundPoints.size >= 2) {
            aMap.addPolyline(
                PolylineOptions()
                    .addAll(state.outboundPoints)
                    .width(trackWidthPx)
                    .color(0xFF2F80ED.toInt())
                    .zIndex(5f),
            )
        }
        if (state.returnPoints.size >= 2) {
            aMap.addPolyline(
                PolylineOptions()
                    .addAll(state.returnPoints)
                    .width(planWidthPx)
                    .color(0xFF1B4F9C.toInt())
                    .setDottedLine(true)
                    .zIndex(4f),
            )
        }
    }

    // marker：可拖动起终点（F-PLAN-06）+ 途经点序号；路线变更后重建
    val markerTargets = remember { mutableMapOf<Marker, SelectTarget>() }
    val allMarkers = remember { mutableListOf<Marker>() }
    LaunchedEffect(state.start, state.end, state.waypoints, state.outboundPoints, state.returnPoints) {
        val aMap = mapView.map
        allMarkers.forEach { it.remove() }
        allMarkers.clear()
        markerTargets.clear()
        state.start?.let { p ->
            val m = aMap.addMarker(
                MarkerOptions()
                    .position(p)
                    .title(context.getString(CoreR.string.plan_target_start))
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                    .draggable(true),
            )
            markerTargets[m] = SelectTarget.START
            allMarkers += m
        }
        state.end?.let { p ->
            val m = aMap.addMarker(
                MarkerOptions()
                    .position(p)
                    .title(context.getString(CoreR.string.plan_target_end))
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    .draggable(true),
            )
            markerTargets[m] = SelectTarget.END
            allMarkers += m
        }
        state.waypoints.forEachIndexed { i, wp ->
            val m = aMap.addMarker(
                MarkerOptions()
                    .position(wp)
                    .title((i + 1).toString())
                    .icon(BitmapDescriptorFactory.fromBitmap(numberedWaypointBitmap(i + 1)))
                    .draggable(false),
            )
            allMarkers += m
        }
        aMap.setOnMarkerDragListener(object : AMap.OnMarkerDragListener {
            override fun onMarkerDragStart(marker: Marker) = Unit
            override fun onMarkerDrag(marker: Marker) = Unit
            override fun onMarkerDragEnd(marker: Marker) {
                markerTargets[marker]?.let { target ->
                    if (target == SelectTarget.START) viewModel.moveStart(marker.position)
                    else viewModel.moveEnd(marker.position)
                }
            }
        })
        // 相机：包含全部路线点（首次加载即定位到计划位置）
        val pts = state.outboundPoints + state.returnPoints +
            listOfNotNull(state.start, state.end) + state.waypoints
        if (pts.isNotEmpty()) {
            val builder = LatLngBounds.Builder()
            pts.forEach { builder.include(it) }
            aMap.moveCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 120))
        }
    }

    // 地图点击：waypointMode 开启时添加中间点
    LaunchedEffect(waypointMode) {
        mapView.map.setOnMapClickListener { latLng ->
            if (waypointMode) viewModel.addWaypoint(latLng)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(GhColors.Bg)
            .statusBarsPadding(),
    ) {
        // appbar：返回 + 计划名
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(CoreR.string.common_action_back),
                    tint = GhColors.TextPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = state.routeName,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = GhColors.TextPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (state.dirty && !state.saving) {
                Text(
                    text = stringResource(CoreR.string.plan_detail_unsaved),
                    fontSize = 10.sp,
                    color = GhColors.TagOrangeFg,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(GhColors.TagOrangeBg)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        // 地图（需求③：路线 + 可拖动起终点 + 点选加中间点）
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            if (waypointMode) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color(0xCC1B1F24),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp),
                ) {
                    Text(
                        text = stringResource(CoreR.string.plan_manual_hint),
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
            if (state.planFailed) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = GhColors.TagRedBg,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = if (waypointMode) 44.dp else 8.dp),
                ) {
                    Text(
                        text = stringResource(CoreR.string.plan_route_failed),
                        color = GhColors.TagRedFg,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }

        // 底部控制卡
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = GhColors.Surface,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                // 段概要
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(CoreR.string.plan_leg_out) + " " +
                            Formatters.distanceText(state.outboundDistM) + " · " +
                            Formatters.durationText(state.outboundDurS),
                        fontSize = 12.sp,
                        color = GhColors.TextSecondary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Text(
                        text = stringResource(CoreR.string.plan_leg_ret) + " " +
                            (if (state.hasReturn) {
                                Formatters.distanceText(state.returnDistM) + " · " +
                                    Formatters.durationText(state.returnDurS)
                            } else {
                                stringResource(CoreR.string.common_stat_unknown)
                            }),
                        fontSize = 12.sp,
                        color = GhColors.TextSecondary,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = GhColors.Line2, thickness = 1.dp)
                Spacer(Modifier.height(8.dp))

                // 规划操作（需求③）
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.replanOutbound() },
                        enabled = !state.planning && !state.saving && state.start != null && state.end != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(CoreR.string.plan_detail_replan_out), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { viewModel.returnBack() },
                        enabled = !state.planning && !state.saving && state.outboundPoints.size >= 2,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(CoreR.string.plan_detail_return_back), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { viewModel.replanReturn() },
                        enabled = !state.planning && !state.saving && state.start != null && state.end != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(CoreR.string.plan_detail_replan_ret), maxLines = 1)
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = GhColors.Line2, thickness = 1.dp)
                Spacer(Modifier.height(8.dp))

                // 途经点（需求③：手动加中间点）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(CoreR.string.plan_detail_add_wp),
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(CoreR.string.plan_waypoint_count, state.waypoints.size),
                        fontSize = 12.sp,
                        color = GhColors.TextSecondary,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Switch(checked = waypointMode, onCheckedChange = { waypointMode = it })
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    OutlinedButton(
                        onClick = { viewModel.undoWaypoint() },
                        enabled = state.waypoints.isNotEmpty() && !state.saving,
                    ) {
                        Text(stringResource(CoreR.string.plan_manual_undo))
                    }
                    OutlinedButton(
                        onClick = { viewModel.clearWaypoints() },
                        enabled = state.waypoints.isNotEmpty() && !state.saving,
                    ) {
                        Text(stringResource(CoreR.string.plan_manual_clear))
                    }
                    Button(
                        onClick = { viewModel.save() },
                        enabled = state.dirty && !state.planning && !state.saving && state.outboundPoints.size >= 2,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (state.saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(
                                stringResource(
                                    if (state.dirty) CoreR.string.plan_detail_save else CoreR.string.plan_saved,
                                ),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun numberedWaypointBitmap(number: Int): android.graphics.Bitmap {
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