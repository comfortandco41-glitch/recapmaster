import { SourcePlatform } from "../types/job";

export interface UrlValidationResult {
  isValid: boolean;
  platform?: SourcePlatform;
  canonicalUrl?: string;
  videoId?: string;
  error?: string;
}

const YOUTUBE_HOSTS = new Set([
  "youtube.com",
  "www.youtube.com",
  "m.youtube.com",
  "music.youtube.com",
  "youtu.be",
]);

const BILIBILI_HOSTS = new Set([
  "bilibili.com",
  "www.bilibili.com",
  "m.bilibili.com",
  "b23.tv",
]);

const PRIVATE_IP_PATTERNS = [
  /^localhost$/i,
  /^127\./,
  /^10\./,
  /^172\.(1[6-9]|2[0-9]|3[0-1])\./,
  /^192\.168\./,
  /^169\.254\./,
  /^::1$/,
  /^fc00:/,
  /^fe80:/,
];

export function validateMediaUrl(rawUrl: string): UrlValidationResult {
  if (!rawUrl || typeof rawUrl !== "string") {
    return { isValid: false, error: "URL is required" };
  }

  const trimmed = rawUrl.trim();
  if (!trimmed) {
    return { isValid: false, error: "URL cannot be empty" };
  }

  let parsed: URL;
  try {
    parsed = new URL(trimmed);
  } catch {
    return { isValid: false, error: "Invalid URL format" };
  }

  // Scheme must be http or https
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    return { isValid: false, error: "Only HTTP and HTTPS protocols are supported" };
  }

  const hostname = parsed.hostname.toLowerCase();

  // SSRF & private IP check
  for (const pattern of PRIVATE_IP_PATTERNS) {
    if (pattern.test(hostname)) {
      return { isValid: false, error: "Private or local network addresses are forbidden" };
    }
  }

  // Check YouTube
  if (YOUTUBE_HOSTS.has(hostname)) {
    if (hostname === "youtu.be") {
      const videoId = parsed.pathname.slice(1).split("/")[0];
      if (!videoId || videoId.length < 3) {
        return { isValid: false, error: "Missing YouTube video ID in short URL" };
      }
      return {
        isValid: true,
        platform: "YOUTUBE",
        canonicalUrl: `https://www.youtube.com/watch?v=${videoId}`,
        videoId,
      };
    }

    if (parsed.pathname === "/watch") {
      const videoId = parsed.searchParams.get("v");
      if (!videoId) {
        return { isValid: false, error: "Missing 'v' parameter in YouTube URL" };
      }
      return {
        isValid: true,
        platform: "YOUTUBE",
        canonicalUrl: `https://www.youtube.com/watch?v=${videoId}`,
        videoId,
      };
    }

    if (parsed.pathname.startsWith("/shorts/")) {
      const parts = parsed.pathname.split("/").filter(Boolean);
      const videoId = parts[1];
      if (!videoId) {
        return { isValid: false, error: "Missing YouTube Shorts video ID" };
      }
      return {
        isValid: true,
        platform: "YOUTUBE",
        canonicalUrl: `https://www.youtube.com/watch?v=${videoId}`,
        videoId,
      };
    }

    return {
      isValid: false,
      error: "Unsupported YouTube path format. Please provide a standard watch or shorts URL.",
    };
  }

  // Check Bilibili
  if (BILIBILI_HOSTS.has(hostname)) {
    if (hostname === "b23.tv") {
      const code = parsed.pathname.slice(1).split("/")[0];
      if (!code) {
        return { isValid: false, error: "Invalid Bilibili short URL" };
      }
      return {
        isValid: true,
        platform: "BILIBILI",
        canonicalUrl: `https://b23.tv/${code}`,
        videoId: code,
      };
    }

    // e.g. /bangumi/play/ep3065300 or /bangumi/play/ss12345
    const bangumiMatch = parsed.pathname.match(/\/bangumi\/play\/(ep\d+|ss\d+)/i);
    if (bangumiMatch && bangumiMatch[1]) {
      const epId = bangumiMatch[1];
      return {
        isValid: true,
        platform: "BILIBILI",
        canonicalUrl: `https://www.bilibili.com/bangumi/play/${epId}`,
        videoId: epId,
      };
    }

    // e.g. /video/BV1xx411c7mD or /video/av123456
    const match = parsed.pathname.match(/\/video\/(BV[0-9a-zA-Z]+|av[0-9]+)/i);
    if (match && match[1]) {
      const videoId = match[1];
      return {
        isValid: true,
        platform: "BILIBILI",
        canonicalUrl: `https://www.bilibili.com/video/${videoId}`,
        videoId,
      };
    }

    return {
      isValid: false,
      error: "Unsupported Bilibili path. Please provide a video URL containing a BV, av, or bangumi ep/ss identifier.",
    };
  }

  return {
    isValid: false,
    error: `Unsupported domain '${hostname}'. Promovie currently supports YouTube and Bilibili only.`,
  };
}
