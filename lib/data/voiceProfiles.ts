export interface VoiceProfile {
  id: string;
  name: string;
  subtitle: string;
  engine: "edge" | "gemini" | "web_speech";
  voiceId: string;
  gender: "Male" | "Female";
  rate: string;
  pitch: string;
  promptPersona: string;
  description: string;
  previewSampleText: string;
}

export const VOICE_PROFILES: VoiceProfile[] = [
  {
    id: "edge_thiha_cinematic",
    name: "Thiha (သီဟ)",
    subtitle: "Burmese Male • Cinematic Action",
    engine: "edge",
    voiceId: "my-MM-ThihaNeural",
    gender: "Male",
    rate: "+10%",
    pitch: "-2Hz",
    promptPersona: "Resonant, clear, fast-paced cinematic movie recap voice.",
    description: "Resonant, clear, fast-paced cinematic movie recap voice.",
    previewSampleText: "မင်္ဂလာပါ RecapMaster မှ ကြိုဆိုပါတယ်။ ရုပ်ရှင်ဇာတ်လမ်းများကို အကောင်းဆုံး ပြန်လည်ပြောပြပေးနေပါတယ်။",
  },
  {
    id: "edge_nilar_expressive",
    name: "Nilar (နီလာ)",
    subtitle: "Burmese Female • Emotional Storyteller",
    engine: "edge",
    voiceId: "my-MM-NilarNeural",
    gender: "Female",
    rate: "+5%",
    pitch: "+0Hz",
    promptPersona: "Warm, expressive, captivating drama & suspense narration.",
    description: "Warm, expressive, captivating drama & suspense narration.",
    previewSampleText: "မင်္ဂလာပါရှင်၊ RecapMaster မှ ရုပ်ရှင်ဇာတ်လမ်းကောင်းများကို တင်ဆက်ပေးနေပါတယ်။",
  },
  {
    id: "gemini_charon_thriller",
    name: "Gemini Charon (ချာရွန်)",
    subtitle: "Gemini AI • Deep Thriller & Suspense",
    engine: "gemini",
    voiceId: "Charon",
    gender: "Male",
    rate: "+0%",
    pitch: "-4Hz",
    promptPersona: "Speak in a deep, suspenseful, dramatic thriller movie narrator voice in Burmese.",
    description: "Low pitch, dramatic pauses, intense cinematic trailer presence.",
    previewSampleText: "အမှောင်ထုထဲမှာ သူတို့ မသိခဲ့တဲ့ လျှို့ဝှက်ချက်တစ်ခု စတင်ခဲ့ပါတယ်...",
  },
  {
    id: "gemini_puck_action",
    name: "Gemini Puck (ပတ်ခ်)",
    subtitle: "Gemini AI • Fast & Punchy Action",
    engine: "gemini",
    voiceId: "Puck",
    gender: "Male",
    rate: "+15%",
    pitch: "+0Hz",
    promptPersona: "Speak in an energetic, punchy, fast-paced action movie recap narration voice in Burmese.",
    description: "Dynamic cadence, vibrant momentum, keeps audience hooked.",
    previewSampleText: "တစ်ခါတည်းနဲ့ ရန်သူတွေကို အပြတ်ရှင်းဖို့ သူတို့ အဆင်သင့်ဖြစ်နေပါပြီ!",
  },
  {
    id: "gemini_kore_warm",
    name: "Gemini Kore (ကိုရီ)",
    subtitle: "Gemini AI • Warm & Emotional Drama",
    engine: "gemini",
    voiceId: "Kore",
    gender: "Female",
    rate: "+0%",
    pitch: "+0Hz",
    promptPersona: "Speak in a warm, captivating, emotional drama storyteller voice in Burmese.",
    description: "Soft nuances, emotional depth, perfect for drama and romantic plots.",
    previewSampleText: "အချိန်တွေ ကုန်လွန်သွားပေမယ့် သူတို့ရဲ့ ချစ်ခြင်းမေတ္တာကတော့ မပြောင်းလဲခဲ့ပါဘူး...",
  },
  {
    id: "web_speech_default",
    name: "Browser Offline Voice",
    subtitle: "Universal Web Speech Synthesis",
    engine: "web_speech",
    voiceId: "default",
    gender: "Male",
    rate: "+0%",
    pitch: "+0Hz",
    promptPersona: "Built-in operating system text-to-speech engine.",
    description: "Fast, zero-network fallback narration.",
    previewSampleText: "This is a live preview of the web browser narration voice.",
  }
];
