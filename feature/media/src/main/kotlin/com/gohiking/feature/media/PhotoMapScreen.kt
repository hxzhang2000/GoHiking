package com.gohiking.feature.media

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.crossfade
import coil3.video.videoFrameMillis
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions
import com.gohiking.core.data.media.MediaClusterer
import com.gohiking.core.data.media.MediaRepository
import com.gohiking.core.database.entity.MediaIndexEntity
import com.gohiking.core.map.search.AmapSearchClient
import com.gohiking.core.resources.R as CoreR
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 照片地图页（P-12，F-MEDIA-10~15/20~25/30~34；P2 项 24/35/36/42 按 DEV §9.3 暂缓）。
 * - 权限（F-MEDIA-04/05）：媒体读取 + ACCESS_MEDIA_LOCATION，进页面按需申请；
 * - 聚合（F-MEDIA-11/12）：zoom 变化结束（防抖 150ms，DEV §4.6）→ MediaClusterer 网格聚类重建 marker；
 * - 气泡（F-MEDIA-13）四级直径；单点（F-MEDIA-14）异步换圆形缩略图，视频播放角标（F-MEDIA-15）；
 * - 点击聚合 → BottomSheet 缩略图条（F-MEDIA-20~23），选择缩略图时地图定位（F-MEDIA-21）；
 * - 点缩略图 → 全屏查看器（F-MEDIA-30~34）：双指缩放/双击放大 + 翻页 + 序号/拍摄时间。
 *   拖动平移（F-MEDIA-31 的一部分）在 v1.0 简化为随缩放视图整体缩放，平移手势与翻页冲突留 v1.1。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoMapScreen(
    mediaRepository: MediaRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appContext = LocalContext.current.applicationContext
    val viewModel: PhotoMapViewModel = viewModel(
        factory = viewModelFactory {
            initializer { PhotoMapViewModel(mediaRepository, appContext) }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val regeoClient = remember { AmapSearchClient(appContext) }

    // ---- 权限（F-MEDIA-04/05）----
    fun hasMedia(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(appContext, p) == PackageManager.PERMISSION_GRANTED
    }
    var mediaGranted by remember { mutableStateOf(hasMedia()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        mediaGranted = grants[Manifest.permission.READ_MEDIA_IMAGES] == true ||
            grants[Manifest.permission.READ_MEDIA_VIDEO] == true ||
            grants[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        if (mediaGranted) viewModel.onPermissionGranted()
    }
    LaunchedEffect(mediaGranted) {
        if (mediaGranted) viewModel.onPermissionGranted()
    }

    // ---- 地图（隐私红线：MapView 创建前调用 privacy 接口，幂等）----
    val mapView = remember {
        MapsInitializer.updatePrivacyShow(appContext, true, true)
        MapsInitializer.updatePrivacyAgree(appContext, true)
        MapView(appContext).apply {
            onCreate(null)
            map.uiSettings.isZoomControlsEnabled = false
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(35.86, 104.19), 4.5f))
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

    // ---- 聚合状态 ----
    var clusters by remember { mutableStateOf<List<MediaClusterer.Cluster>>(emptyList()) }
    var selected by remember { mutableStateOf<MediaClusterer.Cluster?>(null) }
    var viewerList by remember { mutableStateOf<List<MediaIndexEntity>?>(null) }
    var viewerIndex by remember { mutableStateOf(0) }
    val sheetState = rememberModalBottomSheetState()

    fun rebuildCluster(zoom: Float) {
        val photos = state.photos ?: return
        val cellPx = with(density) { 60.dp.toPx() } // DEV §4.6：必须密度换算（审阅 D8）
        clusters = MediaClusterer.cluster(photos, zoom, cellPx)
        selected = null // 视角变化后旧簇失效；F-MEDIA-21 定位由缩略图条点击重新触发
        renderMarkers(mapView.map, clusters, density.density, appContext, scope)
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { _ ->
                mapView.apply {
                    var debounceJob: Job? = null
                    var lastZoom = Float.NaN
                    map.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
                        override fun onCameraChange(position: CameraPosition?) = Unit
                        override fun onCameraChangeFinish(position: CameraPosition?) {
                            val zoom = position?.zoom ?: return
                            if (!zoom.isNaN() && zoom == lastZoom) return
                            lastZoom = zoom
                            debounceJob?.cancel()
                            debounceJob = scope.launch(Dispatchers.Main) {
                                delay(150) // DEV §4.6：zoom 变化结束防抖 150ms
                                rebuildCluster(zoom)
                            }
                        }
                    })
                    map.setOnMarkerClickListener { marker ->
                        marker.title?.toIntOrNull()?.let { idx ->
                            clusters.getOrNull(idx)?.let { c -> selected = c }
                        }
                        true
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 顶部返回 + 扫描进度（F-MEDIA-08）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp, start = 12.dp, end = 12.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(CoreR.string.common_action_back),
                    tint = Color.White,
                )
            }
            if (state.scanning) {
                Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 4.dp) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = stringResource(CoreR.string.photo_map_scanning, state.scanDone, state.scanTotal),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        LinearProgressIndicator(
                            progress = {
                                if (state.scanTotal > 0) state.scanDone.toFloat() / state.scanTotal else 0f
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        // F-MEDIA-05：模糊位置提示
        if (state.approximateCount > 0) {
            Surface(
                tonalElevation = 4.dp,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
            ) {
                Text(
                    text = stringResource(CoreR.string.photo_map_approximate, state.approximateCount),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // 无权限引导（F-MEDIA-04：媒体权限不进 P-01，进本页时按需申请）
        if (state.photos == null && !state.scanning) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text(
                        stringResource(CoreR.string.photo_map_need_permission),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(CoreR.string.photo_map_need_permission_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Button(
                        onClick = {
                            val perms = buildList {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    add(Manifest.permission.READ_MEDIA_IMAGES)
                                    add(Manifest.permission.READ_MEDIA_VIDEO)
                                } else {
                                    add(Manifest.permission.READ_EXTERNAL_STORAGE)
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    add(Manifest.permission.ACCESS_MEDIA_LOCATION)
                                }
                            }
                            permissionLauncher.launch(perms.toTypedArray())
                        },
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        Text(stringResource(CoreR.string.photo_map_grant))
                    }
                }
            }
        }

        // 空态：有权限但无带位置照片
        if (state.photos != null && !state.scanning && state.photos!!.isEmpty()) {
            Surface(
                tonalElevation = 4.dp,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(
                    text = stringResource(CoreR.string.photo_map_empty),
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    // ---- F-MEDIA-20~23 缩略图条（BottomSheet）----
    selected?.let { cluster ->
        ModalBottomSheet(onDismissRequest = { selected = null }, sheetState = sheetState) {
            ClusterSheet(
                cluster = cluster,
                regeoClient = regeoClient,
                onOpenViewer = { index ->
                    viewerList = cluster.items
                    viewerIndex = index
                },
                onLocate = { item ->
                    // F-MEDIA-21：选择缩略图时地图定位对应点
                    val la = item.latGcj02
                    val lo = item.lngGcj02
                    if (la != null && lo != null) {
                        mapView.map.moveCamera(CameraUpdateFactory.changeLatLng(LatLng(la, lo)))
                    }
                },
            )
        }
    }

    // ---- F-MEDIA-30~34 全屏查看器 ----
    viewerList?.let { list ->
        MediaViewerDialog(
            photos = list,
            initialIndex = viewerIndex,
            onDismiss = { viewerList = null },
        )
    }
}

/** 重建 marker：先清后加；单点簇异步换缩略图（IO 解码，主线程 setIcon） */
private fun renderMarkers(
    map: AMap,
    clusters: List<MediaClusterer.Cluster>,
    density: Float,
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    map.clear()
    val imageLoader = context.imageLoader
    clusters.forEachIndexed { index, c ->
        val marker = map.addMarker(
            MarkerOptions()
                .position(LatLng(c.latGcj02, c.lngGcj02))
                .anchor(0.5f, 0.5f)
                .setFlat(true)
                .title(index.toString()), // 簇下标走 title，点击回查（不展示）
        ) ?: return@forEachIndexed
        scope.launch(Dispatchers.IO) {
            val bmp: android.graphics.Bitmap? = if (c.items.size == 1) {
                MarkerBitmapFactory.thumbnail(context, imageLoader, c.items[0].uri)
                    ?.let { MarkerBitmapFactory.circleCrop(it) }
            } else {
                null
            }
            val icon = bmp ?: MarkerBitmapFactory.bubble(context, c.items.size, density)
            withContext(Dispatchers.Main) {
                if (!marker.isRemoved) marker.setIcon(BitmapDescriptorFactory.fromBitmap(icon))
            }
        }
    }
}

/** F-MEDIA-20~23：聚合气泡点击后的缩略图条（横向滑动 + 拍摄时间 + 逆地理位置，离线「—」） */
@Composable
private fun ClusterSheet(
    cluster: MediaClusterer.Cluster,
    regeoClient: AmapSearchClient,
    onOpenViewer: (Int) -> Unit,
    onLocate: (MediaIndexEntity) -> Unit,
) {
    val title = if (cluster.items.size == 1) {
        stringResource(CoreR.string.photo_map_single)
    } else {
        stringResource(CoreR.string.photo_map_cluster, cluster.items.size)
    }
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            items(cluster.items, key = { it.uri }) { m ->
                val index = cluster.items.indexOf(m)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(120.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                onLocate(m)
                                onOpenViewer(index)
                            },
                    ) {
                        val ctx = LocalContext.current
                        val model = remember(m.uri) {
                            coil3.request.ImageRequest.Builder(ctx)
                                .data(m.uri)
                                .apply { if (m.mediaType == "VIDEO") videoFrameMillis(0) }
                                .crossfade(true)
                                .build()
                        }
                        AsyncImage(
                            model = model,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (m.mediaType == "VIDEO") {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.align(Alignment.Center).size(32.dp),
                            )
                        }
                    }
                    Text(
                        text = formatTime(m),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    var place by remember(m.uri) { mutableStateOf<String?>(null) }
                    LaunchedEffect(m.uri) {
                        val la = m.latGcj02 ?: return@LaunchedEffect
                        val lo = m.lngGcj02 ?: return@LaunchedEffect
                        place = withContext(Dispatchers.IO) { regeoClient.regeocode(la, lo) }
                            ?: "" // null = 离线/失败 → 显示「—」（F-MEDIA-23）
                    }
                    Text(
                        text = place?.ifEmpty { "—" } ?: stringResource(CoreR.string.photo_map_locating),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** F-MEDIA-30~34：全屏查看器（双指缩放/双击放大 + 左右翻页 + 顶部序号/拍摄时间） */
@Composable
private fun MediaViewerDialog(
    photos: List<MediaIndexEntity>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        val pagerState = rememberPagerState(initialPage = initialIndex) { photos.size }
        var scale by remember { mutableStateOf(1f) }
        val ctx = LocalContext.current
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f }, // F-MEDIA-31 双击放大
                    )
                },
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val m = photos[page]
                val transformState = rememberTransformableState { zoomChange, _, _ ->
                    scale = (scale * zoomChange).coerceIn(1f, 5f) // F-MEDIA-31 双指缩放
                }
                val model = remember(m.uri) {
                    coil3.request.ImageRequest.Builder(ctx)
                        .data(m.uri)
                        .apply { if (m.mediaType == "VIDEO") videoFrameMillis(0) }
                        .crossfade(true)
                        .build()
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = model,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                            .transformable(transformState),
                    )
                    if (m.mediaType == "VIDEO") {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.align(Alignment.Center).size(48.dp),
                        )
                    }
                }
            }
            // F-MEDIA-34 顶部序号 + 拍摄时间
            Column(modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp)) {
                Text(
                    text = stringResource(
                        CoreR.string.photo_map_viewer_index,
                        pagerState.currentPage + 1,
                        photos.size,
                    ),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                )
                photos.getOrNull(pagerState.currentPage)?.let { m ->
                    Text(
                        text = formatTime(m),
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            // F-MEDIA-33 视频走系统播放器（内置播放器 v1.1）+ 关闭
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            ) {
                val current = photos.getOrNull(pagerState.currentPage)
                if (current?.mediaType == "VIDEO") {
                    TextButton(onClick = {
                        runCatching {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(android.net.Uri.parse(current.uri), "video/*")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            )
                        }
                    }) {
                        Text(stringResource(CoreR.string.photo_map_play_video), color = Color.White)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(CoreR.string.common_action_close), color = Color.White)
                }
            }
        }
    }
}

private fun formatTime(m: MediaIndexEntity): String {
    val t = m.dateTakenMs ?: m.dateModifiedMs
    if (t <= 0) return "—"
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(t))
}
