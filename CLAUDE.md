# LinguaBubble — Contexte projet (Chef de projet : Claude)

## Vision
Application Android : bulle flottante (overlay Accessibility) qui lit le contenu de l'écran,
extrait du vocabulaire japonais, affiche les infos du mot, et permet d'exporter vers Anki.
Focus : apprentissage japonais avec mécanique SRS et friction adaptative.

---

## Décisions techniques validées

### Stack
- Langage : **Kotlin**
- Min SDK : **API 35** (Android 15), target Android 16
- Architecture : **MVVM + Repository + UseCases**
- DI : **Hilt**
- BDD locale : **Room** (SQLite)
- OCR : **ML Kit Text Recognition v2** (on-device, gratuit, supporte japonais)
- Tokenisation JP : **Kuromoji** (lib Java, bundlable)
- Dictionnaire local : **JMdict** (SQLite pré-compilé, ~170k entrées, open source)
- Dictionnaire online : **Jisho.org** (API non-officielle, gratuite, sans clé)
- Traduction : **MyMemory API** (gratuit, sans clé, cible FR)
- Export Anki : **Générateur .apkg maison** (SQLite + ZIP)
- HTTP : **Retrofit + OkHttp**
- Tests : **JUnit 5 + Mockito + Espresso**

### Règle : pas d'hébergement perso, tout gratuit

---

## Fonctionnalités validées

### Bulle flottante
- État réduit : icône draggable
- État étendu : panneau de mots (tap sur la bulle)
- Compteur visible : nombre de mots dans le deck courant

### Capture écran (tap sur bulle)
1. AccessibilityService → texte des vues Android
2. MediaProjection → screenshot → ML Kit OCR
3. Fusion + déduplication
4. Kuromoji tokenisation → filtre noms/verbes/adjectifs
5. Lookup JMdict local → fallback Jisho si réseau dispo

### Affichage des mots
- Nouveaux mots : surbrillance verte, furigana + traduction + JLPT visibles
- Vus 1-3x : furigana masquée, tap pour révéler
- Vus 4x+ : tout masqué, friction active ("tu connais ce mot ?")
- Algorithme SRS : SM-2 simplifié

### Anki — Flux validé
- **Ajout** : appui long sur un mot → vibration haptique → ajout au "deck courant"
- **Export** : bouton Export → confirmation ("X mots, exporter et vider ?")
  → génère `/Downloads/LinguaBubble_[nom ou date].apkg`
  → vide le deck courant dans l'app (repart à zéro)
- **Nommage** : NE PAS supposer le nom du deck automatiquement (risque de doublons)
  → laisser l'utilisateur nommer le deck à l'export
- **Périodes** : courtes (3 jours à 1 semaine), chaque export = 1 deck Anki = 1 période
- **Template carte** : kanji / lecture (furigana) / traduction FR / niveau JLPT

### Langues
- V1 : japonais uniquement
- Plus tard : chinois mandarin, coréen

---

## Décisions finales (traduction + lecture)
- Traduction : **EN systématique** (JMdict), **FR si disponible** (MyMemory, best-effort)
- Lecture : **option utilisateur** dans les settings — toggle furigana (hiragana) ↔ romaji

---

## Structure projet (cible)
```
app/
├── core/
│   ├── data/
│   │   ├── local/        # Room DAOs, entities (JMdict, WordProgress, CurrentDeck)
│   │   └── remote/       # Retrofit (Jisho, MyMemory)
│   ├── domain/
│   │   ├── model/        # Word, WordProgress, ScreenSnapshot, DeckEntry
│   │   └── usecase/      # CaptureScreenUseCase, LookupWordUseCase, ExportAnkiUseCase
│   └── util/             # SRS (SM-2), Kuromoji wrapper, ApkgBuilder
├── feature/
│   ├── bubble/           # FloatingBubbleService + UI overlay
│   ├── worddetail/       # Détail d'un mot
│   ├── review/           # Session de révision SRS
│   ├── anki/             # AnkiExporter + CurrentDeckDao
│   └── settings/         # Paramètres (mode offline, etc.)
├── accessibility/         # LinguaAccessibilityService
└── ocr/                  # ML Kit wrapper + MediaProjection
```

---

## Backlog MVP (ordre de priorité)
1. Floating bubble draggable
2. AccessibilityService (texte vues)
3. ML Kit OCR + MediaProjection
4. Kuromoji tokenisation
5. JMdict local (Room) + schéma
6. Lookup + affichage dans bulle
7. SRS / WordProgress tracking
8. Friction UI (masquage adaptatif)
9. Export .apkg Anki (avec nommage manuel)
10. Fallback Jisho API
11. Tests unitaires (SRS, NLP, Anki)
12. Mode offline complet

---

## Conventions de code
- Kotlin idiomatique, commentaires en français
- Pas de magie cachée : chaque classe fait une chose
- Tout ce qui est testable doit avoir un test
