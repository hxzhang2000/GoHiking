package com.gohiking.core.common.geo

import com.gohiking.core.model.LatLngValue
import org.json.JSONArray

/**
 * planned_leg.polylineJson 的编解码（PRD 7.1：抽稀后的坐标点数组 JSON）。
 * 格式：[[lat,lng],[lat,lng],…]——最紧凑的数组形式，导出/导入（F-IO）按 7.3 schema 独立处理。
 * 用 org.json（Android 自带）避免给 core:common 引序列化插件。
 */
object PolylineJson {

    fun encode(points: List<LatLngValue>): String {
        val arr = JSONArray()
        for (p in points) {
            arr.put(JSONArray().put(p.latitude).put(p.longitude))
        }
        return arr.toString()
    }

    fun decode(json: String): List<LatLngValue> {
        val out = ArrayList<LatLngValue>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val pair = arr.getJSONArray(i)
            out.add(LatLngValue(pair.getDouble(0), pair.getDouble(1)))
        }
        return out
    }
}
