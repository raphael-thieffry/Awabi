# LinguaBubble (Awabi) — Contexte projet

## Vision
Application Android : bulle flottante (overlay Accessibility) qui lit le contenu de l'écran,
extrait du vocabulaire japonais, affiche les infos du mot, et permet d'exporter vers Anki.
Focus : apprentissage japonais avec mécanique SRS et friction adaptative.

---

## Stack

- Langage : **Kotlin** 2.1.20
- Min SDK : **API 26**, target **API 35** (Android 15)
- Architecture : **MVVM + Repository + UseCases**
- DI : **Hilt** 2.56.1
- BDD locale : **Room** 2.6.1 (SQLite, v3 avec migrations)
- OCR : **ML Kit Text Recognition v2** (on-device, gratuit, japonais)
- Tokenisation JP : **DictionaryScanner** (longest-match greedy sur JMdict, max 8 chars, 12 tokens)
- Dictionnaire local : **JMdict** (SQLite bundlé en asset, ~170k entrées) — actif par défaut
- Dictionnaire online : **Jisho.org** (API non-officielle, gratuite, sans clé) — fallback réseau
- Traduction : **EN systématique** (JMdict) ; **FR best-effort** (non implémenté — `definitionFr` toujours vide)
- Analyse grammaticale : **LLM multi-provider** (clef utilisateur côté client)
- Export Anki : **Générateur .apkg maison** (SQLite `collection.anki2` + ZIP)
- HTTP : **Retrofit 2.11** + **OkHttp 4.12**

### Règle : pas d'hébergement perso, tout gratuit (sauf LLM optionnel user-side)

---

## Fonctionnalités implémentées

### Bulle flottante
- État réduit : icône draggable (spring drag + fling physics + snap magnétique bords)
- État étendu : panneau horizontal de mots (tap sur la bulle)
- Zone de fermeture : drop en bas de l'écran (45dp) pour stopper le service
- Bouton "Ouvrir MainActivity" dans le panneau

### Capture écran (tap sur bulle)
1. Masque l'overlay 150ms
2. MediaProjection → screenshot → ML Kit OCR japonais
3. Fallback : AccessibilityService → texte des vues Android
4. DictionaryScanner tokenisation (longest-match JMdict)
5. Lookup JMdict local → fallback Jisho si réseau dispo (délai 120ms/token anti rate-limit)
6. Tri par `clickCount` ASC (mots jamais cliqués en premier)

### Affichage des mots
- Gradient couleur selon `clickCount` : 0 = bleu (nouveau), 1-4 = normal, 5+ = gris discret
- Badge numérique `0-9+` sur chaque mot
- Tap cycle : kanji → lecture → définition EN
- Appui long : ajout au deck courant + coche verte 1.2s
- SRS : **compteur de clics** (pas SM-2 — friction visuelle uniquement)

### Analyse grammaticale (LLM)
- Bouton "Analyser" dans le panneau → envoie le texte brut OCR au LLM
- Réponse structurée en 4 sections : phrase principale, analyse grammaticale FR, traduction FR, correction
- Retry automatique ×2 si parsing échoue
- Clef API stockée en SharedPreferences, provider détecté automatiquement par préfixe

**Providers LLM supportés** :
| Provider | Préfixe clef | Modèle |
|----------|-------------|--------|
| GitHub Models | `github_pat_`, `ghp_` | gpt-4o-mini |
| OpenAI | `sk-` | gpt-4o-mini |
| Google Gemini | `AIza` | gemini-1.5-flash |
| Anthropic | `sk-ant-` | claude-haiku-4-5-20251001 |

### Anki — Flux
- **Ajout** : appui long sur un mot → vibration haptique → ajout au "deck courant"
- **Export** : bouton Export dans MainActivity → dialog nommage → génère `/Downloads/<nom>_<timestamp>.apkg`
  → marque les mots comme exportés (`wasExported = true`) → vide le deck courant
