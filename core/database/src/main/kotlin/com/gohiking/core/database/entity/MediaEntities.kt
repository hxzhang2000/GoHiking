package com.gohiking.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 已关联到记录的媒体引用（PRD 7.1）。不复制原文件，只存 content:// URI。[mediaType]：IMAGE / VIDEO */
@Entity(
    tableName = "media_ref",
    indices = [Index(value = ["tripId"]), Index(value = ["timestamp"])],
)
data class MediaRefEntity(
    @PrimaryKey val id: String, // UUID
    val tripId: String?, // 可空：未关联任何记录的媒体
    val uri: String, // content:// URI
    val fileName: String?, // 扫描时记录的原始文件名（导出 JSON media[].fileName 来源，H-5）
    val note: String?, // 用户备注（导出 JSON media[].note 来源，H-5）
    val mediaType: String,
    val timestamp: Long,
    val latitude: Double?, // GCJ-02
    val longitude: Double?, // GCJ-02
    val durationMs: Long?, // 视频时长
    val sizeBytes: Long?,
)

/**
 * 全库媒体索引（DEV §3.1.1 ②）——F-MEDIA-09 扫描缓存，独立于 trip。
 * 复合主键 (mediaStoreId, mediaType)：MediaStore 的 _ID 在 images/video 两表各自唯一可能重号；
 * 必须是主键而非唯一索引，@Upsert 才能按主键判定插入/更新（DEV §9.5-A2）。
 */
@Entity(
    tableName = "media_index",
    primaryKeys = ["mediaStoreId", "mediaType"],
    indices = [Index(value = ["dateTakenMs"])],
)
data class MediaIndexEntity(
    val mediaStoreId: Long,
    val mediaType: String, // IMAGE / VIDEO
    val dateModifiedMs: Long, // MediaStore DATE_MODIFIED（增量扫描指纹）
    val uri: String,
    val dateTakenMs: Long?,
    val latWgs84: Double?, // 原始 WGS-84
    val lngWgs84: Double?,
    val latGcj02: Double?, // 转换后，供地图直接用
    val lngGcj02: Double?,
    val isApproximate: Boolean, // 未授 ACCESS_MEDIA_LOCATION 时为 true
    val durationMs: Long?,
    val sizeBytes: Long?,
    val indexedAt: Long,
)

/** 媒体索引指纹行（增量扫描比对用） */
data class MediaFingerprintRow(
    val mediaStoreId: Long,
    val dateModifiedMs: Long,
)

/** 媒体索引全键行（F-MEDIA-09：复合主键含 mediaType，增量比对与删除同步需要完整键） */
data class MediaIndexKeyRow(
    val mediaStoreId: Long,
    val mediaType: String,
    val dateModifiedMs: Long,
)
