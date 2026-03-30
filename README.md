# 泡 Awabi — Japanese Vocabulary Bubble

**Awabi** is an Android overlay app that reads the screen while you play games, watch anime or read manga in Japanese — and silently collects vocabulary for you.

Tap the floating bubble → get instant word definitions. Long-press a word → add it to your Anki deck. Done.

---

## What it does

### Floating bubble
A small draggable bubble sits over any app. Tap it to trigger a screen capture and analysis. The bubble snaps to the screen edge when released, stays out of your way.

### Screen reading
Two complementary methods run in parallel:
- **OCR** (ML Kit on-device, Japanese model) — takes a screenshot via MediaProjection and extracts text from images, game UIs, visual novels, etc.
- **Accessibility Service** — reads text directly from Android views when available, as a fallback

Results are merged and deduplicated automatically.

### Word detection & lookup
The extracted text is scanned for Japanese words using a **longest-match greedy algorithm** against the local JMdict dictionary (~170k entries, runs fully offline). Each detected word is looked up for:
- Reading (furigana / romaji)
- English definition
- French translation (best-effort)
- JLPT level (N1–N5)

### Adaptive display (SRS)
Words are displayed with friction that grows the more you've seen them:
- **First time** — kanji + furigana + translation, highlighted in green
- **Seen 1–3×** — furigana hidden, tap to reveal
- **Seen 4×+** — everything hidden ("do you know this one?")

This is a simplified SM-2 spaced repetition mechanic built directly into the overlay.

### Grammar analysis (LLM)
Tap **✦ Analyser** in the panel to send the captured text to a language model. It returns:
- The corrected OCR text (the LLM infers likely kanji errors)
- The main sentence identified
- A grammar explanation adapted to your level (Beginner → Expert)
- A French translation of the main sentence
- Pronunciation for every Japanese term (romaji or hiragana, your choice)

Supported API providers — you bring your own key:
| Key prefix | Provider | Model |
|---|---|---|
| `github_pat_…` / `ghp_…` | GitHub Models | GPT-4o mini |
| `AIza…` | Google Gemini | Gemini 1.5 Flash |
| `sk-ant-…` | Anthropic | Claude Haiku |
| `sk-…` | OpenAI | GPT-4o mini |

### Anki export
Long-press any word in the panel → haptic feedback → word added to the current deck.

When you're ready, open the app → **Export (.apkg)** → name your deck → file lands in `/Downloads/`. The deck is cleared and you start fresh.

Cards include: kanji front / reading + EN definition + FR translation + JLPT level back. CSS-styled for clean display in Anki and AnkiDroid.

---

## Permissions required

| Permission | Why |
|---|---|
| `SYSTEM_ALERT_WINDOW` | Draw the floating bubble over other apps |
| `FOREGROUND_SERVICE` + `MEDIA_PROJECTION` | Keep the service alive and capture the screen |
| `BIND_ACCESSIBILITY_SERVICE` | Read text from Android views |
| `INTERNET` | LLM grammar analysis (optional), Jisho fallback lookup |
| `VIBRATE` | Haptic feedback on long-press |
| `POST_NOTIFICATIONS` | Persistent notification while bubble is active (Android 13+) |

---

## Setup

1. Install the APK
2. Open Awabi → grant **Overlay** and **Accessibility** permissions
3. Go to **⚙ Settings** → paste your API key for grammar analysis (optional)
4. Tap **Launch bubble** → authorize screen capture
5. Switch to any app — the bubble is ready

---

## Settings

- **API key** — your LLM key, detected automatically by prefix. Use the "Test connection" button to validate before saving.
- **Japanese level** — Beginner / Intermediate / Advanced / Expert — adapts the grammar explanations
- **Pronunciation** — Romaji or Hiragana for readings in the grammar panel
- **Profile** — free-text description of yourself sent to the LLM for more personalized explanations

---

## Tech stack

| Layer | Choice |
|---|---|
| Language | Kotlin |
| Min SDK | API 26 (Android 8) |
| Architecture | MVVM + Repository + UseCases |
| DI | Hilt |
| Local DB | Room (SQLite) |
| OCR | ML Kit Text Recognition v2 (on-device, Japanese) |
| Dictionary | JMdict SQLite (~170k entries, offline) |
| Fallback lookup | Jisho.org (unofficial API, no key needed) |
| LLM | User-provided key — GitHub Models / Gemini / Claude / OpenAI |
| Anki export | Custom .apkg builder (SQLite + ZIP) |
| HTTP | OkHttp |

---

## Roadmap

- [ ] Chinese (Mandarin) and Korean support
- [ ] Full offline mode (no LLM required)
- [ ] Deinflection tables for conjugated verb forms
- [ ] Audio TTS for words
- [ ] Sentence examples (Tatoeba / Jisho)
- [ ] Replace ML Kit with open-source OCR (Tesseract) for F-Droid eligibility
