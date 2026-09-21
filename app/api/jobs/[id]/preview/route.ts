import { NextRequest, NextResponse } from "next/server";
import { execFile } from "child_process";
import path from "path";
import fs from "fs";

interface RouteParams {
  params: {
    id: string;
  };
}

export async function POST(
  request: NextRequest,
  { params }: RouteParams
) {
  try {
    const { id } = params;
    const body = await request.json().catch(() => ({}));

    const placement = body.placement || "bottom";
    const size = body.size?.toString() || "1.0";
    const margin = body.margin?.toString() || "30";
    const blurConfig = body.blurBox ? JSON.stringify(body.blurBox) : "";

    const rootDir = process.cwd();
    const workerScript = path.join(rootDir, "worker", "main.py");
    const previewTarget = path.join(rootDir, "storage", "jobs", id, "preview_live.png");
    fs.mkdirSync(path.dirname(previewTarget), { recursive: true });

    const pythonCmd = process.platform === "win32" ? "py" : (process.env.PYTHON_BIN || "python3");

    const args = [
      workerScript,
      "--preview",
      "--job-id", id,
      "--placement", placement,
      "--size", size,
      "--margin", margin,
      "--output-preview", previewTarget,
    ];
    if (blurConfig) {
      args.push("--blur-config", blurConfig);
    }

    await new Promise<void>((resolve, reject) => {
      execFile(pythonCmd, args, { cwd: rootDir }, (error, stdout, stderr) => {
        if (error) {
          console.error("[Preview API Error]:", stderr || error.message);
          reject(error);
        } else {
          resolve();
        }
      });
    });

    if (fs.existsSync(previewTarget)) {
      const buffer = fs.readFileSync(previewTarget);
      return new Response(buffer, {
        headers: {
          "Content-Type": "image/png",
          "Cache-Control": "no-store, max-age=0",
        },
      });
    }

    return NextResponse.json({ error: "Preview generation failed" }, { status: 500 });
  } catch (err: any) {
    console.error("Preview endpoint error:", err);
    return NextResponse.json({ error: err?.message || "Internal server error" }, { status: 500 });
  }
}
