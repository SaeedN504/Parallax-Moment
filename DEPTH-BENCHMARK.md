# Offline depth benchmark

Parallax Moment uses depth only to create relative foreground/background motion. It does not need metric distance, so MiDaS Small v2.1 is the first offline candidate.

## Model contract

- Model: MiDaS Small v2.1, EfficientNet-Lite3 small decoder
- Runtime: TensorFlow Lite / LiteRT-compatible `.tflite`
- Input: 256 x 256 RGB float32, ImageNet normalized
- Output: 256 x 256 relative inverse-depth map
- Runtime policy: model and inference are fully offline

Add the model as `android/app/src/main/assets/midas_small_256_fp16.tflite` after verifying its license and SHA-256. The app never fetches model files at runtime.

## Build and run the compatibility test

```bash
cd android
./gradlew assembleDebug connectedDebugAndroidTest
```

The test checks that the model asset loads, inference returns a 256 x 256 finite normalized map, and the benchmark executes without a network dependency.

## Benchmark harness

`OfflineDepthBenchmark.run()` performs warmups, then records inference times for a bundled sample image. It reports median, p95, minimum, maximum, and approximate heap delta. Use the same APK and model on every phone.

Record results by device:

| Device | Android | CPU/GPU path | Model | Median ms | P95 ms | Notes |
|---|---:|---|---|---:|---:|---|
|  |  | CPU | MiDaS Small 256 |  |  |  |
|  |  | GPU delegate | MiDaS Small 256 |  |  |  |

## Compatibility tiers

- **Tier A:** inference succeeds, output is finite, median under 250 ms.
- **Tier B:** inference succeeds, median 250 to 1000 ms.
- **Tier C:** inference succeeds but exceeds 1000 ms, or needs reduced resolution.
- **Unsupported:** model load or inference fails. Fall back to the existing subject cutout and safe 2-layer parallax.

## Photo policy

Parallax Moment processes user-selected photos locally and stores generated results locally. It does not upload, classify, block, or censor photo content. The UI should still explain that depth estimation can struggle with transparent hair, overlapping objects, reflective surfaces, and very low-light images. Users can refine the subject mask or choose safe motion when that happens.

## Comparison plan

Benchmark MiDaS Small first for broad device coverage. Add Depth Anything V2 Small later as an opt-in high-quality mode after measuring package size, memory, and latency on representative low-end, mid-range, and flagship devices. Do not mix depth scales between models without per-image normalization.
