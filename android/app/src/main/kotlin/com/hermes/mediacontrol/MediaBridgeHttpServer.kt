package com.hermes.mediacontrol

import com.hermes.mediacontrol.model.MediaState
import com.hermes.mediacontrol.model.PhoneStatus
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Lightweight embedded HTTP server (NanoHTTPD) that exposes two routes:
 *
 *   GET  /media/status  — returns current MediaState + PhoneStatus as JSON
 *   POST /media/command — accepts { "cmd": "<name>" } and dispatches to service
 *
 * Both routes add CORS headers so the G2 plugin's fetch() calls succeed
 * without origin restrictions.
 */
class MediaBridgeHttpServer(port: Int) : NanoHTTPD(port) {

    // Injected by MediaBridgeService after construction
    var getState:  (() -> Pair<MediaState, PhoneStatus>)? = null
    var onCommand: ((String) -> Unit)? = null

    override fun serve(session: IHTTPSession): Response {
        return when {
            session.method == Method.OPTIONS                              -> corsPreflightResponse()
            session.method == Method.GET  && session.uri == STATUS_PATH  -> handleStatus()
            session.method == Method.POST && session.uri == COMMAND_PATH -> handleCommand(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
                        .withCors()
        }
    }

    // ── GET /media/status ──────────────────────────────────────────────────────

    private fun handleStatus(): Response {
        val (media, phone) = getState?.invoke()
            ?: return serverError("Service not ready")

        val body = JSONObject().apply {
            put("playing",   media.playing)
            put("buffering", media.buffering)
            put("track",     media.track)
            put("artist",    media.artist)
            put("volume",    media.volume)
            put("phoneBat",  phone.battery)
            put("netByte",   phone.networkByte)
            put("inCall",    media.inCall)
        }.toString()

        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, body).withCors()
    }

    // ── POST /media/command ────────────────────────────────────────────────────

    private fun handleCommand(session: IHTTPSession): Response {
        // parseBody writes the raw POST body into files["postData"] for JSON content types
        val files = HashMap<String, String>()
        return try {
            session.parseBody(files)
            val postData = files["postData"] ?: ""
            val cmd = if (postData.isNotBlank()) {
                JSONObject(postData).optString("cmd", "")
            } else {
                ""
            }

            if (cmd.isNotEmpty()) {
                onCommand?.invoke(cmd)
                newFixedLengthResponse(Response.Status.OK, MIME_JSON, """{"ok":true}""").withCors()
            } else {
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, MIME_JSON,
                    """{"ok":false,"error":"missing cmd"}"""
                ).withCors()
            }
        } catch (e: ResponseException) {
            newFixedLengthResponse(e.status, MIME_JSON, """{"ok":false,"error":"parse error"}""")
                .withCors()
        } catch (e: Exception) {
            serverError(e.message ?: "unknown error")
        }
    }

    // ── CORS ───────────────────────────────────────────────────────────────────

    private fun corsPreflightResponse(): Response =
        newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "").withCors()

    private fun Response.withCors(): Response = apply {
        addHeader("Access-Control-Allow-Origin",  "*")
        addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        addHeader("Access-Control-Allow-Headers", "Content-Type")
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun serverError(message: String): Response =
        newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR, MIME_JSON,
            """{"ok":false,"error":"$message"}"""
        ).withCors()

    companion object {
        const val STATUS_PATH  = "/media/status"
        const val COMMAND_PATH = "/media/command"
        const val MIME_JSON    = "application/json"
    }
}
