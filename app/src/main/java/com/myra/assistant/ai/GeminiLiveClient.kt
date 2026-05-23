package com.myra.assistant.ai

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiLiveClient(
    private val apiKey: String,
    private val model: String,
    private val voice: String,
    private val systemPrompt: String
) {
    companion object {
        const val TAG = "GeminiLive"
        const val WS_URL = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1alpha." +
            "GenerativeService.BidiGenerateContent"
        const val SESSION_RENEW_AFTER = 540_000L
        const val KEEPALIVE_INTERVAL = 8_000L
    }

    // Callbacks
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onAudioReceived: ((ByteArray) -> Unit)? = null
    var onInputTranscript: ((String) -> Unit)? = null
    var onOutputTranscript: ((String) -> Unit)? = null
    var onTurnComplete: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var keepaliveJob: Job? = null
    private var renewJob: Job? = null
    private var isConnected = false

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // ─── Connect ──────────────────────────────────
    fun connect() {
        val url = "$WS_URL?key=$apiKey"
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened")
                isConnected = true
                sendSetupMessage()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $reason")
                isConnected = false
                CoroutineScope(Dispatchers.Main).launch { onDisconnected?.invoke() }
                scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                isConnected = false
                CoroutineScope(Dispatchers.Main).launch { onError?.invoke(t.message ?: "Unknown error") }
                scheduleReconnect()
            }
        })
    }

    // ─── Setup Message ────────────────────────────
    private fun sendSetupMessage() {
        val setup = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", model)
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", systemPrompt) })
                    })
                })
                put("generation_config", JSONObject().apply {
                    put("response_modalities", JSONArray().apply { put("AUDIO") })
                    put("speech_config", JSONObject().apply {
                        put("voice_config", JSONObject().apply {
                            put("prebuilt_voice_config", JSONObject().apply {
                                put("voice_name", voice)
                            })
                        })
                    })
                    put("temperature", 0.9)
                })
                put("output_audio_transcription", JSONObject())
                put("input_audio_transcription", JSONObject())
            })
        }
        webSocket?.send(setup.toString())
        startKeepalive()
        startSessionRenew()
        CoroutineScope(Dispatchers.Main).launch { onConnected?.invoke() }
    }

    // ─── Handle Incoming Message ──────────────────
    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val serverContent = json.optJSONObject("serverContent") ?: return

            // Audio
            val modelTurn = serverContent.optJSONObject("modelTurn")
            val parts = modelTurn?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    val inlineData = part.optJSONObject("inlineData")
                    if (inlineData != null) {
                        val data = inlineData.optString("data")
                        if (data.isNotEmpty()) {
                            val pcm = Base64.decode(data, Base64.DEFAULT)
                            CoroutineScope(Dispatchers.Main).launch {
                                onAudioReceived?.invoke(pcm)
                            }
                        }
                    }
                }
            }

            // Output Transcription (what MYRA said)
            val outputTrans = serverContent.optJSONObject("outputTranscription")
            val outputText = outputTrans?.optString("text")
            if (!outputText.isNullOrEmpty()) {
                CoroutineScope(Dispatchers.Main).launch {
                    onOutputTranscript?.invoke(outputText)
                }
            }

            // Input Transcription (what user said)
            val inputTrans = serverContent.optJSONObject("inputTranscription")
            val inputText = inputTrans?.optString("text")
            if (!inputText.isNullOrEmpty()) {
                CoroutineScope(Dispatchers.Main).launch {
                    onInputTranscript?.invoke(inputText)
                }
            }

            // Turn Complete
            val turnComplete = serverContent.optBoolean("turnComplete", false)
            if (turnComplete) {
                CoroutineScope(Dispatchers.Main).launch {
                    onTurnComplete?.invoke()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
        }
    }

    // ─── Send Mic Audio ───────────────────────────
    fun sendAudioChunk(pcmBytes: ByteArray) {
        if (!isConnected) return
        val b64 = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
        val msg = JSONObject().apply {
            put("realtime_input", JSONObject().apply {
                put("media_chunks", JSONArray().apply {
                    put(JSONObject().apply {
                        put("mime_type", "audio/pcm;rate=16000")
                        put("data", b64)
                    })
                })
            })
        }
        webSocket?.send(msg.toString())
    }

    // ─── Send Text ────────────────────────────────
    fun sendText(text: String) {
        if (!isConnected) return
        val msg = JSONObject().apply {
            put("client_content", JSONObject().apply {
                put("turns", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", text) })
                        })
                    })
                })
                put("turn_complete", true)
            })
        }
        webSocket?.send(msg.toString())
    }

    // ─── Interrupt ────────────────────────────────
    fun interrupt() {
        if (!isConnected) return
        val msg = JSONObject().apply {
            put("client_content", JSONObject().apply {
                put("turns", JSONArray())
                put("turn_complete", true)
            })
        }
        webSocket?.send(msg.toString())
    }

    // ─── Keepalive ────────────────────────────────
    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            while (isConnected) {
                delay(KEEPALIVE_INTERVAL)
                val silence = ByteArray(320) { 0 }
                sendAudioChunk(silence)
            }
        }
    }

    // ─── Session Renewal ──────────────────────────
    private fun startSessionRenew() {
        renewJob?.cancel()
        renewJob = scope.launch {
            delay(SESSION_RENEW_AFTER)
            Log.d(TAG, "Session renewing...")
            disconnect()
            delay(1000)
            connect()
        }
    }

    // ─── Reconnect ────────────────────────────────
    private fun scheduleReconnect() {
        scope.launch {
            delay(3000)
            Log.d(TAG, "Reconnecting...")
            connect()
        }
    }

    // ─── Disconnect ───────────────────────────────
    fun disconnect() {
        keepaliveJob?.cancel()
        renewJob?.cancel()
        isConnected = false
        webSocket?.close(1000, "Disconnected")
        webSocket = null
    }
}
