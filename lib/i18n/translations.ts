export type Language = "my" | "en";

export interface Translations {
  nav: {
    title: string;
    badge: string;
    engineSubtitle: string;
    zeroServerFees: string;
    earnTime: string;
    earnTimeShort: string;
    apiKeyConfigured: string;
    addApiKey: string;
    signIn: string;
    signOut: string;
  };
  viewport: {
    title: string;
    replaceVideo: string;
    dropzoneTitle: string;
    dropzoneDesc: string;
    liveIndicator: string;
    resolution: string;
    blurActive: string;
    fxActive: string;
    emptyTitle: string;
    emptyDesc: string;
  };
  tools: {
    tabBlur: string;
    tabCopyright: string;
    tabVoice: string;
    blurTitle: string;
    blurDesc: string;
    presetsLabel: string;
    posX: string;
    posY: string;
    boxWidth: string;
    boxHeight: string;
    blurStrength: string;
    copyrightTitle: string;
    copyrightDesc: string;
    hflip: string;
    zoomCrop: string;
    brightness: string;
    contrast: string;
    saturation: string;
    border: string;
    voiceTitle: string;
    voiceDesc: string;
    audition: string;
    auditioning: string;
    presets: {
      bottomSubs: string;
      lowerThird: string;
      topBar: string;
      topRightLogo: string;
      topLeftLogo: string;
      bottomRight: string;
      bottomLeft: string;
    };
  };
  action: {
    targetLangLabel: string;
    targetLangDesc: string;
    startDubbing: string;
    processing: string;
    langBurmese: string;
    langEnglish: string;
    langChinese: string;
    langThai: string;
  };
  pipeline: {
    title: string;
    subtitle: string;
    elapsed: string;
    estLeft: string;
    estLeftDone: string;
    progress: string;
    done: string;
    liveLogs: string;
    noLogsYet: string;
    steps: {
      extract: {
        step: string;
        title: string;
        tech: string;
        desc: string;
      };
      transcribe: {
        step: string;
        title: string;
        tech: string;
        desc: string;
      };
      script: {
        step: string;
        title: string;
        tech: string;
        desc: string;
      };
      render: {
        step: string;
        title: string;
        tech: string;
        desc: string;
      };
    };
    status: {
      standby: string;
      extracting: string;
      transcribing: string;
      generating: string;
      rendering: string;
      completed: string;
      error: string;
    };
  };
  output: {
    tabScript: string;
    tabTranscript: string;
    tabVideo: string;
    words: string;
    speech: string;
    copyTooltip: string;
    downloadTxt: string;
    downloadMp4: string;
    scriptEmptyTitle: string;
    scriptEmptyDesc: string;
    transcriptEmptyTitle: string;
    transcriptEmptyDesc: string;
  };
  keyModal: {
    title: string;
    subtitle: string;
    desc: string;
    inputLabel: string;
    noKey: string;
    getKey: string;
    cancel: string;
    save: string;
  };
  rewardModal: {
    title: string;
    desc: string;
    rewardLabel: string;
    rewardValue: string;
    fairCap: string;
    watchBtn: string;
    watchingTitle: string;
    watchingDesc: string;
    completedTitle: string;
    completedDesc: string;
    claimBtn: string;
    claiming: string;
  };
  youtube: {
    tabUpload: string;
    tabYoutube: string;
    inputPlaceholder: string;
    fetchBtn: string;
    fetching: string;
    downloadBtn: string;
    downloading: string;
    previewTitle: string;
    duration: string;
    author: string;
    invalidUrl: string;
    errorFetch: string;
    errorDownload: string;
    readyToProcess: string;
  };
}

