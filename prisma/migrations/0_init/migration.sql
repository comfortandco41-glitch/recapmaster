-- Promovie Recap Initial Migration (PostgreSQL / Supabase)

-- CreateTable
CREATE TABLE IF NOT EXISTS "jobs" (
    "id" TEXT NOT NULL,
    "user_id" TEXT DEFAULT 'anonymous',
    "source_url" TEXT NOT NULL,
    "source_platform" TEXT NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'QUEUED',
    "progress" INTEGER NOT NULL DEFAULT 0,
    "error_code" TEXT,
    "error_message" TEXT,
    "language" TEXT DEFAULT 'en',
    "voice" TEXT DEFAULT 'default',
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "started_at" TIMESTAMP(3),
    "completed_at" TIMESTAMP(3),
    "expires_at" TIMESTAMP(3),
    "deleted_at" TIMESTAMP(3),

    CONSTRAINT "jobs_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE IF NOT EXISTS "job_assets" (
    "id" TEXT NOT NULL,
    "job_id" TEXT NOT NULL,
    "type" TEXT NOT NULL,
    "storage_key" TEXT NOT NULL,
    "mime_type" TEXT NOT NULL,
    "size_bytes" BIGINT,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "expires_at" TIMESTAMP(3),

    CONSTRAINT "job_assets_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE IF NOT EXISTS "job_events" (
    "id" TEXT NOT NULL,
    "job_id" TEXT NOT NULL,
    "stage" TEXT NOT NULL,
    "message" TEXT NOT NULL,
    "progress" INTEGER NOT NULL DEFAULT 0,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "job_events_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX IF NOT EXISTS "jobs_status_idx" ON "jobs"("status");
CREATE INDEX IF NOT EXISTS "jobs_expires_at_idx" ON "jobs"("expires_at");
CREATE INDEX IF NOT EXISTS "job_assets_job_id_idx" ON "job_assets"("job_id");
CREATE INDEX IF NOT EXISTS "job_events_job_id_idx" ON "job_events"("job_id");

-- AddForeignKey
ALTER TABLE "job_assets" ADD CONSTRAINT "job_assets_job_id_fkey" FOREIGN KEY ("job_id") REFERENCES "jobs"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "job_events" ADD CONSTRAINT "job_events_job_id_fkey" FOREIGN KEY ("job_id") REFERENCES "jobs"("id") ON DELETE CASCADE ON UPDATE CASCADE;
