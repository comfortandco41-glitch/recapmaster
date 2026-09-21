import { spawn } from "child_process";
import path from "path";
import fs from "fs";

export interface DispatchRerenderOptions {
  placement?: string;
  size?: number;
  margin?: number;
  blurBox?: any;
  speed?: number;
}

export function dispatchJob(
  jobId: string,
  rerenderOnly: boolean = false,
  options?: DispatchRerenderOptions
): void {
  const rootDir = process.cwd();
  const workerScript = path.join(rootDir, "worker", "main.py");
  const jobLogDir = path.join(rootDir, "storage", "jobs", jobId);

  fs.mkdirSync(jobLogDir, { recursive: true });
  const logFile = path.join(jobLogDir, "worker.log");
  const out = fs.openSync(logFile, "a");
  const err = fs.openSync(logFile, "a");

  // Determine python executable: py on Windows, or python3 on Linux/Docker
  const pythonCmd = process.platform === "win32" ? "py" : (process.env.PYTHON_BIN || "python3");

  const workerArgs = ["--job-id", jobId];
  if (rerenderOnly) {
    workerArgs.push("--rerender-only");
  }

  if (options) {
    if (options.placement) workerArgs.push("--placement", options.placement);
    if (options.size !== undefined) workerArgs.push("--size", options.size.toString());
    if (options.margin !== undefined) workerArgs.push("--margin", options.margin.toString());
    if (options.blurBox) workerArgs.push("--blur-config", JSON.stringify(options.blurBox));
    if (options.speed !== undefined) workerArgs.push("--speed", options.speed.toString());
  }

  try {
    const child = spawn(
      pythonCmd,
      [workerScript, ...workerArgs],
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
    console.log(`[Queue] Dispatched job ${jobId} to media worker (PID: ${child.pid}, rerender: ${rerenderOnly})`);
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
