import { describe, it, expect, beforeAll, afterAll } from "vitest";
import {
  createJobRecord,
  getJobById,
  updateJobStatus,
  addJobAsset,
  deleteJobRecord,
} from "./jobs";
import { prisma } from "./client";

describe("Job Database Operations", () => {
  const testJobId = `job_test_${Date.now()}`;

  afterAll(async () => {
    try {
      await prisma.jobEvent.deleteMany({ where: { jobId: testJobId } });
      await prisma.jobAsset.deleteMany({ where: { jobId: testJobId } });
      await prisma.job.deleteMany({ where: { id: testJobId } });
    } catch {
      // Ignore cleanup error if already removed
    }
  });

  it("creates a job record in QUEUED state with an initial lifecycle event", async () => {
    const job = await createJobRecord({
      id: testJobId,
      sourceUrl: "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
      sourcePlatform: "YOUTUBE",
      language: "en",
      voice: "default",
    });

    expect(job.id).toBe(testJobId);
    expect(job.status).toBe("QUEUED");
    expect(job.progress).toBe(0);
    expect(job.sourcePlatform).toBe("YOUTUBE");

    const fetched = await getJobById(testJobId);
    expect(fetched).not.toBeNull();
    expect(fetched?.events.length).toBeGreaterThanOrEqual(1);
    expect(fetched?.events[0].stage).toBe("QUEUED");
  });

  it("transitions job status and records intermediate progress", async () => {
    const updated = await updateJobStatus(testJobId, "DOWNLOADING", {
      progress: 15,
      stage: "DOWNLOADING",
      eventMessage: "Downloading media stream with yt-dlp",
    });

    expect(updated.status).toBe("DOWNLOADING");
    expect(updated.progress).toBe(15);
    expect(updated.startedAt).not.toBeNull();

    const fetched = await getJobById(testJobId);
    const stages = fetched?.events.map((e) => e.stage);
    expect(stages).toContain("DOWNLOADING");
  });

  it("records job assets correctly", async () => {
    const asset = await addJobAsset(
      testJobId,
      "SOURCE_VIDEO",
      `jobs/${testJobId}/source.mp4`,
      "video/mp4",
      1500000
    );

    expect(asset.type).toBe("SOURCE_VIDEO");
    expect(asset.jobId).toBe(testJobId);

    const fetched = await getJobById(testJobId);
    expect(fetched?.assets.length).toBe(1);
    expect(fetched?.assets[0].type).toBe("SOURCE_VIDEO");
  });

  it("marks job as deleted on cancellation", async () => {
    const deleted = await deleteJobRecord(testJobId);
    expect(deleted.status).toBe("DELETED");
    expect(deleted.deletedAt).not.toBeNull();
  });
});
