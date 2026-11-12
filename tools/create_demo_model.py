"""Offline demo GLB builder for verification; Android uses its own BalloonModel.kt algorithm.
Requires: numpy, Pillow, scipy, trimesh. CLI: python tools/create_demo_model.py INPUT_IMAGE OUTPUT_DIR
"""
import io, pathlib, sys
import numpy as np
from PIL import Image
from scipy.ndimage import label, distance_transform_cdt
import trimesh

source=Image.open(sys.argv[1]).convert('RGBA')
out=pathlib.Path(sys.argv[2]);out.mkdir(parents=True,exist_ok=True)
source.thumbnail((640,640))
arr=np.array(source)
mask=(np.max(255-arr[:,:,:3],axis=2)>43)&(arr[:,:,3]>90)
labels,num=label(mask)
if not num:raise RuntimeError('No foreground detected')
counts=np.bincount(labels.ravel());counts[0]=0
mask=labels==np.argmax(counts)
yx=np.argwhere(mask)
ys,xs=yx[:,0],yx[:,1]
margin=int(max(xs.max()-xs.min(),ys.max()-ys.min())*0.035)+4
x0=max(0,xs.min()-margin);x1=min(mask.shape[1],xs.max()+margin+1)
y0=max(0,ys.min()-margin);y1=min(mask.shape[0],ys.max()+margin+1)
mask=mask[y0:y1,x0:x1]
arr=arr[y0:y1,x0:x1]
arr[~mask,3]=0
preview=Image.fromarray(arr,'RGBA')
preview.save(out/'demo-preview.png')
N=96
# same 96-grid topology as app. Use nearest color sampling of segmentation.
xarr=(np.arange(N+1)/N*(mask.shape[1]-1)).astype(int)
yarr=(np.arange(N+1)/N*(mask.shape[0]-1)).astype(int)
grid=mask[np.ix_(yarr,xarr)]
distance=distance_transform_cdt(grid.astype('uint8'),metric='taxicab').astype(float)
distance=distance/(max(distance.max(),1))
z=0.028+0.16*np.sqrt(distance)
H=1.1;W=H*mask.shape[1]/mask.shape[0]
xv=np.linspace(-W/2,W/2,N+1)
yv=np.linspace(H,0,N+1)
xx,yy=np.meshgrid(xv,yv)
front=np.stack([xx,yy,z],axis=-1).reshape(-1,3)
back=np.stack([xx,yy,-z],axis=-1).reshape(-1,3)
vertices=np.vstack([front,back]).astype(np.float32)
uv=np.stack(np.meshgrid(np.linspace(0,1,N+1),np.linspace(0,1,N+1)),axis=-1).reshape(-1,2)
uv=np.vstack([uv,uv]).astype(np.float32)
faces=[]; boundaries={}
def triangle(a,b,c):
    faces.append([a,b,c]);faces.append([c+len(front),b+len(front),a+len(front)])
    for u,v in [(a,b),(b,c),(c,a)]:
        key=tuple(sorted([u,v]));boundaries.setdefault(key,[]).append((u,v))
for row in range(N):
    for col in range(N):
        a=row*(N+1)+col;b=a+1;c=a+(N+1);d=c+1
        if grid[row,col] and grid[row+1,col] and grid[row,col+1]:triangle(a,c,b)
        if grid[row,col+1] and grid[row+1,col] and grid[row+1,col+1]:triangle(b,c,d)
for edges in boundaries.values():
    if len(edges)!=1:continue
    u,v=edges[0]
    faces.append([u,v+len(front),v]);faces.append([u,u+len(front),v+len(front)])
faces=np.array(faces,dtype=np.int64)
mat=trimesh.visual.material.PBRMaterial(baseColorTexture=preview,metallicFactor=0,roughnessFactor=0.9,doubleSided=True)
mesh=trimesh.Trimesh(vertices=vertices,faces=faces,process=False,visual=trimesh.visual.TextureVisuals(uv=uv,material=mat))
mesh.export(out/'demo.glb')
check=trimesh.load(out/'demo.glb')
print(f'demo generated: triangles={len(faces)}, geometry={len(check.geometry)}, file_bytes={(out/"demo.glb").stat().st_size}')
