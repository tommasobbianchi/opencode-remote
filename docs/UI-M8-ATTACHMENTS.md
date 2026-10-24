# M8 — Attachments in the prompt (images and files)

Implementation spec. API contract verified against server 2.1.4 on Oct 24 2026.

## 1. Server contract (verified facts, not to be rediscovered)

`POST /api/session/{sessionID}/prompt` accepts, besides `text` and `delivery`:

```json
{
  "text": "…",
  "files": [
    { "uri": "data:image/png;base64,iVBOR…", "name": "sample.png", "description": "…" }
  ]
}
```

- `uri` is mandatory. Accepted forms: `data:<mime>;base64,<…>` (inline) and
  `file://<path>` (file already present **on the server** — not used by this app).
- Mimes accepted by the server: `image/*`, `application/pdf`, `application/json`,
  `text/*`, `application/octet-stream`. Anything else → 400 `Unsupported attachment URI`.
- `name` and `description` are optional.
- **Measured inline limit**: 4 MB of decoded bytes → 200; 6 MB → 500 with an empty
  body and the log line `Schema validation failed … Prompt.Base64`. So the safe
  ceiling for inline sending is **4 MB per file** (after any compression).
- The stored message exposes the files like this:

```json
{ "type": "user", "text": "…", "files": [
  { "data": "<base64>", "mime": "image/png", "source": {"type": "inline"},
    "name": "sample.png", "description": "…" } ] }
```

Note: `source.type` can be `uri` (with `uri` instead of `data`) for attachments
coming from a server path. The client must handle both and ignore fields it does
not know.

## 2. Client limits

| What | Value |
|---|---|
| Files per message | **1** (the data model stays a list) |
| Maximum input size | **50 MB** (above that: error, nothing is attached) |
| Images | resized to **max 1600 px** on the long side, JPEG quality 80 |
| Non-image files | sent inline only if **≤ 4 MB** after reading; above that → error |
| Types | images (gallery + camera), PDF, text, JSON, generic zip/binaries |

If an image still exceeds 4 MB after compression: second attempt at 1280 px and
quality 70; if it is still too big → "image too large" error.

## 3. State contract (ViewModel ↔ UI)

In `UiState`:

```kotlin
val attachment: AttachmentUi? = null          // at most one
val attachmentPreparing: Boolean = false      // reading/compressing in progress
val attachmentError: String? = null
```

```kotlin
data class AttachmentUi(
    val name: String,
    val mime: String,
    val sizeBytes: Long,     // original bytes
    val isImage: Boolean,
    val previewUri: String,  // original content:// URI, for the thumbnail
)
```

In `MessageUi` (for messages already on the server):

```kotlin
val files: List<MessageFileUi> = emptyList()

data class MessageFileUi(
    val name: String?,
    val mime: String?,
    val description: String?,
    val dataBase64: String?,   // inline
    val uri: String?,          // uri source (file://…)
)
```

Public ViewModel actions:

```kotlin
fun attach(uri: Uri)      // prepares (read, resize, cap) and sets attachment
fun clearAttachment()     // removes attachment and error
fun dismissAttachmentError()
// sendPrompt() uses attachment (if any) and clears it on a successful send
```

## 4. UI behaviour

### 4.1 Composer

- **Paperclip** icon inside the text field pill, at the right end, 40 dp touch
  target, `mutedText` colour, `contentDescription = "Attach a file"`. Disabled
  when `openReadOnly`, when not connected or when `attachmentPreparing`.
- Tap → `ModalBottomSheet` with three rows, each at least 48 dp high:
  - **Gallery** — `PickVisualMedia` (images only, no permission).
  - **Camera** — `TakePicture` on a `FileProvider` URI (`${packageName}.fileprovider`).
  - **File** — `OpenDocument` with `arrayOf("*/*")`.
- With an attachment: an **attachment row** appears above the field: 40×40 dp
  rounded thumbnail (images) or document icon, name on one line with ellipsis,
  readable size (`1.2 MB`, `840 kB`), and an X on the right (48 dp,
  `contentDescription = "Remove attachment"`).
- While `attachmentPreparing`: the row shows "preparing the attachment…" with a
  `WorkingIndicator`.
- `attachmentError`: short text in `oc.error` below the row, tap to dismiss
  (`dismissAttachmentError`). Never a dialog.
- The send button stays disabled when there is no text **and** no attachment.

### 4.2 User bubble

- Below the text, the attachments in order: images as a **thumbnail**
  (max 220×220 dp, `shapes.medium` corners, `contentScale = Crop`), tap →
  full screen (same pattern as the detail sheet: `background` colour,
  swipe/tap to close, "close" at the top right).
- Non-image files: chip with document icon, name, size when known.
- Thumbnails are decoded from `files[].dataBase64` (inline) with
  `BitmapFactory` and an in-memory cache keyed by `messageId|index`; if `data` is
  missing and there is a `uri` → chip with the name only, no preview.
- Optimistic messages (pending): show the thumbnail from the local URI already
  picked, without waiting for the server.

### 4.3 Copy (lowercase, short)

| context | text |
|---|---|
| button | "Attach a file" (contentDescription) |
| sheet | "gallery" · "camera" · "file" · "cancel" |
| attachment row | "preparing the attachment…" |
| too large error | "file too large: maximum 50 MB" |
| non-image file error | "the server accepts up to 4 MB for non-image files" |
| read error | "cannot read the file" |

## 5. Where things live

| Area | File |
|---|---|
| Prompt request with `files` | `net/OpenCodeApi.kt` (`PromptBody`), `net/PromptFile.kt` |
| Parsing `files` from messages | `net/Models.kt` (`MessageUi`, `MessageFileUi`) |
| Attachment preparation (Android: resolver, Bitmap, base64) | `attachments/AttachmentPreparer.kt` + `attachments/AttachmentRules.kt` (pure, testable) |
| State and actions | `AppViewModel.kt` (`attach`, `clearAttachment`, `dismissAttachmentError`, `sendPrompt`) |
| Composer UI, sheet, attachment row, bubbles | `ChatScreen.kt`, `MainScreen.kt`, `Attachments.kt` |
| FileProvider | `AndroidManifest.xml`, `res/xml/file_paths.xml` |

## 6. Required JVM tests

- `AttachmentRulesTest`: mime → extension/data URI; limits (50 MB input,
  4 MB inline); resize choice (1600 → 1280 → error).
- `PromptFilesTest`: `PromptBody` with `files` serializes `uri`/`name`/`description`
  and stays compatible when `files` is empty (field absent or empty list).
- `MessageFilesTest`: the JSON of a message with inline `files[]` populates
  `MessageUi.files`; `source.type = "uri"` populates `uri` and leaves `dataBase64`
  null; unknown fields ignored.

## 7. Acceptance criteria

1. I pick an image from the gallery → attachment row with thumbnail and size;
   send → the user bubble shows the thumbnail and the model receives it.
2. I take a photo → same flow (image resized, send ≤ 4 MB).
3. I pick a 2 MB PDF → it goes inline and the model receives it.
4. I pick an 8 MB non-image file → clear error, nothing sent.
5. I pick a 30 MB image → resized, send succeeds.
6. I remove the attachment with the X → only text is sent.
7. No file contents in the logs and no writes to the app's disk.
8. Green build with the existing JVM tests + the new ones.
