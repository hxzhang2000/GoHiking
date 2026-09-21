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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import com.amap.api.maps.model.PolylineOptions
import com.gohiking.core.common.format.Formatters
import com.gohiking.core.database.dao.PlannedRouteDao
import com.gohiking.core.designsystem.theme.GhColors
import com.gohiking.core.elevation.ElevationRepository
import com.gohiking.core.location.LocationProvider
import com.gohiking.core.map.route.RouteSearchClient
import com.gohiking.core.resources.R as CoreR

/**
 * 新建计划向导（docs/新建计划-交互设计.md）：五步（起点/终点/去程/返程/保存）
 * 步骤条可任意往返；草稿态不落库，步骤⑤统一保存；起终点变更触发失效联动。
 */
@Composable
fun PlanWizardScreen(
    routeDao: PlannedRouteDao,
    routeClient: RouteSearchClient,
    searchClient: com.gohiking.core.map.search.AmapSearchClient,
    locationProvider: LocationProvider,
    elevationRepository: ElevationRepository,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewModel: PlanWizardViewModel = viewModel(
        key = "wizard",
        factory = viewModelFactory {
            initializer {
                PlanWizardViewModel(
                    routeDao = routeDao,
                    routeClient = routeClient,
                    searchClient = searchClient,
                    locationProvider = locationProvider,
                    queryElevation = elevationRepository::query,
                    appContext = context.applicationContext,
                )
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDiscard by remember { mutableStateOf(false) }
    var deleteWpIndex by remember { mutableStateOf<Int?>(null) }

    val trackWidthPx = with(LocalDensity.current) { 8.dp.toPx() }
    val candWidthPx = with(LocalDensity.current) { 4.dp.toPx() }

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
            mapView.onPause()
            mapView.onDestroy()
        }
    }

    // 折线渲染：候选浅蓝 / 选中深蓝粗 / 手动段（吸附实线 直线虚线）/ 定稿去程 / 返程虚线；失效半透明
    LaunchedEffect(
        state.candidates, state.chosenIndex, state.manualSegments, state.smartMode,
        state.outboundPoints, state.outboundStale, state.returnPoints, state.returnStale,
        trackWidthPx, candWidthPx,
    ) {
        val aMap = mapView.map
        aMap.clear()
        state.candidates.forEachIndexed { i, c ->
            if (c.path.points.size >= 2) {
                val selected = i == state.chosenIndex
                aMap.addPolyline(
                    PolylineOptions()
                        .addAll(c.path.points)
                        .width(if (selected) trackWidthPx else candWidthPx)
                        .color(if (selected) 0xFF1B4F9C.toInt() else 0x995FB6F5.toInt())
                        .zIndex(if (selected) 6f else 3f),
                )
            }
        }
        state.manualSegments.forEach { seg ->
            if (seg.points.size >= 2) {
                aMap.addPolyline(
                    PolylineOptions()
                        .addAll(seg.points)
                        .width(candWidthPx)
                        .color(if (seg.snapped) 0xFF2F80ED.toInt() else 0xFF7FA6D9.toInt())
                        .setDottedLine(!seg.snapped)
                        .zIndex(4f),
                )
            }
        }
        if (state.outboundPoints.size >= 2 && state.outboundReady) {
            aMap.addPolyline(
                PolylineOptions()
                    .addAll(state.outboundPoints)
                    .width(trackWidthPx)
                    .color(if (state.outboundStale) 0x662F80ED.toInt() else 0xFF2F80ED.toInt())
                    .zIndex(5f),
            )
        }
        if (state.returnPoints.size >= 2) {
            aMap.addPolyline(
                PolylineOptions()
                    .addAll(state.returnPoints)
                    .width(candWidthPx * 1.5f)
                    .color(if (state.returnStale) 0x661B4F9C.toInt() else 0xFF1B4F9C.toInt())
                    .setDottedLine(true)
                    .zIndex(4f),
            )
        }
        val pts = state.candidates.flatMap { it.path.points } +
            state.manualSegments.flatMap { it.points } +
            state.outboundPoints + state.returnPoints +
            listOfNotNull(state.start?.latLng, state.end?.latLng) +
            state.manualWaypoints.map { it.latLng } + state.returnWaypoints.map { it.latLng }
        if (pts.isNotEmpty()) {
            val builder = LatLngBounds.Builder()
            pts.forEach { builder.include(it) }
            aMap.moveCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 120))
        }
    }

    // markers：起点绿 / 终点红（可拖，拖动触发失效联动）；途经点紫序号（可拖/点击删除）
    val markerRefs = remember { mutableListOf<Marker>() }
    LaunchedEffect(state.start, state.end, state.manualWaypoints, state.returnWaypoints) {
        val aMap = mapView.map
        markerRefs.forEach { it.remove() }
        markerRefs.clear()
        state.start?.let { p ->
            markerRefs += aMap.addMarker(
                MarkerOptions().position(p.latLng)
                    .title(context.getString(CoreR.string.plan_target_start))
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                    .draggable(true),
            )
        }
        state.end?.let { p ->
            markerRefs += aMap.addMarker(
                MarkerOptions().position(p.latLng)
                    .title(context.getString(CoreR.string.plan_target_end))
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    .draggable(true),
            )
        }
        state.manualWaypoints.forEachIndexed { i, wp ->
            markerRefs += aMap.addMarker(
                MarkerOptions().position(wp.latLng).title((i + 1).toString())
                    .icon(BitmapDescriptorFactory.fromBitmap(numberedWizardBitmap(i + 1)))
                    .draggable(true),
            )
        }
        aMap.setOnMarkerDragListener(object : AMap.OnMarkerDragListener {
            override fun onMarkerDragStart(marker: Marker) = Unit
            override fun onMarkerDrag(marker: Marker) = Unit
            override fun onMarkerDragEnd(marker: Marker) {
                when (marker.title) {
                    context.getString(CoreR.string.plan_target_start) -> viewModel.pickStart(PlanPoint(null, marker.position))
                    context.getString(CoreR.string.plan_target_end) -> viewModel.pickEnd(PlanPoint(null, marker.position))
                    else -> {
                        val idx = state.manualWaypoints.indexOfFirst { it.latLng == marker.position }
                        if (idx >= 0) viewModel.moveWaypoint(idx, marker.position)
                    }
                }
            }
        })
        aMap.setOnMarkerClickListener { marker ->
            val idx = state.manualWaypoints.indexOfFirst { it.latLng == marker.position }
            if (idx >= 0 && marker.title != context.getString(CoreR.string.plan_target_start) && marker.title != context.getString(CoreR.string.plan_target_end)) {
                deleteWpIndex = idx
                true
            } else {
                false
            }
        }
        aMap.setOnMapClickListener { latLng ->
            when (state.step) {
                WizardStep.START -> viewModel.pickStart(PlanPoint(null, latLng))
                WizardStep.END -> viewModel.pickEnd(PlanPoint(null, latLng))
                WizardStep.OUTBOUND -> if (!state.smartMode) viewModel.addWaypoint(latLng)
                WizardStep.RETURN -> if (state.returnMode == WizardReturnMode.MANUAL) viewModel.addReturnWaypoint(latLng)
                else -> Unit
            }
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(GhColors.Bg)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable { if (state.savedRouteId == null) showDiscard = true else onBack() },
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
                text = stringResource(CoreR.string.wizard_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = GhColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            if (state.savedRouteId == null) {
                TextButton(onClick = {
                    viewModel.completeCheck()?.let { missing -> viewModel.setStep(missing) } ?: viewModel.setStep(WizardStep.SAVE)
                }) {
                    Text(stringResource(CoreR.string.wizard_finish), fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WizardStep.entries.forEachIndexed { index, step ->
                val done = isStepDone(state, step)
                val current = state.step == step
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                current -> GhColors.Primary
                                done -> GhColors.TagBlueBg
                                else -> GhColors.Surface2
                            },
                        )
                        .clickable { viewModel.setStep(step) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = (index + 1).toString(),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            current -> Color.White
                            done -> GhColors.TagBlueFg
                            else -> GhColors.TextTertiary
                        },
                    )
                }
                if (index < WizardStep.entries.size - 1) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(2.dp)
                            .background(GhColors.Line),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            if (state.planning) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color(0xCC1B1F24),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(CoreR.string.plan_planning), color = Color.White, fontSize = 12.sp)
                    }
                }
            }
            if (state.outboundStale || state.returnStale) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                ) {
                    Text(
                        text = stringResource(CoreR.string.wizard_stale),
                        fontSize = 11.sp,
                        color = Color(0xFF5A616B),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }

        Surface(color = GhColors.Surface, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .navigationBarsPadding(),
            ) {
                when (state.step) {
                    WizardStep.START -> PointPickPanel(
                        step = state.step,
                        selected = state.start,
                        query = state.query,
                        suggestions = state.suggestions,
                        searching = state.searching,
                        searchHint = state.searchHint,
                        locating = state.locating,
                        onSearchInput = viewModel::onSearchInput,
                        onPick = viewModel::pickSuggestion,
                        onUseCurrent = viewModel::useCurrentLocation,
                        extraAction = null,
                    )

                    WizardStep.END -> PointPickPanel(
                        step = state.step,
                        selected = state.end,
                        query = state.query,
                        suggestions = state.suggestions,
                        searching = state.searching,
                        searchHint = state.searchHint,
                        locating = state.locating,
                        onSearchInput = viewModel::onSearchInput,
                        onPick = viewModel::pickSuggestion,
                        onUseCurrent = viewModel::useCurrentLocation,
                        extraAction = if (state.start != null) stringResource(CoreR.string.wizard_same_as_start) else null,
                        onExtra = { viewModel.copyStartToEnd() },
                    )

                    WizardStep.OUTBOUND -> OutboundPanel(state, viewModel)
                    WizardStep.RETURN -> ReturnPanel(state, viewModel)
                    WizardStep.SAVE -> SavePanel(state, viewModel, onSaved)
                }
            }
        }
    }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text(stringResource(CoreR.string.wizard_discard)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscard = false
                    onBack()
                }) { Text(stringResource(CoreR.string.wizard_discard_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscard = false }) {
                    Text(stringResource(CoreR.string.common_action_cancel))
                }
            },
        )
    }

    deleteWpIndex?.let { idx ->
        AlertDialog(
            onDismissRequest = { deleteWpIndex = null },
            title = { Text(stringResource(CoreR.string.plan_manual_delete_waypoint)) },
            text = { Text("#" + (idx + 1)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeWaypoint(idx)
                    deleteWpIndex = null
                }) { Text(stringResource(CoreR.string.common_action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteWpIndex = null }) {
                    Text(stringResource(CoreR.string.common_action_cancel))
                }
            },
        )
    }
}

