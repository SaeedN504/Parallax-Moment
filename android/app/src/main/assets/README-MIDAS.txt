MiDaS model integration

The tracked midas_small_256_fp16.tflite file was a broken 133-byte Git LFS pointer and is not used by the Android build.

Before preBuild, Gradle downloads the official MiDaS v2.1 model from:
https://github.com/isl-org/MiDaS/releases/download/v2_1/model_opt.tflite

It stores the model in android/app/build/generated/midasAssets/ as midas_small_256_fp16.tflite, rejects short/error responses, verifies the TFLite TFL3 header, and prints the SHA-256 checksum. The app never downloads the model at runtime.

Override the build source when needed with:
./gradlew assembleDebug -PmidasModelUrl=https://example.com/model.tflite
or the MIDAS_MODEL_URL environment variable.

Expected contract:
- Input: [1, 256, 256, 3], float32 NHWC RGB normalized to [-1, 1]
- Output: any float32 shape containing exactly 256 x 256 relative inverse-depth values
