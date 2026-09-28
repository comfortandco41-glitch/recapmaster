import { WebRecapStudio } from "@/components/WebRecapStudio";

export const metadata = {
  title: "RecapMaster Web Studio - 100% In-Browser AI (Zero Server Fees)",
  description: "Transcribe and recap videos entirely inside your browser with Whisper WebGPU and Gemini 1.5 Flash.",
};

export default function StudioPage() {
  return <WebRecapStudio />;
}
