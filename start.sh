#!/bin/bash
set -e

echo "[*] Initializing RecapMaster for Hugging Face Spaces..."

# Ensure storage directories exist
mkdir -p storage/jobs prisma

# Push SQLite database schema if not already present
echo "[*] Ensuring database schema is ready..."
npx prisma db push --skip-generate || true

# Add sound_style column if not present in SQLite
python3 -c "
import sqlite3, os
for db_path in ['prisma/dev.db', 'prisma/prisma/dev.db']:
    try:
        if os.path.exists(db_path):
            conn = sqlite3.connect(db_path)
            cols = [x[1] for x in conn.execute('PRAGMA table_info(jobs)').fetchall()]
            if 'sound_style' not in cols:
                conn.execute('ALTER TABLE jobs ADD COLUMN sound_style TEXT DEFAULT \"cinematic_recap\"')
                conn.commit()
            conn.close()
    except Exception as e:
        print(f'DB note: {e}')
" || true

echo "[*] Starting Next.js Web Server on port ${PORT:-7860}..."
exec npm start -- -p "${PORT:-7860}"
