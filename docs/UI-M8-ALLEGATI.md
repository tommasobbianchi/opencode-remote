# M8 — Allegati nel prompt (immagini e file)

Specifica di implementazione. Contratto API verificato sul server 2.1.4 il 24 ott 2026.

## 1. Contratto server (fatti verificati, non da riscoprire)

`POST /api/session/{sessionID}/prompt` accetta, oltre a `text` e `delivery`:

```json
{
  "text": "…",
  "files": [
    { "uri": "data:image/png;base64,iVBOR…", "name": "prova.png", "description": "…" }
  ]
}
```

- `uri` obbligatorio. Forme accettate: `data:<mime>;base64,<…>` (inline) e
  `file://<path>` (file già presente **sul server** — non usato da questa app).
- Mime accettati dal server: `image/*`, `application/pdf`, `application/json`,
  `text/*`, `application/octet-stream`. Altri → 400 `Unsupported attachment URI`.
- `name` e `description` opzionali.
- **Limite inline misurato**: 4 MB di byte decodificati → 200; 6 MB → 500 con
  body vuoto e log `Schema validation failed … Prompt.Base64`. Quindi il tetto
  sicuro per l'invio inline è **4 MB per file** (dopo l'eventuale compressione).
- Il messaggio salvato espone i file così:

```json
{ "type": "user", "text": "…", "files": [
  { "data": "<base64>", "mime": "image/png", "source": {"type": "inline"},
    "name": "prova.png", "description": "…" } ] }
```

Nota: `source.type` può essere `uri` (con `uri` al posto di `data`) per gli
allegati da percorso server. Il client deve gestire entrambi e ignorare i campi
che non conosce.

## 2. Limiti del client

| Cosa | Valore |
|---|---|
| File per messaggio | **1** (il modello dati resta una lista) |
| Dimensione massima in ingresso | **50 MB** (oltre: errore, non si allega) |
| Immagini | ridimensionate a **max 1600 px** lato lungo, JPEG qualità 80 |
| File non immagine | inviati inline solo se **≤ 4 MB** dopo lettura; oltre → errore |
| Tipi | immagini (galleria + fotocamera), PDF, testo, JSON, zip/binari generici |

Se un'immagine, dopo la compressione, supera 4 MB: secondo tentativo a 1280 px
e qualità 70; se ancora oltre → errore "immagine troppo grande".

## 3. Contratto di stato (ViewModel ↔ UI)

In `UiState`:

```kotlin
val attachment: AttachmentUi? = null          // al massimo uno
val attachmentPreparing: Boolean = false      // lettura/compressione in corso
val attachmentError: String? = null
```

```kotlin
data class AttachmentUi(
    val name: String,
    val mime: String,
    val sizeBytes: Long,     // byte originali
    val isImage: Boolean,
    val previewUri: String,  // content:// originale, per la miniatura
)
```

In `MessageUi` (per i messaggi già sul server):

```kotlin
val files: List<MessageFileUi> = emptyList()

data class MessageFileUi(
    val name: String?,
    val mime: String?,
    val description: String?,
    val dataBase64: String?,   // inline
    val uri: String?,          // sorgente uri (file://…)
)
```

Azioni pubbliche del ViewModel:

```kotlin
fun attach(uri: Uri)      // prepara (lettura, ridimensionamento, cap) e imposta attachment
fun clearAttachment()     // rimuove allegato ed errore
fun dismissAttachmentError()
// sendPrompt() usa attachment (se presente) e lo azzera a invio riuscito
```

## 4. Comportamento UI

### 4.1 Composer

- Icona **graffetta** a sinistra del campo di testo, 48 dp, colore `mutedText`,
  `contentDescription = "Allega un file"`. Disabilitata se `openReadOnly`, se
  non connesso o se `attachmentPreparing`.
- Tap → `ModalBottomSheet` con tre voci, ognuna 48 dp di altezza minima:
  - **Galleria** — `PickVisualMedia` (solo immagini, nessun permesso).
  - **Fotocamera** — `TakePicture` su URI `FileProvider` (`${packageName}.fileprovider`).
  - **File** — `OpenDocument` con `arrayOf("*/*")`.
