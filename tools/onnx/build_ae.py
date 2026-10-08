import numpy as np, struct, sys
rng = np.random.default_rng(1234)
C, T = 12, 16
D = C * T

# ------------------------------------------------------------------ synthetic "boring multichannel sensor" windows
def gen_windows(n, rng, active_drop=0.2):
    out = np.zeros((n, T, C), np.float32)
    for i in range(n):
        K = 3
        phi = rng.uniform(0.3, 0.97, K)
        f = np.zeros((T + 20, K))
        e = rng.standard_normal((T + 20, K)) * np.sqrt(1 - phi ** 2)
        for t in range(1, T + 20):
            f[t] = phi * f[t - 1] + e[t]
        f = f[20:]
        M = rng.standard_normal((C, K)); M /= np.linalg.norm(M, axis=1, keepdims=True)
        rho = rng.uniform(0.0, 0.8, C)
        phi_c = rng.uniform(0.0, 0.9, C)
        idio = np.zeros((T + 20, C))
        ei = rng.standard_normal((T + 20, C)) * np.sqrt(1 - phi_c ** 2)
        for t in range(1, T + 20):
            idio[t] = phi_c * idio[t - 1] + ei[t]
        idio = idio[20:]
        x = np.sqrt(rho) * (f @ M.T) + np.sqrt(1 - rho) * idio
        mask = rng.random(C) > active_drop
        x = x * mask
        out[i] = x
    return out

def inject(w, kind, rng):
    w = w.copy()
    n = w.shape[0]
    for i in range(n):
        c = rng.integers(0, C)
        if kind == "spike":
            w[i, rng.integers(0, T), c] += 6.0
        elif kind == "step":
            w[i, T // 2:, c] += 3.0
        elif kind == "burst":
            cs = rng.choice(C, 2, replace=False)
            t = np.arange(T)
            for cc in cs:
                w[i, :, cc] += 2.0 * np.sin(2 * np.pi * 0.25 * t + rng.uniform(0, 6.28))
        elif kind == "jitter":
            w[i, :, c] += rng.standard_normal(T) * 2.5
    return w

# ------------------------------------------------------------------ train denoising autoencoder 192-48-10-48-192
def init(shape):
    return (rng.standard_normal(shape) * np.sqrt(2.0 / shape[0])).astype(np.float32)

H, Z = 48, 10
W1, b1 = init((D, H)), np.zeros(H, np.float32)
W2, b2 = init((H, Z)), np.zeros(Z, np.float32)
W3, b3 = init((Z, H)), np.zeros(H, np.float32)
W4, b4 = init((H, D)), np.zeros(D, np.float32)
params = [W1, b1, W2, b2, W3, b3, W4, b4]
m = [np.zeros_like(p) for p in params]; v = [np.zeros_like(p) for p in params]

def forward(x, keep=False):
    a1 = x @ W1 + b1; h1 = np.tanh(a1)
    z = h1 @ W2 + b2
    a3 = z @ W3 + b3; h3 = np.tanh(a3)
    y = h3 @ W4 + b4
    return (y, (x, h1, z, h3)) if keep else y

Xtr = gen_windows(24000, rng).reshape(-1, D)
Xva = gen_windows(3000, rng)
step = 0
lr = 2e-3
for ep in range(40):
    idx = rng.permutation(len(Xtr))
    tot = 0.0
    for s in range(0, len(Xtr), 256):
        xb = Xtr[idx[s:s + 256]]
        xin = xb + rng.standard_normal(xb.shape).astype(np.float32) * 0.05
        y, (x0, h1, z, h3) = forward(xin, True)
        err = y - xb
        tot += float((err ** 2).mean()) * len(xb)
        g = 2 * err / err.size * 1.0
        gW4 = h3.T @ g; gb4 = g.sum(0)
        gh3 = g @ W4.T; ga3 = gh3 * (1 - h3 ** 2)
        gW3 = z.T @ ga3; gb3 = ga3.sum(0)
        gz = ga3 @ W3.T
        gW2 = h1.T @ gz; gb2 = gz.sum(0)
        gh1 = gz @ W2.T; ga1 = gh1 * (1 - h1 ** 2)
        gW1 = x0.T @ ga1; gb1 = ga1.sum(0)
        grads = [gW1, gb1, gW2, gb2, gW3, gb3, gW4, gb4]
        step += 1
        for k, (p, gr) in enumerate(zip(params, grads)):
            m[k] = 0.9 * m[k] + 0.1 * gr
            v[k] = 0.999 * v[k] + 0.001 * gr * gr
            mh = m[k] / (1 - 0.9 ** step); vh = v[k] / (1 - 0.999 ** step)
            p -= (lr * mh / (np.sqrt(vh) + 1e-8)).astype(np.float32)
    if ep % 10 == 9 or ep == 0:
        val = float(((forward(Xva.reshape(-1, D)) - Xva.reshape(-1, D)) ** 2).mean())
        print(f"epoch {ep+1:2d} train mse {tot/len(Xtr):.4f}  val mse {val:.4f}")

# ------------------------------------------------------------------ evaluation vs a plain Mahalanobis baseline
def ae_score(w):
    x = w.reshape(len(w), -1)
    return ((forward(x) - x) ** 2).mean(1)

def auc(neg, pos):
    allv = np.concatenate([neg, pos]); ranks = allv.argsort().argsort() + 1
    return (ranks[len(neg):].sum() - len(pos) * (len(pos) + 1) / 2) / (len(neg) * len(pos))

# Mahalanobis baseline calibrated per "room" on normal data, evaluated on windows from that same generator
def maha_scores(w, mu, cov_inv):
    flat = w.reshape(-1, C) - mu
    d2 = np.einsum('ij,jk,ik->i', flat, cov_inv, flat).reshape(len(w), T)
    return d2.max(1)

normal = gen_windows(3000, rng)
base = gen_windows(1500, rng).reshape(-1, C)
mu = base.mean(0); cov = np.cov(base.T) + 0.05 * np.eye(C); cov_inv = np.linalg.inv(cov)
print("\nAUC (normal vs anomalous windows), higher is better")
print(f"{'anomaly':<10} {'AE':>6} {'Mahalanobis(per-step max)':>28}")
for kind in ["spike", "step", "burst", "jitter"]:
    an = inject(gen_windows(3000, rng), kind, rng)
    a = auc(ae_score(normal), ae_score(an))
    b = auc(maha_scores(normal, mu, cov_inv), maha_scores(an, mu, cov_inv))
    print(f"{kind:<10} {a:6.3f} {b:28.3f}")
np.savez("ae_weights.npz", W1=W1, b1=b1, W2=W2, b2=b2, W3=W3, b3=b3, W4=W4, b4=b4)
