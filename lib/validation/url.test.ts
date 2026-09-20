import { describe, it, expect } from "vitest";
import { validateMediaUrl } from "./url";

describe("validateMediaUrl", () => {
  describe("YouTube URLs", () => {
    it("accepts standard youtube.com/watch URLs", () => {
      const result = validateMediaUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
      expect(result.isValid).toBe(true);
      expect(result.platform).toBe("YOUTUBE");
      expect(result.canonicalUrl).toBe("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
      expect(result.videoId).toBe("dQw4w9WgXcQ");
    });

    it("accepts youtu.be short URLs", () => {
      const result = validateMediaUrl("https://youtu.be/dQw4w9WgXcQ");
      expect(result.isValid).toBe(true);
      expect(result.platform).toBe("YOUTUBE");
      expect(result.canonicalUrl).toBe("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
    });

    it("accepts youtube shorts URLs", () => {
      const result = validateMediaUrl("https://www.youtube.com/shorts/abcdef12345");
      expect(result.isValid).toBe(true);
      expect(result.platform).toBe("YOUTUBE");
      expect(result.canonicalUrl).toBe("https://www.youtube.com/watch?v=abcdef12345");
    });

    it("accepts URLs with extra tracking query params", () => {
      const result = validateMediaUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ&feature=share&t=42s");
      expect(result.isValid).toBe(true);
      expect(result.platform).toBe("YOUTUBE");
      expect(result.canonicalUrl).toBe("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
    });

    it("rejects youtube URL without video ID", () => {
      const result = validateMediaUrl("https://www.youtube.com/watch");
      expect(result.isValid).toBe(false);
      expect(result.error).toContain("Missing 'v' parameter");
    });
  });

  describe("Bilibili URLs", () => {
    it("accepts standard bilibili video URLs with BV id", () => {
      const result = validateMediaUrl("https://www.bilibili.com/video/BV1xx411c7mD");
      expect(result.isValid).toBe(true);
      expect(result.platform).toBe("BILIBILI");
      expect(result.videoId).toBe("BV1xx411c7mD");
    });

    it("accepts bilibili bangumi episode URLs with share query params", () => {
      const result = validateMediaUrl("https://www.bilibili.com/bangumi/play/ep3065300/?share_source=copy_web");
      expect(result.isValid).toBe(true);
      expect(result.platform).toBe("BILIBILI");
      expect(result.canonicalUrl).toBe("https://www.bilibili.com/bangumi/play/ep3065300");
      expect(result.videoId).toBe("ep3065300");
    });

    it("accepts bilibili short b23.tv URLs", () => {
      const result = validateMediaUrl("https://b23.tv/xyz789");
      expect(result.isValid).toBe(true);
      expect(result.platform).toBe("BILIBILI");
      expect(result.videoId).toBe("xyz789");
    });

    it("rejects bilibili URL with invalid path", () => {
      const result = validateMediaUrl("https://www.bilibili.com/read/cv12345");
      expect(result.isValid).toBe(false);
      expect(result.error).toContain("Unsupported Bilibili path");
    });
  });

  describe("Unsupported and Invalid URLs", () => {
    it("rejects unsupported domains like Vimeo", () => {
      const result = validateMediaUrl("https://vimeo.com/12345678");
      expect(result.isValid).toBe(false);
      expect(result.error).toContain("Unsupported domain 'vimeo.com'");
    });

    it("rejects empty or whitespace URLs", () => {
      expect(validateMediaUrl("").isValid).toBe(false);
      expect(validateMediaUrl("   ").isValid).toBe(false);
    });

    it("rejects malformed text that is not a URL", () => {
      const result = validateMediaUrl("not-a-valid-url");
      expect(result.isValid).toBe(false);
      expect(result.error).toContain("Invalid URL format");
    });
  });

  describe("Security and SSRF Protection", () => {
    it("rejects localhost", () => {
      const result = validateMediaUrl("http://localhost/watch?v=123");
      expect(result.isValid).toBe(false);
      expect(result.error).toContain("forbidden");
    });

    it("rejects 127.0.0.1", () => {
      const result = validateMediaUrl("http://127.0.0.1:8080/exploit");
      expect(result.isValid).toBe(false);
      expect(result.error).toContain("forbidden");
    });

    it("rejects private subnet 192.168.x.x", () => {
      const result = validateMediaUrl("http://192.168.1.1/admin");
      expect(result.isValid).toBe(false);
      expect(result.error).toContain("forbidden");
    });

    it("rejects non-http protocols like javascript: or file:", () => {
      expect(validateMediaUrl("javascript:alert(1)").isValid).toBe(false);
      expect(validateMediaUrl("file:///etc/passwd").isValid).toBe(false);
    });
  });
});
