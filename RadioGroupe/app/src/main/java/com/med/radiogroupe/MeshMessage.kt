package com.med.radiogroupe

import org.json.JSONObject

/**
 * Un message qui circule dans le groupe.
 * ttl = nombre de relais restants : chaque téléphone qui le reçoit
 * le renvoie à ses voisins avec ttl - 1, ce qui allonge la portée.
 */
data class MeshMessage(
    val type: String,
    val id: String,
    val from: String,
    val text: String,
    val time: Long,
    val ttl: Int
) {
    fun toJson(): String = JSONObject()
        .put("t", type)
        .put("id", id)
        .put("f", from)
        .put("x", text)
        .put("ts", time)
        .put("ttl", ttl)
        .toString()

    companion object {
        const val TEXT = "msg"
        const val HELLO = "hello"

        fun parse(json: String): MeshMessage? = try {
            val o = JSONObject(json)
            MeshMessage(
                type = o.getString("t"),
                id = o.getString("id"),
                from = o.getString("f").take(20),
                text = o.optString("x", "").take(1000),
                time = o.optLong("ts", 0L),
                ttl = o.optInt("ttl", 0).coerceIn(0, 10)
            )
        } catch (e: Exception) {
            null
        }
    }
}
