import numpy as np, struct

def varint(n):
    out = bytearray()
    n &= (1 << 64) - 1
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)

def key(field, wt): return varint((field << 3) | wt)
def f_varint(field, v): return key(field, 0) + varint(v)
def f_bytes(field, b): return key(field, 2) + varint(len(b)) + b
def f_str(field, s): return f_bytes(field, s.encode())

def tensor(name, arr):
    arr = np.ascontiguousarray(arr, dtype=np.float32)
    b = b""
    for d in arr.shape:
        b += f_varint(1, d)          # dims
    b += f_varint(2, 1)              # data_type FLOAT
    b += f_str(8, name)
    b += f_bytes(9, arr.tobytes())   # raw_data
    return b

def node(op, inputs, outputs, name):
    b = b""
    for i in inputs: b += f_str(1, i)
    for o in outputs: b += f_str(2, o)
    b += f_str(3, name)
    b += f_str(4, op)
    return b

def value_info(name, dims):
    shape = b""
    for d in dims:
        if isinstance(d, str):
            shape += f_bytes(1, f_str(2, d))        # dim_param
        else:
            shape += f_bytes(1, f_varint(1, d))     # dim_value
    tt = f_varint(1, 1) + f_bytes(2, shape)         # elem_type FLOAT, shape
    tp = f_bytes(1, tt)                             # tensor_type
    return f_str(1, name) + f_bytes(2, tp)

d = np.load("ae_weights.npz")
nodes = [
    node("Gemm", ["x", "W1", "b1"], ["a1"], "fc1"),
    node("Tanh", ["a1"], ["h1"], "t1"),
    node("Gemm", ["h1", "W2", "b2"], ["z"], "fc2"),
    node("Gemm", ["z", "W3", "b3"], ["a3"], "fc3"),
    node("Tanh", ["a3"], ["h3"], "t3"),
    node("Gemm", ["h3", "W4", "b4"], ["recon"], "fc4"),
]
graph = b""
for n in nodes: graph += f_bytes(1, n)
graph += f_str(2, "spiritscan_novelty_ae")
for k in ["W1", "b1", "W2", "b2", "W3", "b3", "W4", "b4"]:
    graph += f_bytes(5, tensor(k, d[k]))
graph += f_bytes(11, value_info("x", ["N", 192]))
graph += f_bytes(12, value_info("recon", ["N", 192]))

model = f_varint(1, 8)                                   # ir_version
model += f_str(2, "spiritscan")                          # producer_name
model += f_bytes(7, graph)                               # graph
model += f_bytes(8, f_str(1, "") + f_varint(2, 13))      # opset_import {domain "", version 13}
open("novelty_ae.onnx", "wb").write(model)
print("wrote", len(model), "bytes")
