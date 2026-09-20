# Promovie Recap Tool — architecture.md

## 1. System Goal

Promovie Recap is a web application that accepts a supported YouTube or Bilibili URL, processes the source through a media pipeline, generates narration, renders a recap video, and gives the user a temporary download.

The architecture should separate:

1. Web UI
2. API/orchestration
3. Job queue
4. Media worker
5. Whisper worker
6. VoxCPM provider
7. FFmpeg renderer
8. Temporary storage
9. Database
10. Cleanup worker

---

# 2. High-Level Architecture

```text
                         ┌─────────────────────┐
                         │       Browser       │
                         │   Next.js Web App   │
                         └──────────┬──────────┘
                                    │
                              HTTPS / API
                                    │
                         ┌──────────▼──────────┐
                         │    API / Backend     │
                         │ Job Orchestrator     │
                         └──────┬─────────┬─────┘
                                │         │
                         Job metadata     │ enqueue
                                │         │
                         ┌──────▼───┐ ┌──▼────────────┐
                         │ Database │ │   Job Queue   │
                         │PostgreSQL│ │ Redis / Queue │
                         └──────────┘ └──────┬────────┘
                                             │
                                    ┌────────▼────────┐
                                    │  Media Worker   │
                                    │ Python/Node.js  │
                                    └────────┬────────┘
                                             │
               ┌─────────────────────────────┼─────────────────────────────┐
               │                             │                             │
       ┌───────▼────────┐          ┌────────▼────────┐          ┌────────▼────────┐
       │    Downloader  │          │ Whisper Service │          │ VoxCPM Provider │
       │    yt-dlp      │          │   Whisper       │          │ Google Colab    │
       └───────┬────────┘          └────────┬────────┘          └────────┬────────┘
               │                            │                            │
               └────────────────┬───────────┴────────────┬───────────────┘
                                │                        │
                         ┌──────▼──────┐          ┌──────▼───────┐
                         │ Temp Media  │          │    FFmpeg    │
                         │ Job Storage │          │    Renderer  │
                         └──────┬──────┘          └──────┬───────┘
                                │                        │
                                └────────────┬───────────┘
                                             │
                                      ┌──────▼──────┐
                                      │ Final Video  │
                                      └──────┬──────┘
                                             │
                                      Temporary URL
                                             │
                                      ┌──────▼──────┐
                                      │    User     │
                                      │   Download  │
                                      └─────────────┘
```

---

# 3. Recommended Technology Stack

## Frontend

```text
Next.js
React
TypeScript
Tailwind CSS
```

The frontend should only control the job and display progress. Heavy media processing must not run inside the browser.

---

## Backend

Recommended:

```text
Node.js + TypeScript
```

Responsibilities:

- authentication
- URL validation
- job creation
- queue management
- provider orchestration
- progress reporting
- download authorization
- cleanup scheduling

A Python media worker is also recommended because Whisper, audio processing, and ML tooling have strong Python support.

---

## Database

Recommended:

```text
PostgreSQL
```

Supabase PostgreSQL is suitable if the rest of the application already uses Supabase.

Database stores metadata only where possible.

Do not store large video files directly in PostgreSQL.

---

## Queue

Recommended:

```text
Redis + BullMQ
```

Alternative:

```text
Cloud Tasks
```

The queue is important because video rendering is a long-running job.

---

## Temporary Storage

Use object storage or attached worker storage.

Recommended structure:

```text
promovie-temp/
  <user_id>/
    <job_id>/
      source/
      audio/
      transcript/
      voice/
      render/
```

For production, object storage is safer than depending on a single server's local filesystem.

---

# 4. Processing Pipeline

## Stage 1 — Create Job

User submits:

```json
{
  "url": "https://www.youtube.com/watch?v=...",
  "voice": "default",
  "language": "en"
}
```

Backend:

```text
validate URL
 ↓
create job
 ↓
save metadata
 ↓
enqueue processing
 ↓
return job_id
```

---

# 5. Stage 2 — Download

Worker receives:

```text
job_id
url
```

Downloader:

```text
validate platform
 ↓
yt-dlp
 ↓
download source
 ↓
verify media
 ↓
save metadata
```

Example internal output:

