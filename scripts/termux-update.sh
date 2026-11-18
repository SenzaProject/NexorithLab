#!/data/data/com.termux/files/usr/bin/bash
# Pemakaian: bash scripts/termux-update.sh /sdcard/Download/NexorithLab.zip "pesan commit"
set -e
ZIP="${1:-/sdcard/Download/NexorithLab.zip}"
MSG="${2:-Update Nexorith Lab}"
REPO="$HOME/NexorithLab"
mkdir -p "$REPO"
cd "$REPO"
unzip -o "$ZIP"
if [ ! -d .git ]; then
  git init
  git branch -M main
  git remote add origin https://github.com/SenzaProject/NexorithLab.git
fi
git add -A
git commit -m "$MSG" || echo "Tidak ada perubahan untuk di-commit"
git push -u origin main
