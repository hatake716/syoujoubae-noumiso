# MaleCNS v1.0 data

Source: https://male-cns.janelia.org/download/
License: CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/).
Attribution and modifications: [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).

The freely downloadable derived data lives in
[`app/src/main/assets/atlas`](../app/src/main/assets/atlas) and
[`atlas_models/src/main/assets/atlas`](../atlas_models/src/main/assets/atlas). An app purchase is
not needed to obtain or reuse it under CC BY 4.0.

| Asset | Representation |
|---|---|
| `atlas_models: meshes/*.bin` | MCN2 indexed chunks preserving every original triangle and vertex position |
| `base: meshes/interactive/*.bin` | Same MCN2 format, at most 20,000 triangles per region |
| `meshes.json` | official ROI IDs/names, source URL/hash, bounds, original/reduced triangle counts |
| `skeletons/*.bin` | unmodified official precomputed skeleton: uint32 LE vertex and edge counts, nm XYZ float32, uint32 edge indices |
| `skeletons.json` | deterministic offline subset, source URLs and SHA-256 of each original skeleton |
| Overview rendering | Reads all original edges from the 523 existing skeleton files; no duplicated preview asset |
| `database/part-*.dat` | sequential chunks of a single read-only SQLite file; concatenate in lexical filename order |
| `connections.json` | source hash, graph edge and synapse totals |
| `regions.json` | original Japanese explanations and primary references (app-author rights; not CC dataset content) |

MCN2: ASCII `MCN2`, uint32 LE chunk count, then for each chunk uint32 vertex count
and uint32 index count. Each 16-byte vertex stores float32 micrometre XYZ and four
signed normal bytes (XYZ normalized by 127, fourth byte zero). Indices are uint16
LE in original triangle order, at most 60,000 indices and 60,000 vertices per chunk.
Chunking may duplicate boundary vertices but neither changes the original positions
nor discards any original triangle in the full-detail files.

Example extraction of the full packaged catalog and adjacency index:

```sh
cat app/src/main/assets/atlas/database/part-*.dat > male-cns-atlas.db
```

SQLite tables:

- `neurons`: one row per official `status=Traced` neuron. Original IDs are retained.
- `connections`: one row per neuron, columns `outputs`, `inputs`, `nout`, `nin`,
  `wout`, `win`. A NULL blob means no edges in that direction.
- A blob is gzip-compressed. After decompression, read alternating unsigned
  LEB128 `partner-ID delta` and `weight` values until EOF. IDs are accumulated
  from zero. The records are sorted by partner ID; the UI sorts by weight.
  `nout`/`nin` count distinct connected partners; `wout`/`win` sum weights.
- `tools/test_data.py:decode` and `AtlasRepository.decodeAdjacency` are reference
  readers. Both directions contain the full same directed graph; reverse index
  edges do not mean biological reciprocity.

Coordinate agreement is essential: official skeletons use **nanometres**, while
SWC from a different published directory uses **8nm voxel units**. This app
fetches only the documented nanometre precomputed endpoint. Meshes are converted
from nanometres to micrometres during the build pipeline. The renderer applies
a shared origin and uniform scale, preserving alignment without mirroring.

The database excludes non-traced endpoints; this is explicitly an induced graph
of traced neurons, not every fragment in the source segmentation. Its 25,563,197
edges retain the original integer weights with no threshold. There is no
fictional neurite generation or inferred synapse geometry in the assets.

`provenance/manifest.json` lists packaged file hashes and the acquisition date.
Raw downloads in `source/` are a local cache and are not committed.
