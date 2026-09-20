FROM node:20-bookworm

# 1. Install system utilities, Python 3, and FFmpeg with full libass subtitle support
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 \
    python3-pip \
    python3-venv \
    ffmpeg \
    fonts-noto-core \
    libass-dev \
    git \
    && rm -rf /var/lib/apt/lists/*

# 2. Setup non-root user (required by Hugging Face Spaces with UID 1000)
RUN useradd -m -u 1000 user
USER user
ENV HOME=/home/user \
    PATH=/home/user/.local/bin:$PATH \
    PYTHONUNBUFFERED=1 \
    PYTHONIOENCODING=utf-8 \
    PORT=7860

WORKDIR $HOME/app

# 3. Install Python dependencies
COPY --chown=user requirements.txt ./
RUN pip3 install --no-cache-dir --break-system-packages --user -r requirements.txt

# 4. Install Node.js dependencies
COPY --chown=user package*.json ./
COPY --chown=user prisma ./prisma/
RUN npm ci

# 5. Copy remaining source code and set permissions
COPY --chown=user . .
RUN chmod +x start.sh

# 6. Build Next.js production application
ENV NEXT_TELEMETRY_DISABLED=1
RUN npx prisma generate
RUN npm run build

# 7. Expose Hugging Face Space default port
EXPOSE 7860

# 8. Run the startup script
CMD ["/bin/bash", "start.sh"]
