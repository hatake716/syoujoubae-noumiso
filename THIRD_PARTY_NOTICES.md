# Third-party notices

## MaleCNS v1.0 data — CC BY 4.0

**Title:** Male CNS Connectome, `male-cns:v1.0` (released June 8, 2026).

**Attribution:** FlyEM (HHMI Janelia), University of Cambridge Department of
Zoology, MRC Laboratory of Molecular Biology, Google Research, and the Male CNS
project contributors. Individual author lists and the current project paper are
linked from the [official project site](https://male-cns.janelia.org/).

**Source:** [official download page](https://male-cns.janelia.org/download/).
**License:** [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/),
[full text](docs/licenses/CC-BY-4.0.txt).

This applies to the source and transformed ROI meshes, neuron skeletons,
neuron annotations and chemical connection graph. It does not apply to this
app's original code or original explanation text. Scientific facts and names
remain facts and names.

**Changes:** All 7,712,880 original ROI triangles and positions are retained in
indexed uint16 chunks; smooth normals are encoded as normalized signed bytes.
Companion meshes with at most 20,000 triangles per region are used during gestures
and for context behind neuron geometry;
coordinates rigidly rotated `(x,y,z) -> (x,-z,y)` and converted from nm to µm;
523 neurons selected deterministically for offline morphology; the overview
contains all 2,237,417 original edges from those neurons at rest. During gestures,
whole cells are temporarily skipped; no branches are synthesized. Individual offline skeleton
files retain the original source bytes. The catalog selects `status=Traced`.
All chemical edges whose two endpoints are in this catalog are preserved,
encoded as delta-varints, gzipped per cell and indexed in SQLite. Non-traced
fragments/glia are not included. Original sources and hashes are recorded in
[data/provenance/manifest.json](data/provenance/manifest.json).

This is an independent educational app. The attribution does not imply
endorsement by HHMI, Janelia, Cambridge, MRC, Google or individual researchers.
No warranty is supplied by the original data providers. No additional legal or
technical restriction is applied to the CC BY material. It is available without
an app purchase in this public repository and from the official source.

## AndroidX / Jetpack Compose / Material icons

Copyright The Android Open Source Project and its contributors.
Apache License 2.0. [Full text](docs/licenses/Apache-2.0.txt).

## Kotlin standard library / kotlinx.coroutines

Copyright JetBrains s.r.o. and Kotlin contributors.
Apache License 2.0. [Full text](docs/licenses/Apache-2.0.txt).

## Gradle wrapper

Copyright Gradle, Inc. and contributors.
Apache License 2.0. The wrapper was obtained from the local Android project
wrapper distribution for Gradle 8.14.3; it contains the standard Gradle wrapper.

## Data-build tools (not shipped as runtime code)

NumPy (BSD-3-Clause), PyArrow (Apache-2.0), trimesh (MIT), fast-simplification
(MIT), requests (Apache-2.0), pytest (MIT). These run on the developer machine;
the app uses a native OpenGL ES renderer, SQLite and Android framework I/O.
Neither the GPL-licensed `malecns` R package nor its code is included in the app.

## Literature

The app's explanations are original Japanese paraphrases, with references to
primary research. Article figures, photos, videos and full text are not bundled.
A citation does not make an article's copyrighted expression available under
the dataset license. Definitions, known results and unresolved interpretations
are distinguished in each region entry.