export const translations: Record<Language, Translations> = {
  my: {
    nav: {
      title: "RecapMaster Studio",
      badge: "v2.5 WEB",
      engineSubtitle: "100% In-Browser AI စနစ်",
      zeroServerFees: "ဆာဗာကြေး အခမဲ့",
      earnTime: "+10m အချိန်ရယူမည်",
      earnTimeShort: "+10m",
      apiKeyConfigured: "API Key ထည့်ပြီး",
      addApiKey: "Gemini Key ထည့်ရန်",
      signIn: "အကောင့်ဝင်မည်",
      signOut: "ထွက်မည်",
    },
    viewport: {
      title: "၁။ ဗီဒီယို ပရီဗျူးနှင့် တိုက်ရိုက် ကြည့်ရှုကွက်",
      replaceVideo: "ဗီဒီယို အသစ်လဲမည်",
      dropzoneTitle: "ဒါဘင်သွင်းမည့် ဗီဒီယိုဖိုင်ကို ဤနေရာတွင် ရွေးချယ်ပါ",
      dropzoneDesc: "MP4, MKV, WebM, MOV ဖိုင်များ ထောက်ပံ့ထားပြီး WebGPU နှင့် FFmpeg WASM ဖြင့် သင့်ကွန်ပျူတာပေါ်တွင် တိုက်ရိုက် 100% လုံခြုံစွာ လုပ်ဆောင်ပေးပါသည်။",
      liveIndicator: "တိုက်ရိုက် ကြည့်ရှုကွက်",
      resolution: "ရုပ်ထွက်",
      blurActive: "Blur ဖွင့်ထားသည်",
      fxActive: "FX ဖွင့်ထားသည်",
      emptyTitle: "တိုက်ရိုက် ဗီဒီယို ကြည့်ရှုကွက်",
      emptyDesc: "ဗီဒီယို ရွေးချယ်ပြီးပါက Watermark Blur နေရာချထားမှုနှင့် Anti-Copyright အထူးပြုလုပ်ချက်များကို တိုက်ရိုက် ကြည့်ရှုနိုင်ပါသည်။",
    },
    tools: {
      tabBlur: "Blur Box (ဝါးခြင်း)",
      tabCopyright: "Anti-Copyright",
      tabVoice: "အသံပိုင်း ရွေးချယ်မှု",
      blurTitle: "Watermark & Subtitle Blur Box",
      blurDesc: "တီဗွီချန်နယ်လိုဂိုများ၊ TikTok အမှတ်အသားများနှင့် စာတန်းထိုးများကို ဝါးပေးမည့် Frosted Blur စနစ်",
      presetsLabel: "အသင့်သုံး နေရာသတ်မှတ်ချက်များ:",
      posX: "အလျားလိုက် တည်နေရာ (X)",
      posY: "ဒေါင်လိုက် တည်နေရာ (Y)",
      boxWidth: "အကွက်အကျယ် (Width)",
      boxHeight: "အကွက်အမြင့် (Height)",
      blurStrength: "ဝါးမှု အတိုင်းအတာ (Strength)",
      copyrightTitle: "Anti-Copyright Bypass FX",
      copyrightDesc: "Content ID စစ်ဆေးမှုများ လွတ်မြောက်စေရန် ဗီဒီယို၏ ဒစ်ဂျစ်တယ်ပုံစံကို လှည့်ပြောင်းပေးခြင်း",
      hflip: "ဘယ်/ညာ ဘေးတိုက်ပြောင်းပြန်လှန်ခြင်း (hflip)",
      zoomCrop: "ချဲ့၍ ဖြတ်ထုတ်ခြင်း (Zoom & Crop)",
      brightness: "အလင်းအမှောင် (Brightness)",
      contrast: "အရောင်ထင်ရှားမှု (Contrast)",
      saturation: "အရောင်စိုပြေမှု (Saturation)",
      border: "ဗီဒီယိုဘောင် အနားသတ် (Border)",
      voiceTitle: "ဒါဘင်ပြောကြားမည့် အသံပရိုဖိုင်",
      voiceDesc: "ရုပ်ရှင်ဇာတ်လမ်းပြော စတိုင်လ်အတွက် အထူးပြုပြင်ထားသော Neural အသံများ",
      audition: "အသံနမူနာ နားထောင်မည်",
      auditioning: "အသံနမူနာ ပြောကြားနေသည်...",
      presets: {
        bottomSubs: "အောက်ခြေ စာတန်းထိုး အပြည့်ဖုံးမည်",
        lowerThird: "အောက်ဘက် သုံးပုံတစ်ပုံ အပြည့်",
        topBar: "အပေါ်ဘားတန်း အပြည့်",
        topRightLogo: "ညာဘက်အပေါ် လိုဂို (Bilibili / TV)",
        topLeftLogo: "ဘယ်ဘက်အပေါ် လိုဂို (YouTube Icon)",
        bottomRight: "ညာဘက်အောက် (Brand / Time)",
        bottomLeft: "ဘယ်ဘက်အောက် (Platform Handle)",
      },
    },
    action: {
      targetLangLabel: "ဒါဘင် ပြန်ဆိုမည့် ဘာသာစကား:",
      targetLangDesc: "ရွေးချယ်ထားသော ဘာသာစကားဖြင့် ဇာတ်လမ်းဇာတ်ညွှန်း ရေးဖွဲ့ပြီး အသံထွက်ဖတ်ပေးမည် ဖြစ်ပါသည်။",
      startDubbing: "ဗီဒီယို အပြည့်အစုံ ဒါဘင်စတင်ပြုလုပ်မည် (Start Dubbing)",
      processing: "AI အဆင့်ဆင့်ဖြင့် လုပ်ဆောင်နေပါသည်...",
      langBurmese: "🇲🇲 မြန်မာဘာသာ (ရုပ်ရှင်ဇာတ်လမ်းပြော စတိုင်လ်)",
      langEnglish: "🇺🇸 English (YouTube Recap Narration)",
      langChinese: "🇨🇳 Chinese (中文电影解说)",
      langThai: "🇹🇭 Thai (ภาษาไทย)",
    },
    pipeline: {
      title: "ဗီဒီယို ဒါဘင် ပြုလုပ်မှု အဆင့်ဆင့် (Pipeline Engine)",
      subtitle: "AI စနစ်များဖြင့် တစ်ဆင့်ချင်းစီ တိုက်ရိုက်လုပ်ဆောင်မှု အခြေအနေ",
      elapsed: "ကြာချိန် (Elapsed)",
      estLeft: "ကျန်ချိန် (Est. Left)",
      estLeftDone: "ပြီးစီး",
      progress: "တိုးတက်မှု (Progress)",
      done: "ပြီးစီး",
      liveLogs: "လုပ်ဆောင်ချက် အသေးစိတ် မှတ်တမ်း (Live Process Logs)",
      noLogsYet: "လုပ်ဆောင်ချက် စတင်ချိန်တွင် အဆင့်ဆင့်မှတ်တမ်းများ ဤနေရာ၌ ပေါ်လာပါမည်။",
      steps: {
        extract: {
          step: "၀၁",
          title: "အသံဖိုင် သီးသန့်ခွဲထုတ်ခြင်း",
          tech: "FFmpeg WASM (16kHz PCM)",
          desc: "ဗီဒီယိုမှ စကားပြောသံများကို AI နားလည်နိုင်ရန် သီးသန့်ခွဲထုတ်ခြင်း",
        },
        transcribe: {
          step: "၀၂",
          title: "စကားပြောမှ စာသားဖတ်ယူခြင်း",
          tech: "Whisper WebGPU (On-Device AI)",
          desc: "ဖုန်း/ကွန်ပျူတာပေါ်တွင် စကားပြောများကို အချိန်မှတ်နှင့်တကွ ဖတ်ယူခြင်း",
        },
        script: {
          step: "၀၃",
          title: "မြန်မာဇာတ်ညွှန်း ရေးသားဖွဲ့စည်းခြင်း",
          tech: "Gemini 2.5 Flash AI",
          desc: "ဗီဒီယိုကြာချိန်အပြည့် အစမှအဆုံးထိ မြန်မာဘာသာပြန်နှင့် ဇာတ်ညွှန်းရေးဖွဲ့ခြင်း",
        },
        render: {
          step: "၀၄",
          title: "အသံဒါဘင်သွင်း၍ ဗီဒီယိုထုတ်လုပ်ခြင်း",
          tech: "Edge TTS + FFmpeg WASM",
          desc: "ရုပ်ရှင်အသံဖြင့် ဒါဘင်သွင်းပြီး Watermark ဖျက်ခြင်း၊ FX ထည့်သွင်း၍ Final Video ထုတ်လုပ်ခြင်း",
        },
      },
      status: {
        standby: "စောင့်ဆိုင်းဆဲ (STANDBY)",
        extracting: "အသံဖိုင် ထုတ်ယူနေသည်...",
        transcribing: "စကားပြော ဖတ်ယူနေသည်...",
        generating: "ဇာတ်ညွှန်း ရေးဖွဲ့နေသည်...",
        rendering: "ဗီဒီယို ဒါဘင်သွင်းနေသည်...",
        completed: "ပြီးစီးပါပြီ (COMPLETED)",
        error: "ချို့ယွင်းချက် (ERROR)",
      },
    },
    output: {
      tabScript: "မြန်မာဇာတ်ညွှန်း (Script)",
      tabTranscript: "မူရင်းစကားပြောမှတ်တမ်း (Transcript)",
      tabVideo: "ပြီးစီးသော ဗီဒီယို (Rendered Video)",
      words: "စာလုံးရေ",
      speech: "ကြာချိန်",
      copyTooltip: "စာသားအား ကော်ပီကူးမည်",
      downloadTxt: ".txt အဖြစ် ဒေါင်းလုဒ်ယူမည်",
      downloadMp4: "ဒါဘင်သွင်းပြီး ဗီဒီယို ဒေါင်းလုဒ်ရယူမည် (.mp4)",
      scriptEmptyTitle: "မြန်မာဇာတ်ညွှန်း စာသားများ ဤနေရာ၌ ပေါ်လာပါမည်",
      scriptEmptyDesc: "ဗီဒီယိုကို ရွေးချယ်ပြီး 'ဗီဒီယို အပြည့်အစုံ ဒါဘင်စတင်ပြုလုပ်မည်' ကို နှိပ်ပါက အစမှအဆုံးထိ ဇာတ်ညွှန်းကို ရေးဖွဲ့ပေးပါမည်။",
      transcriptEmptyTitle: "မူရင်းစကားပြော မှတ်တမ်း",
      transcriptEmptyDesc: "Whisper WebGPU ဖြင့် ဖတ်ယူထားသော စကားပြောစာသားများနှင့် အချိန်မှတ်များ ဤနေရာ၌ ပေါ်လာပါမည်။",
    },
    keyModal: {
      title: "Gemini API Key ထည့်သွင်းရန်",
      subtitle: "Google AI Studio • Flagship Flash Models",
      desc: "RecapMaster သည် မြန်မာဘာသာပြန်နှင့် ဇာတ်လမ်းဇာတ်ညွှန်းများကို သင့် Browser ပေါ်တွင် Gemini 2.5 / 2.0 Flash ဖြင့် ဆာဗာကြေး အခမဲ့ တိုက်ရိုက်လုပ်ဆောင်ပေးပါသည်။",
      inputLabel: "Google Gemini API Key:",
      noKey: "API Key မရှိသေးပါက အခမဲ့ ရယူရန်:",
      getKey: "Key ရယူရန်",
      cancel: "မလုပ်ဆောင်ပါ",
      save: "သိမ်းဆည်းမည် (Save Key)",
    },
    rewardModal: {
      title: "Earn 10 Minutes Free Time",
      desc: "RecapMaster ကို ကူညီအားဖြည့်ရန် ၁၅ စက္ကန့် ကြော်ငြာမက်ဆေ့ခ်ျကို ကြည့်ရှုပြီး အခမဲ့ AI ဗီဒီယိုဒါဘင်သွင်းခွင့် ၁၀ မိနစ် ချက်ချင်းရယူပါ။",
      rewardLabel: "အခမဲ့ရရှိမည့် အချိန်:",
      rewardValue: "+10 မိနစ် အခမဲ့",
      fairCap: "၂၄ နာရီ ကန့်သတ်ချက်",
      watchBtn: "စပွန်ဆာ ကြော်ငြာကြည့်ရှုမည် (၁၅ စက္ကန့်)",
      watchingTitle: "စပွန်ဆာ ကြော်ငြာ ပြသနေပါသည်...",
      watchingDesc: "အခမဲ့ ၁၀ မိနစ် ရရှိရန် စက္ကန့်အနည်းငယ် စောင့်ဆိုင်းပေးပါ။",
      completedTitle: "ဆုလာဘ် ရယူရန် အဆင်သင့်ဖြစ်ပါပြီ!",
      completedDesc: "RecapMaster ကို အားပေးသည့်အတွက် ကျေးဇူးတင်ပါသည်။ အောက်ပါခလုတ်ကို နှိပ်၍ သင့်အကောင့်ထဲသို့ ၁၀ မိနစ် ထည့်သွင်းပါ။",
      claimBtn: "+10 မိနစ် အခမဲ့ အချိန် ထည့်သွင်းမည်",
      claiming: "အချိန် ထည့်သွင်းနေသည်...",
    },
    youtube: {
      tabUpload: "📁 ဖိုင်တင်ရန်",
      tabYoutube: "🔴 YouTube လင့်ခ်",
      inputPlaceholder: "YouTube ဗီဒီယို လင့်ခ် ထည့်ပါ (ဥပမာ https://youtube.com/watch?v=...)",
      fetchBtn: "စစ်ဆေးမည်",
      fetching: "ရှာဖွေနေသည်...",
      downloadBtn: "ဗီဒီယို ရယူပြီး Studio သို့ထည့်မည်",
      downloading: "ဒေါင်းလုဒ်လုပ်နေသည်...",
      previewTitle: "YouTube ဗီဒီယို အချက်အလက်",
      duration: "ကြာချိန်",
      author: "Channel",
      invalidUrl: "မှန်ကန်သော YouTube လင့်ခ် မဟုတ်ပါ။",
      errorFetch: "YouTube ဗီဒီယို အချက်အလက် ရယူ၍ မရပါ။",
      errorDownload: "YouTube ဗီဒီယို ဒေါင်းလုဒ်လုပ်၍ မရပါ။",
      readyToProcess: "ဗီဒီယို အောင်မြင်စွာ ရယူပြီးပါပြီ။",
    },
  },
  en: {
    nav: {
      title: "RecapMaster Studio",
      badge: "v2.5 WEB",
      engineSubtitle: "100% In-Browser AI Engine",
      zeroServerFees: "$0 Server Compute",
      earnTime: "+10m Free Time",
      earnTimeShort: "+10m",
      apiKeyConfigured: "API Key Configured",
      addApiKey: "Add Gemini Key",
      signIn: "Sign In",
      signOut: "Sign Out",
    },
    viewport: {
      title: "1. Video Canvas & Live Viewport",
      replaceVideo: "Replace Video",
      dropzoneTitle: "Choose or Drop Video File Here",
      dropzoneDesc: "MP4, MKV, WebM, MOV. Processed entirely inside your browser with WebGPU and FFmpeg WASM.",
      liveIndicator: "LIVE VIEWPORT",
      resolution: "Resolution",
      blurActive: "Blur Active",
      fxActive: "FX Active",
      emptyTitle: "Real-Time Video Preview Canvas",
      emptyDesc: "Upload a video to preview live watermark blur positioning, copyright bypass FX, and audio-video alignment.",
    },
    tools: {
      tabBlur: "Blur Box",
      tabCopyright: "Anti-Copyright",
      tabVoice: "Voice FX",
      blurTitle: "Watermark & Subtitle Blur Box",
      blurDesc: "Frosted blur overlay to remove TV channel logos, TikTok watermarks, and hardcoded subtitles.",
      presetsLabel: "Quick Position Presets:",
      posX: "Position X (Horizontal)",
      posY: "Position Y (Vertical)",
      boxWidth: "Box Width",
      boxHeight: "Box Height",
      blurStrength: "Frosted Blur Strength",
      copyrightTitle: "Anti-Copyright Bypass FX",
      copyrightDesc: "Alters digital video fingerprint (horizontal flip, zoom crop, color curves, border) to pass Content ID algorithms.",
      hflip: "Mirror Horizontal Flip (hflip)",
      zoomCrop: "Zoom & Crop",
      brightness: "Brightness",
      contrast: "Contrast",
      saturation: "Saturation",
      border: "Canvas Border",
      voiceTitle: "Narration Voice Profile",
      voiceDesc: "Neural narrator voices tuned for cinematic movie recaps and storytelling.",
      audition: "Audition Voice",
      auditioning: "Speaking...",
      presets: {
        bottomSubs: "Full-Width Bottom (Cover Subs)",
        lowerThird: "Full-Width Lower Third",
        topBar: "Full-Width Top Bar",
        topRightLogo: "Top-Right Logo (Bilibili / TV)",
        topLeftLogo: "Top-Left Logo (YouTube Icon)",
        bottomRight: "Bottom-Right (Timestamp / Brand)",
        bottomLeft: "Bottom-Left (Platform Handle)",
      },
    },
    action: {
      targetLangLabel: "Target Dubbing Language:",
      targetLangDesc: "AI will write and narrate the complete story in this language.",
      startDubbing: "Generate Complete Dubbed Video",
      processing: "Processing In-Browser AI Pipeline...",
      langBurmese: "🇲🇲 Burmese (မြန်မာဘာသာ - Viral Movie Recap)",
      langEnglish: "🇺🇸 English (YouTube Recap Narration)",
      langChinese: "🇨🇳 Chinese (中文电影解说)",
      langThai: "🇹🇭 Thai (ภาษาไทย)",
    },
    pipeline: {
      title: "Pipeline Orchestration Engine",
      subtitle: "Real-time on-device AI pipeline execution & timing",
      elapsed: "Elapsed",
      estLeft: "Est. Left",
      estLeftDone: "Done",
      progress: "Progress",
      done: "Done",
      liveLogs: "Live Process Logs",
      noLogsYet: "Detailed stage logs will appear here once the process starts.",
      steps: {
        extract: {
          step: "01",
          title: "Audio Extract",
          tech: "FFmpeg WASM (16kHz PCM)",
          desc: "Extracts dialogue speech track for AI transcription",
        },
        transcribe: {
          step: "02",
          title: "Speech AI",
          tech: "Whisper WebGPU (On-Device AI)",
          desc: "Transcribes speech with exact timestamped chunks",
        },
        script: {
          step: "03",
          title: "Story Recap",
          tech: "Gemini 2.5 Flash AI",
          desc: "Generates full-duration translated recap narration script",
        },
        render: {
          step: "04",
          title: "Neural Dubbing",
          tech: "Edge TTS + FFmpeg WASM",
          desc: "Synthesizes voice, applies blur & FX, exports final video",
        },
      },
      status: {
        standby: "STANDBY",
        extracting: "Extracting Audio...",
        transcribing: "Transcribing Speech...",
        generating: "Writing Recap Script...",
        rendering: "Dubbing Video...",
        completed: "COMPLETED",
        error: "ERROR",
      },
    },
    output: {
      tabScript: "Recap Script",
      tabTranscript: "Whisper Transcript",
      tabVideo: "Rendered Video",
      words: "words",
      speech: "speech",
      copyTooltip: "Copy to Clipboard",
      downloadTxt: "Download .txt",
      downloadMp4: "Download Dubbed Video (.mp4)",
      scriptEmptyTitle: "Generated Narration Script",
      scriptEmptyDesc: "Select a video and click 'Generate Complete Dubbed Video' to produce a continuous story script translated into Burmese.",
      transcriptEmptyTitle: "Speech-to-Text Transcript",
      transcriptEmptyDesc: "Whisper WebGPU dialogue transcript with accurate timestamps will be displayed here.",
    },
    keyModal: {
      title: "Configure Gemini API Key",
      subtitle: "Google AI Studio • Flagship Flash Models",
      desc: "RecapMaster processes movie translation and scripts directly in your browser using the latest Gemini 2.5 / 2.0 Flash models with zero server compute fees.",
      inputLabel: "Google Gemini API Key:",
      noKey: "Don't have a key? It's free from Google:",
      getKey: "Get Key",
      cancel: "Cancel",
      save: "Save & Connect",
    },
    rewardModal: {
      title: "Earn 10 Minutes Free Time",
      desc: "Support RecapMaster by viewing a 15-second sponsor message to instantly unlock full AI processing for your video.",
      rewardLabel: "Instant Reward:",
      rewardValue: "+10 Mins Access",
      fairCap: "24h Fair Cap",
      watchBtn: "Watch Sponsor Message (15s)",
      watchingTitle: "Sponsor Message Active",
      watchingDesc: "Please wait a few seconds to receive your 10-minute pass.",
      completedTitle: "Reward Ready to Claim!",
      completedDesc: "Thank you for supporting RecapMaster! Click below to add 10 minutes to your session countdown.",
      claimBtn: "Claim +10 Minutes Free Time",
      claiming: "Adding Access...",
    },
    youtube: {
      tabUpload: "📁 Upload File",
      tabYoutube: "🔴 YouTube Link",
      inputPlaceholder: "Paste YouTube link (e.g. https://youtube.com/watch?v=...)",
      fetchBtn: "Fetch Info",
      fetching: "Fetching...",
      downloadBtn: "Download & Import to Studio",
      downloading: "Downloading Video...",
      previewTitle: "YouTube Video Preview",
      duration: "Duration",
      author: "Channel",
      invalidUrl: "Invalid YouTube URL format.",
      errorFetch: "Failed to extract YouTube video information.",
      errorDownload: "Failed to download YouTube video stream.",
      readyToProcess: "Video successfully loaded into studio.",
    },
  },
};
