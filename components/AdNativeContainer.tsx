"use client";

import React, { useEffect, useRef } from "react";

export function AdNativeContainer() {
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!containerRef.current) return;

    // Avoid duplicate script insertion
    if (document.getElementById("ad-script-be176ee18658a59ec1bb93481f400eab")) return;

    const script = document.createElement("script");
    script.id = "ad-script-be176ee18658a59ec1bb93481f400eab";
    script.async = true;
    script.setAttribute("data-cfasync", "false");
    script.src = "https://pl31530651.profitableratecpmnetwork.com/be176ee18658a59ec1bb93481f400eab/invoke.js";

    containerRef.current.appendChild(script);
  }, []);

  return (
    <div className="flex flex-col items-center justify-center my-4 overflow-hidden rounded-xl border border-zinc-800/60 bg-zinc-900/30 p-2 text-center">
      <span className="text-[10px] text-zinc-500 uppercase tracking-widest font-mono mb-2">
        Sponsored Recommendation
      </span>
      <div ref={containerRef} className="w-full flex justify-center">
        <div id="container-be176ee18658a59ec1bb93481f400eab"></div>
      </div>
    </div>
  );
}
