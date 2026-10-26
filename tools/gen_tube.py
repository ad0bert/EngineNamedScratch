"""Generates a small skinned glTF test/demo asset: a striped tube with a four-bone chain and three animations.
Usage (from the repository root):
  python3 tools/gen_tube.py demo/src/main/resources/models/tube.glb engine/src/test/resources/gltf/tube.gltf
  cp demo/src/main/resources/models/tube.glb engine/src/test/resources/models/tube.glb
The output is public domain (CC0), like this script.
"""
import base64, json, math, struct, sys, zlib

SEG, ROWS, H, R = 16, 16, 2.0, 0.25
BONES = 4
STEP = H / BONES


def png(w, h, rows):
    raw = b''.join(b'\x00' + bytes(r) for r in rows)
    def chunk(t, d):
        c = t + d
        return struct.pack('>I', len(d)) + c + struct.pack('>I', zlib.crc32(c) & 0xffffffff)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def weights(y):
    f = max(0.0, min(BONES - 1.0, y / STEP))
    j = min(int(f), BONES - 2)
    t = f - j
    return (j, j + 1, 0, 0), (1 - t, t, 0, 0)


pos, nor, uv, jnt, wgt, idx = [], [], [], [], [], []
for r in range(ROWS + 1):
    y = H * r / ROWS
    for s in range(SEG + 1):
        a = 2 * math.pi * s / SEG
        pos += [R * math.cos(a), y, -R * math.sin(a)]
        nor += [math.cos(a), 0, -math.sin(a)]
        uv += [s / SEG, 1 - y / H]
        j, w = weights(y)
        jnt += j
        wgt += w
for r in range(ROWS):
    for s in range(SEG):
        a = r * (SEG + 1) + s
        b = a + SEG + 1
        idx += [a, a + 1, b, a + 1, b + 1, b]
# a cap on top
top = len(pos) // 3
pos += [0, H, 0]; nor += [0, 1, 0]; uv += [0.5, 0]
j, w = weights(H); jnt += j; wgt += w
ring = top + 1
for s in range(SEG + 1):
    a = 2 * math.pi * s / SEG
    pos += [R * math.cos(a), H, -R * math.sin(a)]; nor += [0, 1, 0]; uv += [s / SEG, 0]
    jnt += j; wgt += w
for s in range(SEG):
    idx += [top, ring + s, ring + s + 1]

n = len(pos) // 3
buf = bytearray()
views, accessors = [], []


def add(data, fmt, target=None, comp=5126, typ='VEC3', count=None, mn=None, mx=None, normalized=False):
    while len(buf) % 4:
        buf.append(0)
    off = len(buf)
    raw = struct.pack('<%d%s' % (len(data), fmt), *data)
    buf.extend(raw)
    v = {'buffer': 0, 'byteOffset': off, 'byteLength': len(raw)}
    if target:
        v['target'] = target
    views.append(v)
    a = {'bufferView': len(views) - 1, 'componentType': comp, 'count': count, 'type': typ}
    if mn is not None:
        a['min'], a['max'] = mn, mx
    if normalized:
        a['normalized'] = True
    accessors.append(a)
    return len(accessors) - 1


xs, ys, zs = pos[0::3], pos[1::3], pos[2::3]
A_POS = add(pos, 'f', 34962, count=n, mn=[min(xs), min(ys), min(zs)], mx=[max(xs), max(ys), max(zs)])
A_NOR = add(nor, 'f', 34962, count=n)
A_UV = add(uv, 'f', 34962, typ='VEC2', count=n)
A_J = add(jnt, 'B', 34962, comp=5121, typ='VEC4', count=n)
A_W = add(wgt, 'f', 34962, typ='VEC4', count=n)
A_IDX = add(idx, 'H', 34963, comp=5123, typ='SCALAR', count=len(idx))
ibm = []
for i in range(BONES):
    ibm += [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, -STEP * i, 0, 1]
A_IBM = add(ibm, 'f', typ='MAT4', count=BONES)


def quat(axis, deg):
    h = math.radians(deg) / 2
    s = math.sin(h)
    return [axis[0] * s, axis[1] * s, axis[2] * s, math.cos(h)]


animations = []


def anim(name, chans):
    samplers, channels = [], []
    for node, path, times, values, interp, width in chans:
        ti = add(times, 'f', typ='SCALAR', count=len(times), mn=[min(times)], mx=[max(times)])
        flat = [c for v in values for c in v]
        vi = add(flat, 'f', typ={3: 'VEC3', 4: 'VEC4'}[width], count=len(values))
        samplers.append({'input': ti, 'output': vi, 'interpolation': interp})
        channels.append({'sampler': len(samplers) - 1, 'target': {'node': node, 'path': path}})
    animations.append({'name': name, 'samplers': samplers, 'channels': channels})