```text
/jobs/<job_id>/source/source.mp4
```

After successful download:

```text
status = DOWNLOADED
```

---

# 6. Stage 3 — Extract Audio

FFmpeg:

```text
source.mp4
    ↓
audio.wav
```

Recommended:

```text
16 kHz
mono
PCM
```

---

# 7. Stage 4 — Whisper

Whisper worker:

```text
audio.wav
    ↓
Whisper
    ↓
transcript.json
    ↓
SRT
```

Store:

```text
transcript.json
transcript.srt
```

Example:

```json
{
  "language": "en",
  "segments": [
    {
      "start": 0.0,
      "end": 3.4,
      "text": "..."
    }
  ]
}
```

---

# 8. Stage 5 — Recap Script

Optional but recommended.

```text
transcript
    ↓
clean transcript
    ↓
recap generator
    ↓
recap script
```

The recap generator can be an LLM service.

The generated script should be saved separately:

```text
recap_script.txt
```

This prevents accidental loss of the original transcription.

---

# 9. Stage 6 — VoxCPM 2

The voice service is external.

```text
Media Worker
     │
     │ HTTPS
     ▼
VoxCPM 2 / Google Colab
     │
     │ generated WAV
     ▼
Media Worker
```

Configuration:

```env
VOXCPM_ENDPOINT=
VOXCPM_API_KEY=
VOXCPM_TIMEOUT_SECONDS=300
```

Do not expose the endpoint secret or API key to the frontend.

---

# 10. VoxCPM Adapter

Use an adapter so the rest of the system does not depend on Google Colab.

```text
VoiceProvider
   │
   ├── VoxCPMColabProvider
   ├── FutureGPUProvider
   └── FutureCloudProvider
```

Interface:

```typescript
interface VoiceProvider {
  generateSpeech(params: {
    text: string;
    language?: string;
    voice?: string;
  }): Promise<{
    localPath: string;
    duration: number;
  }>;
}
```

This makes migration away from Colab easy.

---

# 11. Stage 7 — Segment Planning

Before rendering, create a render plan.

Example:

```json
{
  "segments": [
    {
      "sourceStart": 12.2,
      "sourceEnd": 18.4,
      "narrationStart": 0,
      "narrationEnd": 6.2,
      "effects": [
        {
          "type": "zoom",
          "direction": "in",
          "amount": 1.06
        }
      ]
    }
  ]
}
```

The render plan is the bridge between AI/script generation and FFmpeg.

---

# 12. Stage 8 — FFmpeg Rendering

FFmpeg handles:

```text
video trimming
video scaling
zoom
crop
flip
freeze frame
speed changes
audio replacement
audio mixing
encoding
```

Pipeline:

```text
Source video
    +
Render plan
    +
Narration
    ↓
FFmpeg filter graph
    ↓
Rendered video
```

Output:

```text
final.mp4
```

Recommended baseline:

```text
Container: MP4
Video: H.264
Audio: AAC
```

---

# 13. Visual Editing System

The visual editor should use deterministic presets.

Example presets:

```text
NONE
SUBTLE_ZOOM
ZOOM_OUT
HORIZONTAL_FLIP
FREEZE_SHORT
CROP_CENTER
SPEED_0_95
SPEED_1_05
```

Example:

```json
{
  "effect": "SUBTLE_ZOOM",
  "duration": 2.0,
  "intensity": 0.06
}
```

Effects should be applied for editing and recap presentation, not represented as a way to defeat copyright systems.

---

# 14. Copyright and Legal UX

The application should not promise that transformations make source material copyright-free.

Required product copy:

```text
You are responsible for ensuring you have the necessary
rights or permission to process and use the source material.
Editing, narration, cropping, zooming, flipping, or other
transformations do not automatically remove copyright
restrictions.
```

Before processing:

```text
[ ] I confirm that I have the necessary rights or lawful
    basis to process this content.
```

If the application later supports public publishing, add a separate publishing confirmation.

---

# 15. Job State Machine

```text
QUEUED
  │
  ▼
DOWNLOADING
  │
  ▼
DOWNLOADED
  │
  ▼
TRANSCRIBING
  │
  ▼
TRANSCRIBED
  │
  ▼
VOICE_GENERATING
  │
  ▼
VOICE_READY
  │
  ▼
RENDERING
  │
  ▼
RENDERED
  │
  ▼
READY
  │
  ▼
CLEANING
  │
  ▼
DELETED
```