- Con un allegato: sopra il campo compare una **riga allegato**: miniatura
  40×40 dp arrotondata (immagini) oppure icona documento, nome su una riga con
  ellissi, peso leggibile (`1,2 MB`, `840 kB`), e una X a destra (48 dp,
  `contentDescription = "Rimuovi allegato"`).
- Durante `attachmentPreparing`: la riga mostra "preparo l'allegato…" con
  `WorkingIndicator` e la X è sostituita da un indicatore.
- `attachmentError`: testo breve in `oc.error` sotto la riga, con tap per
  chiudere (`dismissAttachmentError`). Mai un dialog.
- Il pulsante di invio resta disabilitato se non c'è testo **e** non c'è allegato.

### 4.2 Bolla utente

- Sotto il testo, gli allegati nell'ordine: immagini come **miniatura**
  (max 220×220 dp, angoli `shapes.medium`, `contentScale = Crop`), tap →
  schermo intero (stesso pattern del foglio di dettaglio: fondo `background`,
  swipe/tap per chiudere, "chiudi" in alto a destra).
- File non immagine: chip con icona documento, nome, peso se noto.
- Le miniature si decodificano dai `files[].dataBase64` (inline) con
  `BitmapFactory` e cache in memoria per chiave `messageId|indice`; se manca
  `data` e c'è `uri` → solo chip con nome, nessuna anteprima.
- Messaggi ottimistici (pending): mostrano la miniatura dall'URI locale già
  scelto, senza attendere il server.

### 4.3 Copy (minuscolo, breve)

| contesto | testo |
|---|---|
| pulsante | "Allega un file" (contentDescription) |
| foglio | "galleria" · "fotocamera" · "file" · "annulla" |
| riga allegato | "preparo l'allegato…" |
| errore troppo grande | "file troppo grande: massimo 50 MB" |
| errore file non immagine | "il server accetta fino a 4 MB per i file non immagine" |
| errore lettura | "non riesco a leggere il file" |

## 5. Dove sta cosa

| Area | File |
|---|---|
| Richiesta prompt con `files` | `net/OpenCodeApi.kt` (`PromptBody`), `net/PromptFile.kt` |
| Parsing `files` dai messaggi | `net/Models.kt` (`MessageUi`, `MessageFileUi`) |
| Preparazione allegato (Android: resolver, Bitmap, base64) | `attachments/AttachmentPreparer.kt` + `attachments/AttachmentRules.kt` (puro, testabile) |
| Stato e azioni | `AppViewModel.kt` (`attach`, `clearAttachment`, `dismissAttachmentError`, `sendPrompt`) |
| UI composer, foglio, riga allegato, bolle | `ChatScreen.kt`, `MainScreen.kt`, `strings.xml` |
| FileProvider | `AndroidManifest.xml`, `res/xml/file_paths.xml` |

## 6. Test JVM richiesti

- `AttachmentRulesTest`: mime → estensione/data URI; soglie (50 MB ingresso,
  4 MB inline); scelta del ridimensionamento (1600 → 1280 → errore).
- `PromptFilesTest`: `PromptBody` con `files` serializza `uri`/`name`/`description`
  e resta compatibile quando `files` è vuoto (campo assente o lista vuota).
- `MessageFilesTest`: il JSON di un messaggio con `files[]` inline popola
  `MessageUi.files`; `source.type = "uri"` popola `uri` e lascia `dataBase64` null;
  campi sconosciuti ignorati.

## 7. Criteri di accettazione

1. Scelgo un'immagine dalla galleria → riga allegato con miniatura e peso; invio
   → la bolla utente mostra la miniatura e il modello la riceve.
2. Scatto una foto → stesso flusso (immagine ridimensionata, invio ≤ 4 MB).
3. Scelgo un PDF da 2 MB → parte inline e il modello lo riceve.
4. Scelgo un file da 8 MB non immagine → errore chiaro, nessun invio.
5. Scelgo un'immagine da 30 MB → ridimensionata, invio riuscito.
6. Rimuovo l'allegato con la X → invio solo testo.
7. Nessun contenuto di file nei log e niente scritture su disco dell'app.
8. Build verde con i test JVM esistenti + quelli nuovi.
