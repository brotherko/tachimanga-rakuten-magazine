# Rakuten Magazine — Tachimanga / Mihon Extension

A custom Tachiyomi / Mihon / Tachimanga (iOS) extension for [Rakuten Magazine](https://magazine.rakuten.co.jp/) (`eu.kanade.tachiyomi.extension.ja.rakutenmagazine`).

## Features

- **Popular (人気順)**: Lists all 3,700+ magazines sorted by Rakuten Magazine's popularity ranking.
- **Latest (新着順)**: Lists magazines sorted by latest issue publication date.
- **Search & Kana Fuzzy Search**: Search by magazine title, issue title, category, description, or Japanese hiragana/katakana reading (via `/csv/magazine_list.csv`). Also supports direct URL lookup (`https://magazine.rakuten.co.jp/title/<id>` or `https://magazine.rakuten.co.jp/issue/<id>`).
- **Category & Featured Filters**: Filter by all 20 Rakuten Magazine categories (`女性ファッション`, `男性ファッション`, `グルメ・トラベル`, `IT・ガジェット`, `ニュース・週刊誌`, etc.) or filter to Featured / Notice titles (`/json/v1.0/noticeTitles`).
- **Back Numbers as Chapters**: Each magazine title (`/title/<titleIdentifier>`) lists all currently available issues (`/title/<titleIdentifier>/relatedIssue.json`) as chapters.
- **Full-Resolution Reader**:
  - Authenticates against `/read/<issueId>` using your Rakuten session.
  - Decrypts Aquafadas `documentInfo.proj` and `AVED0.proj` (`Salted__` OpenSSL AES-256-CBC with MD5 `EVP_BytesToKey` and per-issue `readerString`).
  - Extracts the unencrypted high-resolution (`~2176x3072`) embedded JPEG stream directly from each single-page Aquafadas PDF (`AVED0_A0_L0_Pxx.pdf`).
  - Automatically maintains a persistent `_REM_UUID` cookie so Rakuten Magazine never triggers its "2 browsers per 30 minutes" (`403 Forbidden`) limit.

---

## How to Install in Tachimanga (iOS)

### Option A: Direct APK Installation
1. Copy `repo/apk/tachiyomi-ja.rakutenmagazine-v1.4.1.apk` to your iPhone/iPad (e.g., via AirDrop, iCloud Drive, or Files).
2. Open **Tachimanga** → **Browse** → **Extensions** → tap **`+`** in the top-right corner → install the `.apk` file.

### Option B: Extension Repository URL
1. Host the `repo/` folder on GitHub (or any static HTTPS host).
2. In **Tachimanga**, go to **More** → **Extensions** → **Extension Repositories** → **Add Repository**, and paste the raw URL to `repo/index.min.json`:
   ```text
   https://raw.githubusercontent.com/brotherko/tachimanga-rakuten-magazine/main/repo/index.min.json
   ```
3. Refresh the **Extensions** tab and tap **Install** next to **Rakuten Magazine**.

---

## How to Configure Your Rakuten Login Session

Because iOS `Tachimanga` runs extensions in a background runtime where Android `WebView` cookies may not automatically sync:

1. In Tachimanga, go to **Browse** → **Sources** → **Rakuten Magazine** → tap the **Gear (Settings)** icon.
2. Open **Rakuten Session Cookie (Rz / Full Cookie)**.
3. Paste your `Rz` cookie value from `.rakuten.co.jp` (or a `Cookie` header string like `Rz=...; _REM=...`).
   - To get `Rz` on a desktop browser: log in at `https://magazine.rakuten.co.jp/`, open DevTools (`F12`) → **Application** → **Cookies** → `https://magazine.rakuten.co.jp`, and copy the value of `Rz`.
4. *(Optional)* **Browser Device UUID (`_REM_UUID`)**: Leave blank to use the extension's auto-generated persistent UUID, or paste your browser's `_REM_UUID` if you want the extension and your desktop browser to share the same browser slot.

---

## Building From Source

```bash
./gradlew :src:ja:rakutenmagazine:testDebugUnitTest :src:ja:rakutenmagazine:assembleRelease
```

The signed release APK is output to:
- `src/ja/rakutenmagazine/build/outputs/apk/release/tachiyomi-ja.rakutenmagazine-v1.4.1-release.apk`
- `repo/apk/tachiyomi-ja.rakutenmagazine-v1.4.1.apk`
