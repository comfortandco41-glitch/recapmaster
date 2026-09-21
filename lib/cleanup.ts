import fs from "fs";
import path from "path";
import { prisma } from "./db/client";

/**
 * Removes the original source video file and final rendered output video after user download.
 * Also cleans up intermediate extracted source audio to free up maximum server storage space.
 */
export async function cleanupPostDownload(jobId: string): Promise<{ success: boolean; removed: string[] }> {
  const rootDir = process.cwd();
  const jobDir = path.join(rootDir, "storage", "jobs", jobId);
  const sourceDir = path.join(jobDir, "source");
  const audioDir = path.join(jobDir, "audio");
  const finalVideo = path.join(jobDir, "render", "final.mp4");

  const removed: string[] = [];

  // 1. Remove source video directory and files
  try {
    if (fs.existsSync(sourceDir)) {
      fs.rmSync(sourceDir, { recursive: true, force: true });
      removed.push("source_video");
      console.log(`[Post-Download Cleanup] Removed source directory: ${sourceDir}`);
    }
  } catch (err) {
    console.error(`[Post-Download Cleanup] Failed to remove source dir ${sourceDir}:`, err);
  }

  // 2. Remove extracted source audio directory and files
  try {
    if (fs.existsSync(audioDir)) {
      fs.rmSync(audioDir, { recursive: true, force: true });
      removed.push("source_audio");
      console.log(`[Post-Download Cleanup] Removed source audio directory: ${audioDir}`);
    }
  } catch (err) {
    console.error(`[Post-Download Cleanup] Failed to remove audio dir ${audioDir}:`, err);
  }

  // 3. Remove final rendered output video file
  try {
    if (fs.existsSync(finalVideo)) {
      fs.unlinkSync(finalVideo);
      removed.push("final_video");
      console.log(`[Post-Download Cleanup] Removed final rendered video: ${finalVideo}`);
    }
  } catch (err) {
    console.error(`[Post-Download Cleanup] Failed to remove final video ${finalVideo}:`, err);
  }

  // 4. Record database event
  try {
    await prisma.jobEvent.create({
      data: {
        id: `evt_${Date.now()}_post_download_cleanup`,
        jobId,
        stage: "CLEANUP_POST_DOWNLOAD",
        message: "Source file and final output video removed after user download to save disk space.",
        progress: 100,
      },
    });
  } catch (dbErr) {
    console.warn(`[Post-Download Cleanup] Could not log cleanup event to DB:`, dbErr);
  }

  return { success: true, removed };
}