Any stage can transition to:

```text
FAILED
```

Retryable failures return to the relevant stage.

---

# 16. Database Schema

## users

```sql
id
email
created_at
```

## jobs

```sql
id
user_id
source_url
source_platform
status
progress
error_code
error_message
created_at
started_at
completed_at
expires_at
deleted_at
```

## job_assets

```sql
id
job_id
type
storage_key
mime_type
size_bytes
created_at
expires_at
```

Asset types:

```text
SOURCE_VIDEO
SOURCE_AUDIO
TRANSCRIPT_JSON
SRT
RECAP_SCRIPT
VOICE_AUDIO
FINAL_VIDEO
```

## job_events

```sql
id
job_id
stage
message
progress
created_at
```

This is useful for debugging and support.

---

# 17. API Architecture

## POST /api/jobs

Creates a job.

```json
{
  "url": "https://...",
  "language": "en",
  "voice": "default"
}
```

Response:

```json
{
  "jobId": "abc123",
  "status": "QUEUED"
}
```

---

## GET /api/jobs/:id

Returns current state.

```json
{
  "id": "abc123",
  "status": "RENDERING",
  "progress": 78,
  "expiresAt": "2026-09-19T12:30:00Z"
}
```

---

## GET /api/jobs/:id/events

Optional SSE endpoint for live progress.

Alternative:

```text
WebSocket
```

For a simple MVP, frontend polling every 2–5 seconds is sufficient.

---

## GET /api/jobs/:id/download

Checks:

```text
user owns job
job status = READY
asset exists
asset not expired
```

Then returns a temporary signed download URL or streams the file.

---

## DELETE /api/jobs/:id

Immediately schedules cleanup.

---

# 18. Cleanup Architecture

Cleanup must happen in two ways.

## A. Post-download cleanup

After the user downloads the final video:

```text
download complete
    ↓
mark download
    ↓
schedule cleanup
    ↓
delete all job assets
```

## B. TTL cleanup

A scheduled worker periodically checks:

```text
expires_at < NOW()
```

and deletes expired files.

This protects against:

- browser closed during download
- network failure
- user never downloading
- worker crash
- abandoned jobs

---

# 19. Cleanup Order

Delete:

```text
final.mp4
voice.wav
recap_script.txt
transcript.srt
transcript.json
audio.wav
source.mp4
```

Then delete the job directory.

Finally mark:

```text
status = DELETED
deleted_at = NOW()
```

Do not delete database records immediately if audit/debug information is useful. Instead retain lightweight metadata according to the product's retention policy.

---

# 20. Deployment Architecture

For an MVP:

```text
Vercel
  └── Next.js frontend/API

Supabase
  ├── PostgreSQL
  └── Authentication

Redis
  └── BullMQ

Media Worker
  ├── yt-dlp
  ├── FFmpeg
  └── Whisper

Google Colab
  └── VoxCPM 2
```

Important:

Do not run long FFmpeg/Whisper jobs inside a normal serverless request.

Use a worker/container for long-running processing.

---

# 21. Recommended Worker

A dedicated Docker image:

```text
promovie-worker
```

Contains:

```text
Python
FFmpeg
yt-dlp
Whisper
audio libraries
HTTP client
```

Example internal structure:

```text
worker/
  downloader/
  transcription/
  voice/
  rendering/
  cleanup/
  pipeline/
```

Main pipeline:

```python
async def process_job(job_id):
    await download_source(job_id)
    await extract_audio(job_id)
    await transcribe(job_id)
    await generate_srt(job_id)
    await generate_recap_script(job_id)
    await generate_voice(job_id)
    await build_render_plan(job_id)
    await render_video(job_id)
    await validate_output(job_id)
    await mark_ready(job_id)
```

---

# 22. Failure Recovery

Every stage should be idempotent.

Example:

If rendering fails:

```text
RENDERING
   ↓
FAILED
   ↓
retry
   ↓
RENDERING
```

Do not download the source again if the source file is already valid.

Use checkpoints:

