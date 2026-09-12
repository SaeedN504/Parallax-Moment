Place the offline MiDaS Small v2.1 LiteRT/TFLite model here as:

midas_small_256_fp16.tflite

Expected contract:
- Input: [1, 256, 256, 3], float32 NHWC RGB, ImageNet normalized
- Output: [1, 256, 256], float32 relative inverse depth

The binary model is intentionally not committed in this text-only change. Add the model after checking its upstream license and checksum, then run the instrumentation compatibility test.

The app must not download a model at runtime. If the model is absent, show a clear local setup/build error instead of making a network request.
