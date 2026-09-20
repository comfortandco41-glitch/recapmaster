import { spawn } from "child_process";
import path from "path";
import fs from "fs";

export function dispatchJob(jobId: string): void {
  const rootDir = process.cwd();
  const workerScript = path.join(rootDir, "worker", "main.py");
  const jobLogDir = path.join(rootDir, "storage", "jobs", jobId);

  fs.mkdirSync(jobLogDir, { recursive: true });
  const logFile = path.join(jobLogDir, "worker.log");
  const out = fs.openSync(logFile, "a");
  const err = fs.openSync(logFile, "a");

  // Determine python executable: py on Windows, or python3 on Linux/Docker
  const pythonCmd = process.platform === "win32" ? "py" : (process.env.PYTHON_BIN || "python3");

  try {
    const child = spawn(
      pythonCmd,
      [workerScript, "--job-id", jobId],
      {
        cwd: rootDir,
        detached: true,
        stdio: ["ignore", out, err],
        env: {
          ...process.env,
          PYTHONUNBUFFERED: "1",
          PYTHONIOENCODING: "utf-8",
          PYTHONPATH: rootDir,
        },
      }
    );

    child.unref();
    console.log(`[Queue] Dispatched job ${jobId} to media worker (PID: ${child.pid})`);
  } catch (error) {
    console.error(`[Queue] Failed to spawn worker for job ${jobId}:`, error);
  }
}

export function dispatchCleanup(): void {
  const rootDir = process.cwd();
  const workerScript = path.join(rootDir, "worker", "main.py");
  const pythonCmd = process.platform === "win32" ? "py" : (process.env.PYTHON_BIN || "python3");

  try {
    const child = spawn(
      pythonCmd,
      [workerScript, "--cleanup"],
      {
        cwd: rootDir,
        detached: true,
        stdio: "ignore",
        env: { ...process.env, PYTHONPATH: rootDir },
      }
    );
    child.unref();
  } catch (err) {
    console.error("[Queue] Failed to trigger cleanup worker:", err);
  }
}
