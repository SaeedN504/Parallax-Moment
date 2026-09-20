# Offline depth benchmark

Parallax Moment uses depth only to create relative foreground/background motion. It does not need metric distance, so the official MiDaS Small v2.1 mobile TFLite release is the first offline model.

## Model contract

- Model: official MiDaS v2.1 `model_opt.tflite`
- Runtime: TensorFlow Lite 2.16.1
- Input: 256 x 256 RGB float32 NHWC, normalized to `[-1, 1]`
- Output: 65,536 float32 relative inverse-depth values (accepted as `[1,256,256]` or `[1,256,256,1]`)
- Runtime policy: the model is packaged in the APK and inference is fully offline

The old tracked `.tflite` was only a 133-byte Git LFS pointer. It is deliberately excluded from Android assets. `prepareMidasModel` downloads the official GitHub release into a generated assets directory during the build, rejects undersized responses, validates the TFLite header, and logs its SHA-256. The app performs no runtime download.

Override the build-time source with `-PmidasModelUrl=<direct-url>` or `MIDAS_MODEL_URL=<direct-url>`.

## Build and run the compatibility test

```bash
cd android
./gradlew clean assembleDebug connectedDebugAndroidTest
```

The test checks that the generated model asset loads, inference returns a 256 x 256 finite normalized map, and the benchmark executes without a runtime network dependency.

## Benchmark harness

`OfflineDepthBenchmark.run()` performs warmups, then records inference times for a bundled or deterministic sample image. It reports median, p95, minimum, maximum, and approximate heap delta. Use the same APK and model on every phone.

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

Parallax Moment processes user-selected photos locally and stores generated results locally. It does not upload photos. Depth estimation can struggle with transparent hair, overlapping objects, reflective surfaces, and very low-light images.
