# Rebuilding the novelty autoencoder
`build_ae.py` trains the 192-48-10-48-192 denoising autoencoder on synthetic multichannel windows (numpy only) and writes `ae_weights.npz`.
`write_onnx.py` encodes it as `novelty_ae.onnx` (hand-written protobuf, opset 13, IR 8) with no `onnx` package. `eval2.py` reproduces the AUC table
in docs/passive-onnx.md. Copy the result to app/src/main/assets/models/. The layout of the 192 inputs is [time 0..15] x [12 channels].
