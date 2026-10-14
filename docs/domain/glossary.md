# Glossary

The project's vocabulary, with the code each term maps to. If a term in a review
comment or an ADR isn't clear, it should be here. If it isn't, please add it.

| Term | Meaning | Code |
|---|---|---|
| **Catalog** | A source of books behind the provider-agnostic port: `find`, `search`, `download`. | [`domain/Catalog.kt`](../../pixerion-core/src/main/kotlin/io/modernia/pixerion/domain/Catalog.kt) |
| **Source** | A concrete upstream provider (MangaDex). Selected by name with `--source` / `{source}`. | `source/*` |
| **Scheme** | The name a source owns in references and the registry, e.g. `mangadex`. Each adapter's `SCHEME` constant. | `MangaDexCatalog.SCHEME` |
| **Adapter** | A `Catalog` implementation for one source, split into a *catalog* (DTO → domain mapping) and a *client* (HTTP/JSON). | `MangaDexCatalog` + `MangaDexClient` |
| **Leaf adapter** | An adapter bound to a single source, as opposed to a (not yet existing) *aggregating* catalog that would route across sources. | — |
| **Registry** | The fixed table mapping a scheme to an adapter factory. The only place sources are registered. | [`source/CatalogRegistry.kt`](../../pixerion-core/src/main/kotlin/io/modernia/pixerion/source/CatalogRegistry.kt) |
| **Book** | A work in domain terms: `id`, `ref`, `title`, `synopsis`. | [`domain/Book.kt`](../../pixerion-core/src/main/kotlin/io/modernia/pixerion/domain/Book.kt) |
| **BookRef** | Any handle to a book. A sealed type: either a `BookId` or a `SourceRef`. | [`domain/BookRef.kt`](../../pixerion-core/src/main/kotlin/io/modernia/pixerion/domain/BookRef.kt) |
| **BookId** | Portable, cross-source identity. A `@JvmInline value class`, so its getter can't be called from Java; use `Refs.idOf`. Leaf adapters mint it from the native id. | [`domain/BookId.kt`](../../pixerion-core/src/main/kotlin/io/modernia/pixerion/domain/BookId.kt) |
| **SourceRef** | A `(scheme, value)` handle scoped to one source. Written as `scheme:value` in the CLI and API, e.g. `mangadex:801513ba-…`. | [`domain/SourceRef.kt`](../../pixerion-core/src/main/kotlin/io/modernia/pixerion/domain/SourceRef.kt) |
| **Bare id / ref** | A CLI argument without `scheme:`, interpreted under `--source`. | `CatalogCommand` |
| **Absence vs failure** | The core invariant: "not found" is a successful `null`/empty result, and "couldn't reach or understand the source" is a `CatalogException`. | [`domain/CatalogException.kt`](../../pixerion-core/src/main/kotlin/io/modernia/pixerion/domain/CatalogException.kt) |
| **Page** | A lazily fetched image descriptor: `chapter`, `number`, `filename`, `suspend bytes()`. | [`domain/Page.kt`](../../pixerion-core/src/main/kotlin/io/modernia/pixerion/domain/Page.kt) |
| **DownloadEvent** | An item in the cold download stream: `Manifest(chapters)` → `ChapterStarted(chapterId, label, pages)` → `PageReady(chapterId, page)`. | [`domain/DownloadEvent.kt`](../../pixerion-core/src/main/kotlin/io/modernia/pixerion/domain/DownloadEvent.kt) |
| **Cold flow** | A Kotlin `Flow` that does nothing until it's collected. Collecting a download is what runs it. | — |
| **Chapter label** | The source's display name for a chapter (`"1"`, `"12.5"`, possibly blank). Used to name the directory. | `ChapterStarted.label` |
| **chapterId** | The source-scoped id of a specific chapter upload. Used to attribute pages, because labels can collide. | `ChapterStarted.chapterId` |
| **Scanlation group / upload** | MangaDex hosts several fan translations ("uploads") of the same chapter. Pixerion keeps the first per label. | `MangaDexCatalog.download` |
| **MangaDex@Home** | MangaDex's volunteer image CDN (`*.mangadex.network`). Rate-limited, and expects success/failure reports. | `MangaDexClient` (at-home, report) |
| **At-home server** | The per-chapter image host returned by `/at-home/server/{chapterId}`. | `MangaDexClient` |
| **Downloader** | `core` orchestration: resolve the book, collect events, write pages through a bounded pool. | [`download/Downloader.kt`](../../pixerion-core/src/main/kotlin/io/modernia/pixerion/download/Downloader.kt) |
| **Layout / StandardLayout** | Maps a book or page to a path: `<root>/<source>/<book>/chapters/ch-<label>/<NNN>.<ext>`. | [`download/Layout.kt`](../../pixerion-core/src/main/kotlin/io/modernia/pixerion/download/Layout.kt) |
| **Purpose layer** | A sibling directory under a book dir that separates output kinds: `chapters/` (raw pages) and `cbz/` (archives). | `StandardLayout.CHAPTERS_DIR`, `Bundler.DEFAULT_SUBDIR` |
| **Root** | Base download directory. Default `~/.pixerion`, override with `-o/--output`. | `Downloader.defaultRoot()` |
| **DownloadProgress** | Listener for live progress (`onStart`, `onChapterStart`, `onPageWritten`, `onChapterComplete`). Callbacks are serialized. | [`download/DownloadProgress.kt`](../../pixerion-core/src/main/kotlin/io/modernia/pixerion/download/DownloadProgress.kt) |
| **Bundle / .cbz** | A comic book ZIP: one per chapter, with images stored uncompressed and a `ComicInfo.xml` (Series/Number/PageCount). Readable by Komga, Kavita, Panel, and Mihon. | [`bundle/Bundler.kt`](../../pixerion-core/src/main/kotlin/io/modernia/pixerion/bundle/Bundler.kt) |
| **Interop facade** | `BlockingCatalog` + `Refs`: the only way Java code calls `core`. | [`interop/`](../../pixerion-core/src/main/kotlin/io/modernia/pixerion/interop/) |
| **Front-end** | Anything that consumes `core` and renders: the CLI and the server. (The *web frontend* is the Angular webapp.) | — |
| **Self-issued JWT** | An HS256 token the server signs and verifies itself, with the claims `iss=pixerion`, `sub=<email>`, `roles=[ROLE_…]`. | `TokenController`, `SecurityConfig` |
| **ROLE_ prefix** | Role names are stored, granted, and put in the token *with* `ROLE_`, so nothing has to rewrite them. | `V2__create_roles.sql` |

## Related

- [Core library](../architecture/core.md)
- [Architecture overview](../architecture/overview.md)
- [Back to the hub](../index.md)
