import React from "react";
import { ShieldAlert } from "lucide-react";

interface CopyrightNoticeProps {
  className?: string;
}

export function CopyrightNotice({ className = "" }: CopyrightNoticeProps) {
  return (
    <div
      className={`rounded-lg border border-amber-500/20 bg-amber-500/5 p-4 text-xs text-amber-200/80 ${className}`}
    >
      <div className="flex items-start gap-2.5">
        <ShieldAlert className="h-4 w-4 shrink-0 text-amber-400 mt-0.5" />
        <div className="space-y-1">
          <p className="font-medium text-amber-300">
            Legal & Rights Responsibility Notice
          </p>
          <p className="leading-relaxed text-zinc-400">
            Only process content you have permission or a lawful basis to use.
            Editing, narration, cropping, zooming, flipping, or other visual
            transformations do not automatically remove copyright restrictions or
            bypass copyright detection systems. Promovie is a creative media recap
            and editing tool; users remain solely responsible for having lawful
            rights to source content.
          </p>
        </div>
      </div>
    </div>
  );
}