- **Nommage** : utilisateur nomme le deck à l'export (pas de nom automatique — risque de doublons)
- **Template carte** : Kanji / Lecture / Traduction EN / Traduction FR (vide si non dispo) / JLPT

### Settings
- Clef API LLM (avec bouton "Test" et détection provider)
- Niveau JLPT (Débutant / Intermédiaire / Avancé / Expert)
- Mode lecture : Hiragana ↔ Romaji
- Description personnelle (contexte injecté dans le prompt LLM)

### Langues
- V1 : japonais uniquement
- Plus tard : chinois mandarin, coréen

---

## Structure projet réelle

```
app/src/main/java/com/linguabubble/
├── MainActivity.kt / MainViewModel.kt
├── AwabiApp.kt
├── accessibility/
│   └── LinguaAccessibilityService.kt
├── feature/
│   ├── bubble/
│   │   ├── FloatingBubbleService.kt  ← cœur (753 lignes)
│   │   ├── BubbleTileService.kt      ← Quick Settings tile
│   │   ├── TileActivity.kt           ← pont permission MediaProjection
│   │   ├── WordRepository.kt
│   │   └── WordAdapter.kt
│   ├── anki/
│   │   ├── CurrentDeckRepository.kt
│   │   └── DeckEntryAdapter.kt
│   ├── progress/
│   │   ├── WordProgressActivity.kt / ViewModel / Adapter
│   └── settings/
│       └── SettingsActivity.kt
├── core/
│   ├── data/local/   # AppDatabase (v3), WordProgressEntity/Dao, DeckEntryEntity/Dao, LocalDictionary
│   ├── data/remote/  # JishoApiService (Retrofit), GrammarApiService (OkHttp multi-provider)
│   ├── domain/model/ # WordEntry
│   └── domain/usecase/ # AnalyzeGrammarUseCase
└── core/util/        # DictionaryScanner, ApkgBuilder, LlmProvider, NetworkModule, DatabaseModule, DebugStore
└── ocr/
    └── OcrProcessor.kt
```

---

## Backlog MVP

- [x] Floating bubble draggable (spring + fling + snap)
- [x] AccessibilityService (texte vues)
- [x] ML Kit OCR + MediaProjection
- [x] DictionaryScanner tokenisation (longest-match JMdict)
- [x] JMdict local (asset SQLite bundlé)
- [x] Lookup + affichage dans bulle (JMdict + Jisho fallback)
- [x] SRS / WordProgress tracking (clickCount, seenCount, wasExported)
- [x] Friction UI (gradient couleurs adaptatif)
- [x] Export .apkg Anki (nommage manuel, SQLite collection.anki2)
- [x] Fallback Jisho API
- [x] LLM multi-provider (analyse grammaticale contextuelle)
- [x] Quick Settings tile
- [ ] Tests unitaires (SRS, NLP, Anki)
- [ ] Traduction FR (MyMemory API — `definitionFr` non implémenté)
- [ ] Mode offline complet (JMdict oui, Jisho non)

---

## Issues / Todo futur

### Bugs connus
- [ ] **`reading` absent de WordProgress** : `WordProgressEntity` ne stocke pas la lecture — l'activité progression affiche les mots sans furigana.
- [ ] **`definitionFr` toujours vide** : MyMemory API non implémentée. Les cartes Anki exportent un champ FR vide.
- [ ] **Parsing LLM fragile** : utilise `.indexOf()` sur les balises `[CORRECTED]` etc. — risque si le LLM répète les balises dans son texte.

### UX
- [ ] Bouton fermeture propre (alternative à la close zone drop)

### Technique
- [ ] Ajouter `gradlew` dans le PATH de l'env (ou un script `run.sh`)

---

## Conventions de code
- Kotlin idiomatique, commentaires en français
- Pas de magie cachée : chaque classe fait une chose
- Tout ce qui est testable doit avoir un test
