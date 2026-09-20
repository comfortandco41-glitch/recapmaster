import { NextRequest, NextResponse } from "next/server";

export async function POST(request: NextRequest) {
  try {
    const body = await request.json();
    const rawEndpoint = body?.endpoint?.trim();

    if (!rawEndpoint) {
      return NextResponse.json(
        { success: false, error: "Colab endpoint URL is required." },
        { status: 400 }
      );
    }

    let parsedUrl: URL;
    try {
      parsedUrl = new URL(rawEndpoint);
    } catch {
      return NextResponse.json(
        { success: false, error: "Invalid URL format. Provide a full URL including https://" },
        { status: 400 }
      );
    }

    if (parsedUrl.protocol !== "http:" && parsedUrl.protocol !== "https:") {
      return NextResponse.json(
        { success: false, error: "Only HTTP and HTTPS URLs are supported." },
        { status: 400 }
      );
    }

    const startTime = Date.now();
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 6000);

    const headers: Record<string, string> = {
      Accept: "application/json, text/plain, */*",
    };
    if (body.apiKey?.trim()) {
      headers["Authorization"] = `Bearer ${body.apiKey.trim()}`;
    }

    try {
      // Ping root or endpoint URL
      const response = await fetch(rawEndpoint, {
        method: "GET",
        headers,
        signal: controller.signal,
      });

      clearTimeout(timeoutId);
      const latencyMs = Date.now() - startTime;

      return NextResponse.json({
        success: true,
        httpStatus: response.status,
        latencyMs,
        message: `Endpoint responded in ${latencyMs}ms (HTTP ${response.status}). Colab tunnel is active.`,
      });
    } catch (fetchError: unknown) {
      clearTimeout(timeoutId);
      const err = fetchError as Error;

      if (err.name === "AbortError") {
        return NextResponse.json({
          success: false,
          error: "Connection timed out after 6 seconds. The Colab notebook may be starting up or sleeping.",
        });
      }

      return NextResponse.json({
        success: false,
        error: `Could not connect to Colab endpoint: ${err.message || "Connection refused"}. Check your ngrok or tunnel link.`,
      });
    }
  } catch (error) {
    return NextResponse.json(
      { success: false, error: "Internal error checking VoxCPM endpoint." },
      { status: 500 }
    );
  }
}
