# The parts of the BOOX `.note` format Papermill relies on

Established against Notes 46343 on a Palma 3 (firmware 4.3.1) by decoding a notebook that
Notes exported after its own "From Local File" PDF import, then importing modified and
from-scratch files and observing the result. Also imported on Notes 46258 (Note Mini C).

## Container

A `.note` file is a ZIP. Everything sits under one folder named after the notebook id.
A PDF-backed notebook with no ink has these files (records are protobuf):

| Path | Content |
|---|---|
| `note/pb/note_info` | Repeated field 1: the notebook record (a device export adds the parent folder's record) |
| `virtual/doc/pb/<notebook id>` | View state and default template |
| `virtual/page/pb/<id>` | Repeated field 1: one record per page |
| `pageModel/pb/<id>` | Repeated field 1: one ink-canvas record per page |
| `resource/content/<name>.pdf` | The backing PDF, unmodified |
| `resource/pb/<uuid>#<ms>` | Empty |
| `template/json/<uuid>.template_json` | Empty; named by the default template |
| `extra/pb/extra` | Format marker: field 1 = 1, field 2 = Notes version code, field 4 = notebook id |

A device export also has empty `stash/…` folders. A notebook with ink adds `shape/` and
`point/`, which Papermill doesn't write.

## Page record (`virtual/page/pb`)

| Field | Type | Meaning |
|---|---|---|
| 1 | string | Page id |
| 2, 3 | varint | Created, updated (ms) |
| 4 | float | Order, starting at 1 |
| 6 | string | `pageSize`: JSON rectangle, the page in Notes units. Must start at 0,0 |
| 7 | string | `pageSizeWithMargin`: the page plus writable margin |
| 8 | string | `contentPageSize`: the PDF page in points |
| 9 | string | `import_pdf` |
| 10 | string | `document/<notebook id>/resource/content/<name>.pdf` |
| 11 | string | `{"pageIndex":N,"pageReferenceId":"pdf-<object>-<generation>","pageUUID":""}` |

Rectangles are written as
`{"bottom":2970.0,"empty":false,"left":0.0,"right":2100.0,"stability":0,"top":0.0}`.

Observed behaviour:

- **Geometry comes only from these stored values.** Notes fits the PDF page (field 8) into
  `pageSize` (field 6), keeping its shape and centring it. Nothing is recomputed from the
  screen when a notebook opens.
- **The stock import writes A4 for every page**: `pageSize` 2100×2970, and a
  `pageSizeWithMargin` padded out to the screen's shape (2100×4200 on a 1:2 screen). A 1:2
  PDF page fitted into A4 is 1485×2970; A4 fitted into the screen leaves that at 70.7%.
- **Older Notes did not do this.** Notes 45960 (firmware 4.2) stores the PDF page's own
  shape scaled to a width of 2100 (2100×4200 for a 1:2 page), with field 7 equal to field 6,
  and shows it full bleed.
- **Setting fields 6 and 7 to the PDF page's own shape gives a full-bleed page.** Papermill
  uses the PDF page fitted inside 2100×2970, moved to the origin (1485×2970 for a 1:2 page),
  which keeps the stock import's units.
- **Field 11 identifies the PDF page.** The object number is the PDF's own page object.
  When a link is tapped, Notes builds the same string for the link's target and looks for
  the notebook page whose fields 10 and 11 equal it exactly, so the string must be written
  in exactly this form.

## View state (`virtual/doc/pb`)

| Field | Type | Meaning |
|---|---|---|
| 1 | string | Doc id (the importer replaces it with the notebook id) |
| 2, 3 | varint | Created, updated (ms) |
| 4 | string | Id of the page to open on |
| 5 | float | Zoom: screen pixels per page unit |
| 9 | string | Default template JSON for new pages |
| 10 | string | Layout options JSON |

The stock import writes zoom = screen width ÷ 2100. Notes keeps a stale zoom until the page
is turned: a file with a 1485-wide page and the stock zoom opened at 70.7% and became
full-bleed on the first page turn. Papermill writes the zoom that fits the first page to the
screen it runs on.

## Notebook record (`note/pb/note_info`)

Papermill writes fields 1 (id), 2, 3 (times), 6 (title), 8, 9, 10, 11 (pen settings),
12 (per-page canvas info, keyed by page id), 13 (background), 14 (device and screen size),
15, 16, 18, 20 (page id list), 21, 22, 23 (screen size), 24, 31, 37, 40, 43 and 44, with
the values the stock import wrote.

A device export also carries field 4 (parent folder id), field 39 (account id), a second
record describing the parent folder, and the account id again in `extra` field 3. Papermill
omits all of these; import works without them and files the notebook at the top level.

## Import

Opening a `.note` file starts Notes' import screen ("A new notebook will be created…").
The importer:

- issues a new notebook id and new page ids, and rewrites field 10 of each page to match;
- copies the PDF unchanged and keeps the stored page sizes and zoom;
- names the notebook after the file, usually with a number appended.

Notes resolves the incoming link to a file path. A link from a private `FileProvider` is
rejected ("invalid path"); a media-library link to a file in shared storage works.

## Why the other import paths fall short

- **Page + → From Files** stores the PDF page's own shape, but first copies the chosen pages
  into a new PDF. In the copy, a link survives only if its target page had already been
  copied, so forward links are lost and backward links work. (Rewriting each link target
  from a page reference to a bare page number survives the copy and works in Notes, but is
  not standard PDF.)
- **Native Backup & Restore** is not an import: its own text says restore overwrites
  existing notes locally and in the cloud.