```text
source_exists
audio_exists
transcript_exists
voice_exists
render_exists
```

This reduces cost and processing time.

---

# 23. Concurrency

Set a maximum number of active jobs.

Example:

```env
MAX_CONCURRENT_JOBS=2
```

This prevents one user from consuming all CPU/GPU resources.

For larger deployments:

```text
Queue
  ↓
Worker 1
Worker 2
Worker 3
...
```

Workers can scale independently.

---

# 24. Resource Limits

Recommended configuration:

```env
MAX_SOURCE_SIZE_MB=2000
MAX_VIDEO_DURATION_SECONDS=7200
MAX_OUTPUT_SIZE_MB=2000
JOB_TTL_MINUTES=60
MAX_CONCURRENT_JOBS=2
```

Tune these based on actual server capacity and business requirements.

---

# 25. Security

### URL Security

Allow only known media providers.

### Shell Security

Never interpolate user input directly into shell commands.

### Storage Security

Use per-user/per-job paths.

### Download Security

Require authentication before issuing download URLs.

### Provider Security

Keep VoxCPM credentials server-side.

### Resource Abuse

Enforce:

```text
per-user job limits
file-size limits
duration limits
rate limits
concurrency limits
```

---

# 26. Recommended Project Structure

```text
promovie/
├── app/
│   ├── page.tsx
│   ├── jobs/
│   │   └── [id]/
│   │       └── page.tsx
│   └── api/
│       └── jobs/
│           ├── route.ts
│           └── [id]/
│               ├── route.ts
│               ├── download/
│               │   └── route.ts
│               └── events/
│                   └── route.ts
│
├── components/
│   ├── url-input.tsx
│   ├── job-progress.tsx
│   ├── job-status.tsx
│   └── download-button.tsx
│
├── lib/
│   ├── db/
│   ├── queue/
│   ├── storage/
│   ├── validation/
│   └── auth/
│
├── worker/
│   ├── pipeline/
│   │   ├── download.py
│   │   ├── audio.py
│   │   ├── whisper.py
│   │   ├── voice.py
│   │   ├── render.py
│   │   └── cleanup.py
│   ├── providers/
│   │   └── voxcpm.py
│   └── main.py
│
├── docker/
│   └── worker.Dockerfile
│
├── prisma/
│   └── schema.prisma
│
├── .env.example
├── skill.md
└── architecture.md
```

---

# 27. Environment Variables

```env
DATABASE_URL=

REDIS_URL=

STORAGE_BUCKET=
STORAGE_ENDPOINT=
STORAGE_ACCESS_KEY=
STORAGE_SECRET_KEY=

VOXCPM_ENDPOINT=
VOXCPM_API_KEY=

MAX_SOURCE_SIZE_MB=2000
MAX_VIDEO_DURATION_SECONDS=7200
JOB_TTL_MINUTES=60
MAX_CONCURRENT_JOBS=2
```

Secrets must never be committed to Git.

---

# 28. MVP Build Order

Build in this order:

### Phase 1

```text
Next.js UI
+
URL validation
+
Job database
```

### Phase 2

```text
Queue
+
Downloader
+
FFmpeg audio extraction
```

### Phase 3

```text
Whisper
+
SRT
```

### Phase 4

```text
VoxCPM provider
+
voice audio
```

### Phase 5

```text
FFmpeg render plan
+
video effects
+
audio replacement
```

### Phase 6

```text
Download
+
TTL cleanup
+
failure recovery
```

### Phase 7

```text
Authentication
+
usage limits
+
billing
+
scaling
```

---

# 29. Production Improvements

After MVP validation:

```text
Google Colab VoxCPM
       ↓
Dedicated inference server
```

and:

```text
Local temporary storage
       ↓
Object storage
```

and:

```text
Polling
       ↓
SSE/WebSocket
```

and:

```text
Single worker
       ↓
Autoscaling worker pool
```

---

# 30. Important Product Principle

Promovie should be designed as a **media recap and transformation pipeline**, not as a copyright-evasion system.

The technical architecture can support legitimate editing workflows while leaving copyright ownership, permission, licensing, and publishing decisions with the user.

The system should make the processing pipeline reliable, observable, recoverable, and easy to scale.
