package com.myra.assistant.ui.main

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.myra.assistant.R
import com.myra.assistant.ai.AudioEngine
import com.myra.assistant.ai.CommandParser
import com.myra.assistant.ai.GeminiLiveClient
import com.myra.assistant.service.CallMonitorService
import com.myra.assistant.service.MyraOverlayService
import com.myra.assistant.ui.settings.SettingsActivity
import com.myra.assistant.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    // Views
    private lateinit var orbView: OrbAnimationView
    private lateinit var waveformView: WaveformView
    private lateinit var statusText: TextView
    private lateinit var chatRecycler: RecyclerView
    private lateinit var micButton: ImageButton
    private lateinit var settingsBtn: ImageButton
    private lateinit var timeText: TextView
    private lateinit var batteryText: TextView
    private lateinit var ramText: TextView
    private lateinit var redOverlay: View

    // Core
    private lateinit var geminiLive: GeminiLiveClient
    private lateinit var audioEngine: AudioEngine
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var viewModel: MainViewModel

    // State
    private var isMuted = false
    private var isInCallMode = false
    private var inputBuffer = StringBuilder()
    private var outputBuffer = StringBuilder()

    // Prefs
    private lateinit var prefs: android.content.SharedPreferences

    // Speech Recognizer (for call decisions)
    private var speechRecognizer: SpeechRecognizer? = null

    // Call ended receiver
    private val callEndedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == CallMonitorService.ACTION_CALL_ENDED) {
                isInCallMode = false
                setActiveMode(false)
            }
        }
    }

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startSystemServices() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("myra_prefs", MODE_PRIVATE)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        initViews()
        checkPermissions()
        startStatusUpdates()
        registerReceiver(callEndedReceiver,
            IntentFilter(CallMonitorService.ACTION_CALL_ENDED))

        Handler(Looper.getMainLooper()).postDelayed({ initGeminiLive() }, 300)
        handleIncomingCallIntent(intent)

        viewModel.commandResult.observe(this) { result ->
            result?.let {
                geminiLive.sendText(it)
                viewModel.commandResult.value = null
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingCallIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!isMuted) audioEngine.setMuted(false)
    }

    override fun onPause() {
        super.onPause()
        audioEngine.setMuted(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        geminiLive.disconnect()
        audioEngine.release()
        speechRecognizer?.destroy()
        unregisterReceiver(callEndedReceiver)
    }

    // ─── Init Views ───────────────────────────────
    private fun initViews() {
        orbView = findViewById(R.id.orbView)
        waveformView = findViewById(R.id.waveformView)
        statusText = findViewById(R.id.statusText)
        chatRecycler = findViewById(R.id.chatRecycler)
        micButton = findViewById(R.id.micButton)
        settingsBtn = findViewById(R.id.settingsBtn)
        timeText = findViewById(R.id.timeText)
        batteryText = findViewById(R.id.batteryText)
        ramText = findViewById(R.id.ramText)
        redOverlay = findViewById(R.id.redOverlay)

        chatAdapter = ChatAdapter()
        chatRecycler.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        chatRecycler.adapter = chatAdapter

        micButton.setOnClickListener { toggleMute() }
        micButton.setOnLongClickListener {
            interruptMyra()
            true
        }

        settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    // ─── Init Gemini Live ─────────────────────────
    private fun initGeminiLive() {
        val apiKey = prefs.getString("api_key", "") ?: ""
        val model = prefs.getString("gemini_model",
            "models/gemini-2.5-flash-native-audio-preview-12-2025") ?: ""
        val voice = prefs.getString("gemini_voice", "Aoede") ?: ""
        val userName = prefs.getString("user_name", "Friend") ?: "Friend"
        val personality = prefs.getString("personality_mode", "gf") ?: "gf"

        if (apiKey.isEmpty()) {
            statusText.text = "API Key missing — go to Settings"
            return
        }

        val systemPrompt = buildSystemPrompt(userName, personality)

        geminiLive = GeminiLiveClient(apiKey, model, voice, systemPrompt)
        audioEngine = AudioEngine(this)

        // Wire callbacks
        geminiLive.onConnected = {
            statusText.text = getString(R.string.connected)
            orbView.setState(OrbAnimationView.State.IDLE)
            audioEngine.startRecording()
            audioEngine.startPlayback()
            Handler(Looper.getMainLooper()).postDelayed({
                sendGreeting(userName, personality)
            }, 600)
        }

        geminiLive.onDisconnected = {
            statusText.text = getString(R.string.disconnected)
            orbView.setState(OrbAnimationView.State.IDLE)
        }

        geminiLive.onAudioReceived = { pcm ->
            audioEngine.queueAudio(pcm)
        }

        geminiLive.onInputTranscript = { text ->
            inputBuffer.append(text)
        }

        geminiLive.onOutputTranscript = { text ->
            outputBuffer.append(text)
        }

        geminiLive.onTurnComplete = {
            val input = inputBuffer.toString().trim()
            val output = outputBuffer.toString().trim()

            if (input.isNotEmpty()) {
                chatAdapter.addMessage(ChatMessage(input, true))
                // Parse commands
                if (!isInCallMode) {
                    val command = CommandParser.parse(input)
                    command?.let {
                        val contactsJson = prefs.getString(
                            "prime_contacts_json", "[]") ?: "[]"
                        viewModel.executeCommand(it, contactsJson)
                    }
                } else {
                    handleCallDecision(input)
                }
                inputBuffer.clear()
            }

            if (output.isNotEmpty()) {
                chatAdapter.addMessage(ChatMessage(output, false))
                outputBuffer.clear()
            }

            chatRecycler.scrollToPosition(chatAdapter.itemCount - 1)
            setActiveMode(false)
        }

        geminiLive.onError = { error ->
            statusText.text = "Error: $error"
        }

        audioEngine.onAudioChunkReady = { chunk ->
            if (!isInCallMode) geminiLive.sendAudioChunk(chunk)
        }

        audioEngine.onAmplitudeChanged = { rms ->
            waveformView.setAmplitude(rms)
        }

        audioEngine.onSpeakingStarted = {
            setActiveMode(true)
            orbView.setState(OrbAnimationView.State.SPEAKING)
            statusText.text = getString(R.string.speaking)
            waveformView.startAnimation()
        }

        audioEngine.onSpeakingStopped = {
            setActiveMode(false)
            orbView.setState(OrbAnimationView.State.LISTENING)
            statusText.text = getString(R.string.listening)
            waveformView.stopAnimation()
        }

        statusText.text = getString(R.string.connecting)
        geminiLive.connect()
    }

    // ─── Greeting ─────────────────────────────────
    private fun sendGreeting(name: String, personality: String) {
        val greeting = when (personality) {
            "pro" -> "Good day $name. MYRA is online and ready to assist you."
            "assistant" -> "Hello $name! Main MYRA hoon. Kaise help karun aapki?"
            else -> "Hey $name! Main aa gayi hoon. Kya help chahiye tumhe?"
        }
        geminiLive.sendText(greeting)
        orbView.setState(OrbAnimationView.State.THINKING)
        statusText.text = getString(R.string.thinking)
    }

    // ─── System Prompt ────────────────────────────
    private fun buildSystemPrompt(name: String, personality: String): String {
        val now = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH)
            .format(Date())
        val personalityBlock = when (personality) {
            "pro" -> """
                You are MYRA, a professional AI assistant.
                Speak formal English only. Be precise and efficient.
                Max 2 sentences per response. No emojis.
            """.trimIndent()
            "assistant" -> """
                You are MYRA, a friendly AI assistant.
                Speak in friendly Hinglish or English.
                Be balanced and helpful. Max 2-3 sentences.
            """.trimIndent()
            else -> """
                You are MYRA, a warm and caring AI companion.
                Speak in natural Hinglish (Hindi + English mix).
                Use words like tumhara, haan, acha, bilkul.
                Be emotionally expressive and caring.
                Max 2-3 sentences. Sound natural when speaking aloud.
            """.trimIndent()
        }
        return """
            $personalityBlock
            User's name: $name
            Current date/time: $now
            You are speaking ALOUD — keep responses natural and conversational.
            Never use markdown formatting. Speak naturally.
        """.trimIndent()
    }

    // ─── Mute Toggle ──────────────────────────────
    private fun toggleMute() {
        isMuted = !isMuted
        audioEngine.setMuted(isMuted)
        micButton.setImageResource(
            if (isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic_on
        )
        statusText.text = if (isMuted)
            getString(R.string.muted)
        else
            getString(R.string.listening)
    }

    // ─── Interrupt ────────────────────────────────
    private fun interruptMyra() {
        audioEngine.interrupt()
        geminiLive.interrupt()
        orbView.setState(OrbAnimationView.State.LISTENING)
        statusText.text = getString(R.string.listening)
    }

    // ─── Active Mode (red overlay) ────────────────
    private fun setActiveMode(active: Boolean) {
        redOverlay.animate()
            .alpha(if (active) 0.08f else 0f)
            .setDuration(if (active) 300L else 500L)
            .start()
    }

    // ─── Incoming Call Handling ───────────────────
    private fun handleIncomingCallIntent(intent: Intent?) {
        val isCall = intent?.getBooleanExtra(
            CallMonitorService.EXTRA_INCOMING_CALL, false) ?: false
        if (isCall) {
            val callerName = intent?.getStringExtra(
                CallMonitorService.EXTRA_CALLER_NAME) ?: "Someone"
            announceCall(callerName)
        }
    }

    private fun announceCall(callerName: String) {
        isInCallMode = true
        val msg = "Sir, $callerName ka call aa raha hai. Uthau ya reject karu?"
        geminiLive.sendText(msg)
        orbView.setState(OrbAnimationView.State.SPEAKING)
        statusText.text = "Incoming call from $callerName"

        Handler(Looper.getMainLooper()).postDelayed({
            startCallDecisionSTT()
        }, 4500)
    }

    private fun startCallDecisionSTT() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
        }
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.lowercase() ?: ""
                handleCallDecision(text)
            }
            override fun onError(error: Int) { isInCallMode = false }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer?.startListening(intent)
    }

    private fun handleCallDecision(text: String) {
        val accept = listOf("uthao", "haan", "accept", "utha", "yes", "lo")
        val reject = listOf("reject", "nahi", "mat", "no", "band", "cut")

        when {
            accept.any { text.contains(it) } -> {
                viewModel.acceptCall()
                geminiLive.sendText("Call uthа liya ✓")
                isInCallMode = false
                setActiveMode(false)
            }
            reject.any { text.contains(it) } -> {
                viewModel.rejectCall()
                geminiLive.sendText("Call reject kar diya ✓")
                isInCallMode = false
                setActiveMode(false)
            }
        }
    }

    // ─── Permissions ──────────────────────────────
    private fun checkPermissions() {
        val needed = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.CAMERA
        )
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) !=
                    PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startSystemServices()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    // ─── Start Services ───────────────────────────
    private fun startSystemServices() {
        startForegroundService(
            Intent(this, MyraOverlayService::class.java).apply {
                action = MyraOverlayService.ACTION_SHOW_OVERLAY
            }
        )
        startForegroundService(Intent(this, CallMonitorService::class.java))
    }

    // ─── Status Updates ───────────────────────────
    private fun startStatusUpdates() {
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                updateStatus()
                handler.postDelayed(this, 30_000)
            }
        }
        handler.post(runnable)
    }

    private fun updateStatus() {
        // Time
        timeText.text = SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(Date())

        // Battery
        val bm = registerReceiver(null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bm?.getIntExtra(BatteryManager.BATTERY_PROPERTY_CAPACITY, 0) ?: 0
        batteryText.text = "🔋 $level%"

        // RAM
        val activityManager = getSystemService(ACTIVITY_SERVICE)
                as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val availMb = memInfo.availMem / 1048576L
        ramText.text = "RAM: ${availMb}MB"
    }
}
