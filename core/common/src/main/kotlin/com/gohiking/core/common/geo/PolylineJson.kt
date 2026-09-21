package com.gohiking.core.common.geo

import com.gohiking.core.model.LatLngValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * planned_leg.polylineJson 的编解码（PRD 7.1：抽稀后的坐标点数组 JSON）。
 * 格式：`[[lat,lng],[lat,lng],…]`
 *
 * 历史与 N-36/N-37：
 * - 原先这里用 `org.json`（Android 自带）。两个后果：① 在 JVM 单测里调用会抛
 *   "Method ... not mocked"，**这个模块结构下根本没法给它写测试**；
 *   ② decode 完全没有防御，损坏数据一路抛 JSONException 到调用点。
 * - 同时 core:data 的 `IoCodecs.encodePolyline/decodePolyline` 是**另一套**独立实现
 *   （kotlinx + try/catch）。同一份磁盘格式两套编解码、且互不出测试。
 *   导入链路用 IoCodecs 写库、记录页用 PolylineJson 读库（`RecordingScreen`），
 *   任一侧格式漂移都会静默读到空折线或直接崩。
 *
 * 现在：这里是**唯一实现**（纯 Kotlin，可单测，全程防御），
 * `IoCodecs` 的两个方法已改为委托到本类。
 */
object PolylineJson {

    private val Json = Json { ignoreUnknownKeys = true }

    fun encode(points: List<LatLngValue>): String =
        "[" + points.joinToString(",") { "[${it.latitude},${it.longitude}]" } + "]"

    /**
     * 解码。**永不抛异常**：格式非法 / 元素不是数组 / 元素不足 2 个 / 非数字
     * 一律跳过（返回空列表或已解析出的部分），调用方按「无折线」处理。
     *
     * 逐元素容错而非整体 try/catch：这样即使中间夹了一个坏点，
     * 其余好点仍然能画出来（爬升/距离会偏，但用户看得到线路）。
     */
    fun decode(json: String?): List<LatLngValue> {
        if (json.isNullOrBlank()) return emptyList()
        val root = try {
            Json.parseToJsonElement(json) as? JsonArray ?: return emptyList()
        } catch (t: Throwable) {
            return emptyList()
        }
        val out = ArrayList<LatLngValue>(root.size)
        for (e in root) {
            val pair = e as? JsonArray ?: continue
            if (pair.size < 2) continue
            // doubleOrNull 同时接受 30 与 30.0（org.json 时代写进去的数据可能是整数形式）
            val lat = (pair[0] as? JsonPrimitive)?.doubleOrNull ?: continue
            val lng = (pair[1] as? JsonPrimitive)?.doubleOrNull ?: continue
            out.add(LatLngValue(lat, lng))
        }
        return out
    }
}
