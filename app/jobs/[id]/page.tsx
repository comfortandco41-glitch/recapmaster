import React from "react";
import { JobStatusView } from "@/components/job-status";

interface JobPageProps {
  params: {
    id: string;
  };
}

export default function JobDetailPage({ params }: JobPageProps) {
  return (
    <div className="max-w-3xl mx-auto py-4 sm:py-6">
      <JobStatusView jobId={params.id} />
    </div>
  );
}
