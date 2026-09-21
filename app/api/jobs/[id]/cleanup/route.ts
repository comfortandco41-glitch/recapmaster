import { NextRequest, NextResponse } from "next/server";
import { cleanupPostDownload } from "@/lib/cleanup";
import { getJobById } from "@/lib/db/jobs";

interface RouteParams {
  params: {
    id: string;
  };
}

export async function POST(
  _request: NextRequest,
  { params }: RouteParams
) {
  try {
    const { id } = params;
    if (!id) {
      return NextResponse.json({ code: "BAD_REQUEST", message: "Job ID is required" }, { status: 400 });
    }

    const job = await getJobById(id);
    if (!job) {
      return NextResponse.json({ code: "NOT_FOUND", message: `Job '${id}' was not found.` }, { status: 404 });
    }

    const result = await cleanupPostDownload(id);
    return NextResponse.json({
      success: true,
      message: "Source file and final output video have been removed from server storage.",
      removed: result.removed,
    });
  } catch (error) {
    console.error("Cleanup API error:", error);
    return NextResponse.json(
      { code: "INTERNAL_ERROR", message: "Failed to perform post-download cleanup." },
      { status: 500 }
    );
  }
}
