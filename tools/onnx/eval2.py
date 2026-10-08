import numpy as np
exec(open('build_ae.py').read().split("# ------------------------------------------------------------------ train")[0])
d = np.load("ae_weights.npz"); W1,b1,W2,b2,W3,b3,W4,b4 = [d[k] for k in ["W1","b1","W2","b2","W3","b3","W4","b4"]]
def forward(x):
    return np.tanh(np.tanh(x@W1+b1)@W2+b2)@W3 + 0 if False else (np.tanh((x@W1+b1))@W2+b2)
def fwd(x):
    h1=np.tanh(x@W1+b1); z=h1@W2+b2; h3=np.tanh(z@W3+b3); return h3@W4+b4
def auc(neg,pos):
    a=np.concatenate([neg,pos]); r=a.argsort().argsort()+1
    return (r[len(neg):].sum()-len(pos)*(len(pos)+1)/2)/(len(neg)*len(pos))
def sc_mean(w):
    x=w.reshape(len(w),-1); return ((fwd(x)-x)**2).mean(1)
def sc_topk(w,k=8):
    x=w.reshape(len(w),-1); e=(fwd(x)-x)**2; return np.sort(e,1)[:,-k:].mean(1)
def maha(w,mu,ci):
    f=w.reshape(-1,C)-mu; d2=np.einsum('ij,jk,ik->i',f,ci,f).reshape(len(w),T); return d2.max(1)
rng=np.random.default_rng(9)
normal=gen_windows(3000,rng); base=gen_windows(1500,rng).reshape(-1,C)
mu=base.mean(0); ci=np.linalg.inv(np.cov(base.T)+0.05*np.eye(C))
def z(s,ref):  # robust z against normal reference
    med=np.median(ref); mad=np.median(np.abs(ref-med))*1.4826+1e-9; return (s-med)/mad
print(f"{'anomaly':<8}{'AEmean':>8}{'AEtop8':>8}{'Maha':>8}{'max(z_AEmean,z_Maha)':>24}")
for kind in ["spike","step","burst","jitter"]:
    an=inject(gen_windows(3000,rng),kind,rng)
    nm,am=sc_mean(normal),sc_mean(an); nt,at=sc_topk(normal),sc_topk(an); nh,ah=maha(normal,mu,ci),maha(an,mu,ci)
    nc=np.maximum(z(nm,nm),z(nh,nh)); ac=np.maximum(z(am,nm),z(ah,nh))
    print(f"{kind:<8}{auc(nm,am):8.3f}{auc(nt,at):8.3f}{auc(nh,ah):8.3f}{auc(nc,ac):24.3f}")