private fun isStepDone(state: WizardUiState, step: WizardStep): Boolean = when (step) {
    WizardStep.START -> state.start != null
    WizardStep.END -> state.end != null
    WizardStep.OUTBOUND -> state.outboundReady
    WizardStep.RETURN -> state.returnReady || state.returnMode == WizardReturnMode.SKIPPED
    WizardStep.SAVE -> state.savedRouteId != null
}
/** 步骤①/② 面板：搜索 + 联想 + 当前位置 +（终点）与起点相同 */
@Composable
private fun PointPickPanel(
    step: WizardStep,
    selected: PlanPoint?,
    query: String,
    suggestions: List<com.gohiking.core.map.search.Suggestion>,
    searching: Boolean,
    searchHint: String?,
    locating: Boolean,
    onSearchInput: (String) -> Unit,
    onPick: (Int) -> Unit,
    onUseCurrent: () -> Unit,
    extraAction: String?,
    onExtra: (() -> Unit)? = null,
) {
    Column {
        Text(
            text = stringResource(if (step == WizardStep.START) CoreR.string.plan_target_start else CoreR.string.plan_target_end) +
                " · " + (selected?.name ?: stringResource(CoreR.string.plan_point_unset)),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = GhColors.TextPrimary,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onSearchInput,
            placeholder = { Text(stringResource(CoreR.string.plan_search_hint)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            trailingIcon = {
                if (searching) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (searchHint != null) {
            Text(
                text = searchHint,
                fontSize = 12.sp,
                color = GhColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (suggestions.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .heightIn(max = 180.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(GhColors.Surface2),
            ) {
                suggestions.forEachIndexed { i, s ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(i) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            if (s.district.isNotBlank()) {
                                Text(s.district, fontSize = 11.sp, color = GhColors.TextSecondary, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            OutlinedButton(onClick = onUseCurrent, enabled = !locating) {
                if (locating) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(CoreR.string.wizard_use_current), maxLines = 1)
                }
            }
            if (extraAction != null && onExtra != null) {
                OutlinedButton(onClick = onExtra) {
                    Text(extraAction, maxLines = 1)
                }
            }
        }
    }
}

/** 步骤③ 面板：智能（chips）/ 手动（吸附-直线 + n/50 + 撤销/清空）+ 完成 */
@Composable
private fun OutboundPanel(state: WizardUiState, viewModel: PlanWizardViewModel) {
    Column {
        if (state.outboundStale) {
            Text(
                text = stringResource(CoreR.string.wizard_stale),
                fontSize = 12.sp,
                color = GhColors.TagRedFg,
            )
            Spacer(Modifier.height(4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.smartMode,
                onClick = { viewModel.setOutboundSmart(true) },
                label = { Text(stringResource(CoreR.string.wizard_smart)) },
            )
            FilterChip(
                selected = !state.smartMode,
                onClick = { viewModel.setOutboundSmart(false) },
                label = { Text(stringResource(CoreR.string.wizard_manual)) },
            )
        }
        Spacer(Modifier.height(8.dp))
        if (state.smartMode) {
            Button(
                onClick = { viewModel.planSmart() },
                enabled = !state.planning && state.start != null && state.end != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (state.candidates.isEmpty()) CoreR.string.wizard_plan_now else CoreR.string.plan_replan,
                    ),
                )
            }
            if (state.candidates.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(CoreR.string.wizard_candidates),
                    fontSize = 12.sp,
                    color = GhColors.TextSecondary,
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    items(state.candidates.size) { i ->
                        val chosen = i == state.chosenIndex
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (chosen) GhColors.TagBlueBg else GhColors.Surface2)
                                .clickable { viewModel.chooseCandidate(i) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = candidateLabelOf(i),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (chosen) GhColors.TagBlueFg else GhColors.TextPrimary,
                            )
                            Text(
                                text = Formatters.distanceText(state.candidates[i].path.distanceM.toDouble()),
                                fontSize = 10.sp,
                                color = GhColors.TextSecondary,
                            )
                        }
                    }
                }
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = state.manualConnect == ManualConnect.SNAP,
                    onClick = { viewModel.setManualConnect(ManualConnect.SNAP) },
                    label = { Text(stringResource(CoreR.string.plan_manual_connect_snap)) },
                )
                FilterChip(
                    selected = state.manualConnect == ManualConnect.STRAIGHT,
                    onClick = { viewModel.setManualConnect(ManualConnect.STRAIGHT) },
                    label = { Text(stringResource(CoreR.string.plan_manual_connect_straight)) },
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(CoreR.string.plan_waypoint_count, state.manualWaypoints.size),
                    fontSize = 12.sp,
                    color = GhColors.TextSecondary,
                )
            }
            Text(
                text = stringResource(CoreR.string.plan_manual_hint),
                fontSize = 12.sp,
                color = GhColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                OutlinedButton(
                    onClick = { viewModel.undoWaypoint() },
                    enabled = state.manualWaypoints.isNotEmpty(),
                ) {
                    Text(stringResource(CoreR.string.plan_manual_undo))
                }
                OutlinedButton(
                    onClick = { viewModel.clearWaypoints() },
                    enabled = state.manualWaypoints.isNotEmpty(),
                ) {
                    Text(stringResource(CoreR.string.plan_manual_clear))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val ready = if (state.smartMode) {
            state.chosenIndex >= 0
        } else {
            state.manualWaypoints.isNotEmpty() || state.outboundPoints.isNotEmpty()
        }
        Button(
            onClick = { viewModel.finishOutbound() },
            enabled = ready && !state.planning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(CoreR.string.wizard_finish_outbound))
        }
    }
}

/** 步骤④ 面板：原路返回 / 智能 / 手动 / 单程 */
@Composable
private fun ReturnPanel(state: WizardUiState, viewModel: PlanWizardViewModel) {
    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            FilterChip(
                selected = state.returnMode == WizardReturnMode.BACK,
                onClick = { viewModel.setReturnMode(WizardReturnMode.BACK) },
                label = { Text(stringResource(CoreR.string.wizard_return_back), fontSize = 12.sp) },
            )
            FilterChip(
                selected = state.returnMode == WizardReturnMode.SMART,
                onClick = { viewModel.setReturnMode(WizardReturnMode.SMART) },
                label = { Text(stringResource(CoreR.string.wizard_return_smart), fontSize = 12.sp) },
            )
            FilterChip(
                selected = state.returnMode == WizardReturnMode.MANUAL,
                onClick = { viewModel.setReturnMode(WizardReturnMode.MANUAL) },
                label = { Text(stringResource(CoreR.string.wizard_return_manual), fontSize = 12.sp) },
            )
            FilterChip(
                selected = state.returnMode == WizardReturnMode.SKIPPED,
                onClick = { viewModel.setReturnMode(WizardReturnMode.SKIPPED) },
                label = { Text(stringResource(CoreR.string.wizard_return_skip), fontSize = 12.sp) },
            )
        }
        Spacer(Modifier.height(8.dp))
        when (state.returnMode) {
            WizardReturnMode.BACK -> Text(
                text = stringResource(CoreR.string.wizard_return_ready) + " · " +
                    Formatters.distanceText(state.returnDistM) + " · " + Formatters.durationText(state.returnDurS),
                fontSize = 12.sp,
                color = GhColors.TextSecondary,
            )
            WizardReturnMode.SKIPPED -> Text(
                text = stringResource(CoreR.string.wizard_return_skipped),
                fontSize = 12.sp,
                color = GhColors.TextSecondary,
            )
            WizardReturnMode.SMART -> Button(
                onClick = { viewModel.planReturnSmart() },
                enabled = !state.planning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(CoreR.string.wizard_plan_now))
            }
            WizardReturnMode.MANUAL -> {
                Text(
                    text = stringResource(CoreR.string.plan_manual_hint) + " · " +
                        stringResource(CoreR.string.plan_waypoint_count, state.returnWaypoints.size),
                    fontSize = 12.sp,
                    color = GhColors.TextSecondary,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    OutlinedButton(
                        onClick = { viewModel.undoReturnWaypoint() },
                        enabled = state.returnWaypoints.isNotEmpty(),
                    ) {
                        Text(stringResource(CoreR.string.plan_manual_undo))
                    }
                    OutlinedButton(
                        onClick = { viewModel.clearReturnWaypoints() },
                        enabled = state.returnWaypoints.isNotEmpty(),
                    ) {
                        Text(stringResource(CoreR.string.plan_manual_clear))
                    }
                }
            }
            null -> Text(
                text = stringResource(CoreR.string.wizard_return_pick),
                fontSize = 12.sp,
                color = GhColors.TextSecondary,
            )
        }
        if (state.returnMode == WizardReturnMode.SMART || state.returnMode == WizardReturnMode.MANUAL) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (state.returnMode == WizardReturnMode.SMART) viewModel.planReturnSmart() else viewModel.finishReturn()
                },
                enabled = !state.planning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(CoreR.string.wizard_finish_return))
            }
        }
    }
}

