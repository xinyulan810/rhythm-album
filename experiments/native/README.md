# Single-reference native AV1 probe

Research implementation only. It does not define a production archive format,
authenticate references, delete source media, or integrate with Android.

## Dependency and build

Dependency: official libaom source at commit
`9354161f7c9937a40a3815c84f545009ede57fe1`, held in ignored
`experiments/vendor/aom`. Retain upstream LICENSE and PATENTS if distributing it.

From the project root, with CMake, MSVC 2022, NASM and Perl available:

```powershell
# Resolve tool locations via PATH or supply explicit local tool paths.
# NASM_PATH is the directory containing nasm.exe, not the executable itself.
cmake -S experiments/vendor/aom -B experiments/native-build/aom -G "Visual Studio 17 2022" -A x64 -DENABLE_DOCS=0 -DENABLE_TESTS=0 -DENABLE_EXAMPLES=0 -DENABLE_TOOLS=0 -DCONFIG_REALTIME_ONLY=1 -DENABLE_NASM=1
cmake --build experiments/native-build/aom --config Release --target aom --parallel 4
cmake -S experiments/native -B experiments/native-build/probe -G "Visual Studio 17 2022" -A x64
cmake --build experiments/native-build/probe --config Release --parallel 2
python -m unittest discover -s experiments -p "test_*.py" -v
```

The current CMake link path targets this Windows research build, not Android.
Commands must run from the project root. Build parallelism is not codec runtime
parallelism: the historical desktop probe runs with exactly one thread.
The `rhythm_codec_probe_parallel` target exercises the bounded two-thread row-MT
path now used on Android. Both targets retain the same quantizer and speed.

```powershell
$env:RHYTHM_PROBE_EXE = Join-Path (Get-Location) 'experiments/native-build/probe/Release/rhythm_codec_probe_parallel.exe'
python -m unittest discover -s experiments -p test_native_probe.py -v
Remove-Item Env:RHYTHM_PROBE_EXE
```

These tests check reconstruction, including a full-height stripe; they do not
establish Android throughput, memory, battery use or visual acceptability.

## Fresh-process reconstruction

1. An independent anchor is encoded as an AV1 key frame.
2. A new process decodes that persisted anchor; its original JPG is unnecessary.
3. The encoder emits a small gray bootstrap key frame to allocate reference
   buffers. This is **not** a re-encoding of the anchor or any old screenshot.
4. `AV1_SET_REFERENCE` replaces reference slot zero with decoded anchor pixels.
5. Only LAST prediction is allowed; order hints are disabled, and error resilient
   mode with `PRIMARY_REF_NONE` avoids depending on the anchor's entropy state.
6. A delta packet and its bootstrap are persisted. No prior objects are rewritten.
7. Another process decodes the anchor, initializes from the bootstrap, installs
   the same pixels and decodes the target. Every output pixel must match the
   encoder's reconstruction.

The bootstrap is counted for each delta even when small. Headers, parameters and
the specific anchor identity would require validation/versioning in a real
format. Wrong-anchor substitution is not currently authenticated.

## Performance measures implemented

- One codec thread, no lookahead, real-time speed 8.
- Eight preview candidates maximum in the library probe, one full reference decode.
- Reference dependencies are one level; delta images never become anchors.
- Reference decoder is destroyed before target codec initialization.
- 8-bit decoder path requested explicitly; no unnecessary 16-bit pixel buffers.
- Planar input/output streamed row-by-row, eliminating entire raw byte copies.
- Only a single full-resolution reference image remains between codec stages.
- Per-child 60-second watchdog in Python; native failure aborts the experiment.
- Immutable-file hashes and fresh-process reconstruction check every insertion.
- No old-image recoding when a new independent anchor is added.

The research library encodes both independent and delta candidates and computes
full numerical quality metrics. This is intentionally accounted for, but it is
too expensive to copy literally into the final mobile background task.
RSS sampling is per native process at 10 ms intervals; brief peaks may be missed.
Python preparation/quality computations are outside native RSS measurements.

## Reproduce benchmarks

Use new output directories; all raw inputs come from explicit manifests.

```powershell
python experiments/native_benchmark.py --manifest experiments/output/labelled-001/selection.json --output experiments/output/native-next
python experiments/native_library.py --manifest experiments/output/labelled-001/selection.json --output experiments/output/native-library-next --q 24
python experiments/native_stripes.py --manifest experiments/output/labelled-001/selection.json --output experiments/output/native-stripes-next
```

`native_benchmark.py` tests four labelled images/two selected pairs.
`native_library.py` tests all sixteen confirmed settlement images chronologically.
The latter applies the same numerical gate as the tile prototype: at most
0.25 dB PSNR loss and edge MAE within baseline×1.05+0.1, relative to independent
AV1 at the same quantizer. This is not a visual-legibility guarantee.

`native_stripes.py` screens two pairs using sequential 512-pixel-high stripes,
without resizing. Native child peaks were approximately 55 MiB encode / 34 MiB
decode. Python still holds full images, so these are not total pipeline or app
memory figures. Stripe payload totals exclude thumbnails and production indexes.
See `../RESULTS-003.md` for results and measurement boundaries.

The persistent footprint is the union of files referenced by `library.json`,
plus that manifest. Raw GBR fixtures, encoder reconstruction oracle outputs,
discarded candidates and reports are research artifacts and are excluded.
Those artifacts remain on disk for diagnosis; the output directory's total
size is therefore much larger than the prospective library.

## Known issues / remaining work

- First trial configurations crashed during the second-frame encode. Changing
  keyframe-distance configuration and using default rather than screen-content
  tuning produced the validated path; the precise root cause is not isolated.
  Never enable the failed combination in an app.
- No broad corpus/fuzz testing or visual review yet.
- No transactional multi-file publication, recovery, reference authentication,
  garbage collection or background scheduler.
- No Android/ARM measurements, battery, temperature or memory-pressure tests.
- Full-resolution native encoding still approaches 184 MiB RSS on this desktop
  corpus. A low-end phone cannot be assumed to have that budget.
- Expand original-resolution stripes beyond the two tested pairs; validate seams,
  full archive overhead, streaming source decoding and bounded display caches.
  Do not silently downsize the user's images to meet a memory target.
