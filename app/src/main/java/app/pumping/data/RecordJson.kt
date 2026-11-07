package app.pumping.data

import org.json.JSONObject

internal fun sessionJson(s: Session): JSONObject =
    JSONObject()
        .put("id", s.id)
        .put("start", s.start)
        .put("end", s.end)
        .put("activeSeconds", s.activeSeconds)
        .put("volume", s.volume)
        .put("left", s.left ?: JSONObject.NULL)
        .put("right", s.right ?: JSONObject.NULL)
        .put("notes", s.notes)
        .put("mode", s.mode)
        .put("created", s.created)
        .put("updated", s.updated)

internal fun bagJson(b: Bag): JSONObject =
    JSONObject()
        .put("id", b.id)
        .put("number", b.number)
        .put("stored", b.stored)
        .put("initial", b.initial)
        .put("location", b.location)
        .put("expires", b.expires)
        .put("notes", b.notes)
        .put("sourceSession", b.sourceSession ?: JSONObject.NULL)