T = [0.0, 1.0, 2.0, 3.0, 4.0]
anim('bend', [(3 + i, 'rotation', T, [quat((0, 0, 1), d) for d in (0, 28, 0, -28, 0)], 'LINEAR', 4)
              for i in range(BONES - 1)])
anim('twist', [(3 + i, 'rotation', [0.0, 1.0, 2.0], [quat((0, 1, 0), d) for d in (0, 50, 0)], 'LINEAR', 4)
               for i in range(BONES - 1)]
     + [(2, 'translation', [0.0, 1.0], [[0, 0, 0], [0, 0.15, 0]], 'STEP', 3)])
# cubic spline: in-tangent, value, out-tangent per key (zero tangents: eases in and out)
anim('pulse', [(5, 'scale', [0.0, 0.5, 1.0],
                [[0, 0, 0], [1, 1, 1], [0, 0, 0], [0, 0, 0], [1.6, 1.6, 1.6], [0, 0, 0], [0, 0, 0], [1, 1, 1], [0, 0, 0]],
                'CUBICSPLINE', 3)])

stripes = []
for y in range(8):
    c = (235, 140, 40) if y % 2 == 0 else (40, 170, 170)
    stripes.append([v for x in range(8) for v in c])
image = png(8, 8, stripes)

doc = {
    'asset': {'version': '2.0', 'generator': 'EngineNamedScratch gen_tube.py (public domain)'},
    'scene': 0,
    'scenes': [{'name': 'tube', 'nodes': [0]}],
    'nodes': [
        {'name': 'tube-root', 'children': [1, 2]},
        {'name': 'tube-mesh', 'mesh': 0, 'skin': 0},
        {'name': 'bone0', 'children': [3]},
        {'name': 'bone1', 'translation': [0, STEP, 0], 'children': [4]},
        {'name': 'bone2', 'translation': [0, STEP, 0], 'children': [5]},
        {'name': 'bone3', 'translation': [0, STEP, 0]},
    ],
    'meshes': [{'name': 'tube', 'primitives': [{
        'attributes': {'POSITION': A_POS, 'NORMAL': A_NOR, 'TEXCOORD_0': A_UV, 'JOINTS_0': A_J, 'WEIGHTS_0': A_W},
        'indices': A_IDX, 'material': 0}]}],
    'skins': [{'joints': [2, 3, 4, 5], 'inverseBindMatrices': A_IBM, 'skeleton': 2}],
    'materials': [{'name': 'stripes', 'pbrMetallicRoughness': {
        'baseColorTexture': {'index': 0}, 'metallicFactor': 0.0, 'roughnessFactor': 0.45}}],
    'textures': [{'source': 0, 'sampler': 0}],
    'samplers': [{'magFilter': 9729, 'minFilter': 9987, 'wrapS': 10497, 'wrapT': 10497}],
    'animations': animations,
}


def write_gltf(path):
    d = json.loads(json.dumps(doc))
    d['buffers'] = [{'byteLength': len(buf), 'uri': 'data:application/octet-stream;base64,'
                     + base64.b64encode(bytes(buf)).decode()}]
    d['images'] = [{'uri': 'data:image/png;base64,' + base64.b64encode(image).decode()}]
    d['bufferViews'] = views
    d['accessors'] = accessors
    open(path, 'w').write(json.dumps(d, indent=1))


def write_glb(path):
    d = json.loads(json.dumps(doc))
    b = bytearray(buf)
    while len(b) % 4:
        b.append(0)
    img_off = len(b)
    b.extend(image)
    v = views + [{'buffer': 0, 'byteOffset': img_off, 'byteLength': len(image)}]
    while len(b) % 4:
        b.append(0)
    d['buffers'] = [{'byteLength': len(b)}]
    d['images'] = [{'bufferView': len(v) - 1, 'mimeType': 'image/png'}]
    d['bufferViews'] = v
    d['accessors'] = accessors
    j = json.dumps(d, separators=(',', ':')).encode()
    while len(j) % 4:
        j += b' '
    total = 12 + 8 + len(j) + 8 + len(b)
    out = struct.pack('<III', 0x46546C67, 2, total) + struct.pack('<II', len(j), 0x4E4F534A) + j \
        + struct.pack('<II', len(b), 0x004E4942) + bytes(b)
    open(path, 'wb').write(out)


write_glb(sys.argv[1])
write_gltf(sys.argv[2])
print(n, 'vertices', len(idx) // 3, 'triangles', len(buf), 'buffer bytes')
