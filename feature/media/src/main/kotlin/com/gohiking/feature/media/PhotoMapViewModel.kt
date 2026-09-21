package com.gohiking.feature.media

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gohiking.core.data.media.MediaRepository
import com.gohiking.core.database.entity.MediaIndexEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 照片地图页（P-12）状态。
 * - 权限：媒体读取（Android 13+ READ_MEDIA_IMAGES/VIDEO）+ 精确位置 ACCESS_MEDIA_LOCATION（F-MEDIA-04/05）；
 *   未授权 → photos = null，页面显示引导卡片（F-MEDIA-04 按需申请即在此页）；
 * - 扫描：进入页面增量扫描（F-MEDIA-08/09），进度条展示；
 * - 数据：located（有 GCJ-02 坐标）交给 Screen 用 MediaClusterer 聚类（zoom 相关，纯函数）。
 */
data class PhotoMapUiState(
    /** null = 无媒体权限（显示引导）；非 null = 已授权后的有坐标媒体 */
    val photos: List<MediaIndexEntity>? = null,
    val scanning: Boolean = false,
    val scanDone: Int = 0,
    val scanTotal: Int = 0,
    /** 未授 ACCESS_MEDIA_LOCATION 的照片数量（F-MEDIA-05 提示） */
    val approximateCount: Int = 0,
)

class PhotoMapViewModel(
    private val mediaRepository: MediaRepository,
    private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(PhotoMapUiState())
    val state: StateFlow<PhotoMapUiState> = _state.asStateFlow()

    private var scanJob: Job? = null

    /** 权限授予后调用（含重启本页时已授权的情形），幂等 */
    fun onPermissionGranted() {
        if (_state.value.photos != null || scanJob?.isActive == true) return
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(scanning = true) }
            try {
                // N-57：原实现用 runCatching 吞掉 rescan 异常，且 located() 在 try 之外裸调。
                // 一旦扫描或读取抛异常，scanning 会永远停在 true、photos 永远停在 null，
                // 页面卡在「扫描中」且无任何错误提示。这里改成显式 try/catch/finally：
                // 异常落日志，finally 保证 scanning 一定复位。
                mediaRepository.rescan(appContext) { p ->
                    _state.update { it.copy(scanDone = p.scanned, scanTotal = p.total) }
                }
                val located = mediaRepository.located()
                val approx = located.count { it.isApproximate }
                _state.update { it.copy(photos = located, approximateCount = approx) }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                Timber.e(t, "媒体扫描失败")
                // 扫描失败也要给出终态：空列表 + 页面显示「无照片」，而不是永远转圈
                _state.update { it.copy(photos = emptyList(), approximateCount = 0) }
            } finally {
                _state.update { it.copy(scanning = false) }
            }
        }
    }

    override fun onCleared() {
        scanJob?.cancel()
        super.onCleared()
    }
}
