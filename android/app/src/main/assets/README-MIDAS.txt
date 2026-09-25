MiDaS model integration

The verified midas_small_256_fp16.tflite file is bundled under android/app/src/main/assets/ and is required for the Android build. The app never downloads the model at runtime.

The build verifies the exact file size, TFLite TFL3 header, and SHA-256 before compilation:
- Size: 66,338,288 bytes
- SHA-256: 93d871071edff1218973ce25ee27ce95ccd20450c70a55e1b89efa3f5a772cdd

Expected contract:
- Input: [1, 256, 256, 3], float32 NHWC RGB normalized to [-1, 1]
- Output: any float32 shape containing exactly 256 x 256 relative inverse-depth values

Source metadata: https://huggingface.co/litert-community/MiDaS-small
