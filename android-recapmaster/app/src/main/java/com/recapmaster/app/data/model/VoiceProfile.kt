package com.recapmaster.app.data.model

enum class TtsEngine(val id: String, val displayName: String, val badge: String) {
    EDGE_TTS("edge", "Microsoft Edge Neural", "Edge TTS (Free)"),
    GEMINI_VOICE("gemini", "Google Gemini AI Voice", "Gemini API"),
    GOOGLE_CLOUD_TTS("google_cloud", "Google Cloud TTS", "Google Cloud")
}

data class VoiceProfile(
    val id: String,
    val name: String,
    val subtitle: String,
    val engine: TtsEngine,
    val voiceId: String,          // e.g. "my-MM-ThihaNeural", "Charon", "Puck", "my-MM-Standard-A"
    val gender: String,           // "Male" | "Female"
    val rate: String = "+0%",     // Speech rate, e.g. "+10%", "-5%"
    val pitch: String = "+0Hz",   // Speech pitch, e.g. "-2Hz", "+2Hz"
    val promptPersona: String = "", // Custom persona directive for Gemini voice synthesis
    val description: String = "",
    val previewSampleText: String = "မင်္ဂလာပါ RecapMaster မှ ကြိုဆိုပါတယ်။ ရုပ်ရှင်ဇာတ်လမ်းများကို အကောင်းဆုံး ပြန်လည်ပြောပြပေးနေပါတယ်။"
) {
    val isGemini: Boolean get() = engine == TtsEngine.GEMINI_VOICE
    val isGoogleCloud: Boolean get() = engine == TtsEngine.GOOGLE_CLOUD_TTS
    val isEdge: Boolean get() = engine == TtsEngine.EDGE_TTS
}

object VoiceProfiles {

    val PRESETS = listOf(
        VoiceProfile(
            id = "edge_thiha_cinematic",
            name = "Thiha (သီဟ)",
            subtitle = "Burmese Male • Cinematic Action",
            engine = TtsEngine.EDGE_TTS,
            voiceId = "my-MM-ThihaNeural",
            gender = "Male",
            rate = "+10%",
            pitch = "-2Hz",
            description = "Resonant, clear, fast-paced cinematic movie recap voice."
        ),
        VoiceProfile(
            id = "edge_nilar_expressive",
            name = "Nilar (နီလာ)",
            subtitle = "Burmese Female • Emotional Storyteller",
            engine = TtsEngine.EDGE_TTS,
            voiceId = "my-MM-NilarNeural",
            gender = "Female",
            rate = "+5%",
            pitch = "+0Hz",
            description = "Warm, expressive, captivating drama & suspense narration."
        ),
        VoiceProfile(
            id = "gemini_charon_thriller",
            name = "Gemini Charon (ချာရွန်)",
            subtitle = "Gemini AI • Deep Thriller & Suspense",
            engine = TtsEngine.GEMINI_VOICE,
            voiceId = "Charon",
            gender = "Male",
            promptPersona = "Speak in a deep, suspenseful, dramatic thriller movie narrator voice in Burmese.",
            description = "Low pitch, dramatic pauses, intense cinematic trailer presence."
        ),
        VoiceProfile(
            id = "gemini_puck_action",
            name = "Gemini Puck (ပတ်ခ်)",
            subtitle = "Gemini AI • Fast & Punchy Action",
            engine = TtsEngine.GEMINI_VOICE,
            voiceId = "Puck",
            gender = "Male",
            promptPersona = "Speak in an energetic, punchy, fast-paced action movie and anime recap narration voice in Burmese.",
            description = "Dynamic cadence, vibrant momentum, keeps audience hooked."
        ),
        VoiceProfile(
            id = "gemini_kore_warm",
            name = "Gemini Kore (ကိုရီ)",
            subtitle = "Gemini AI • Warm & Emotional Drama",
            engine = TtsEngine.GEMINI_VOICE,
            voiceId = "Kore",
            gender = "Female",
            promptPersona = "Speak in a warm, captivating, emotional drama storyteller voice in Burmese.",
            description = "Soft nuances, emotional depth, perfect for drama and romantic plots."
        ),
        VoiceProfile(
            id = "gemini_fenrir_epic",
            name = "Gemini Fenrir (ဖန်ရီယာ)",
            subtitle = "Gemini AI • Epic Blockbuster Male",
            engine = TtsEngine.GEMINI_VOICE,
            voiceId = "Fenrir",
            gender = "Male",
            promptPersona = "Speak in a commanding, epic, authoritative blockbuster narration voice in Burmese.",
            description = "Commanding authority, grand epic style for fantasy & sci-fi recaps."
        ),
        VoiceProfile(
            id = "gemini_aoede_mystery",
            name = "Gemini Aoede (အေးဒီး)",
            subtitle = "Gemini AI • Melodic Mystery & Horror",
            engine = TtsEngine.GEMINI_VOICE,
            voiceId = "Aoede",
            gender = "Female",
            promptPersona = "Speak in a mysterious, evocative, gripping thriller narration voice in Burmese.",
            description = "Atmospheric, spine-chilling suspense for mystery & horror recaps."
        ),
        VoiceProfile(
            id = "google_burmese_standard",
            name = "Google Standard (စံသတ်မှတ်)",
            subtitle = "Google Cloud TTS • Standard Burmese",
            engine = TtsEngine.GOOGLE_CLOUD_TTS,
            voiceId = "my-MM-Standard-A",
            gender = "Female",
            rate = "+0%",
            pitch = "+0Hz",
            description = "Standard Burmese neural voice via Google Cloud Text-to-Speech API."
        )
    )

    fun defaultProfile(): VoiceProfile = PRESETS[0]

    fun findById(id: String): VoiceProfile {
        return PRESETS.firstOrNull { it.id == id } ?: defaultProfile()
    }

    fun findByVoiceName(voiceName: String): VoiceProfile {
        return PRESETS.firstOrNull { it.voiceId == voiceName } ?: defaultProfile()
    }
}
