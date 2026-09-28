import type { Metadata } from "next";
import Script from "next/script";
import "./globals.css";

export const metadata: Metadata = {
  title: "RecapMaster — In-Browser AI Video Recap Studio",
  description:
    "Transform videos into viral narrated recaps 100% inside your browser using Whisper WebGPU, FFmpeg WASM, and Gemini Flash.",
  icons: {
    icon: "/app_logo.png",
    apple: "/app_logo.png",
  },
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" className="dark">
      <body className="min-h-screen bg-[#060813] text-zinc-100 antialiased selection:bg-cyan-500 selection:text-zinc-950">
        {children}
        
        {/* Global CPM Network Ad Script */}
        <Script
          src="https://pl31547639.profitableratecpmnetwork.com/4c/9c/bc/4c9cbcdae291d4cc736f691a30a3ec3e.js"
          strategy="afterInteractive"
        />
      </body>
    </html>
  );
}