/** 步骤⑤ 面板：命名 + 备注 + 汇总 + 保存 */
@Composable
private fun SavePanel(state: WizardUiState, viewModel: PlanWizardViewModel, onSaved: () -> Unit) {
    if (state.savedRouteId != null) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(CoreR.string.wizard_saved),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = GhColors.Ok,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onSaved, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(CoreR.string.wizard_back_to_list))
            }
        }
        return
    }
    Column {
        Text(
            text = stringResource(CoreR.string.wizard_summary),
            fontSize = 12.sp,
            color = GhColors.TextSecondary,
        )
        Text(
            text = stringResource(CoreR.string.plan_leg_out) + " " +
                Formatters.distanceText(state.outboundDistM) + " · " +
                Formatters.durationText(state.outboundDurS),
            fontSize = 12.sp,
        )
        if (state.hasReturn) {
            Text(
                text = stringResource(CoreR.string.plan_leg_ret) + " " +
                    Formatters.distanceText(state.returnDistM) + " · " +
                    Formatters.durationText(state.returnDurS),
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::setName,
            placeholder = { Text(stringResource(CoreR.string.plan_manual_name_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = state.note,
            onValueChange = viewModel::setNote,
            placeholder = { Text(stringResource(CoreR.string.plan_note_hint)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { viewModel.save() },
            enabled = !state.saving && state.outboundReady,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.saving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(CoreR.string.plan_detail_save))
            }
        }
    }
}

/** 候选方案名（方案 A/B/C…，DEV §4.8 特征命名随候选卡批次统一接入） */
private fun candidateLabelOf(index: Int): String = "方案 " + ('A' + index)

/** 途经点序号 marker（紫色，与 PlanScreen 同款） */
private fun numberedWizardBitmap(number: Int): android.graphics.Bitmap {
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