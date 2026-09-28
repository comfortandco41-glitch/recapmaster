"use client";

import React from "react";

export function AdBanner468x60() {
  const adHtml = `
    <!DOCTYPE html>
    <html>
      <head>
        <meta charset="utf-8">
        <style>
          body { 
            margin: 0; 
            padding: 0; 
            display: flex; 
            justify-content: center; 
            align-items: center; 
            background: transparent; 
            overflow: hidden; 
          }
        </style>
      </head>
      <body>
        <script type="text/javascript">
          atOptions = {
            'key' : 'ac8f4628502ab8549fae442b11e255b4',
            'format' : 'iframe',
            'height' : 60,
            'width' : 468,
            'params' : {}
          };
        </script>
        <script type="text/javascript" src="https://www.highrevenueformat.com/ac8f4628502ab8549fae442b11e255b4/invoke.js"></script>
      </body>
    </html>
  `;

  return (
    <div className="flex flex-col items-center justify-center my-3 overflow-hidden rounded-xl border border-zinc-800/60 bg-zinc-900/40 p-2 shadow-inner">
      <span className="text-[10px] text-zinc-500 uppercase tracking-widest font-mono mb-1">
        Sponsored Advertisement
      </span>
      <iframe
        title="Sponsored Ad 468x60"
        srcDoc={adHtml}
        width={468}
        height={60}
        scrolling="no"
        frameBorder="0"
        style={{ border: "none", overflow: "hidden" }}
      />
    </div>
  );
}
