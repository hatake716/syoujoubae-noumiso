"""Indexed GLES2 meshes: original vertices/faces, signed-byte smooth normals.

Each chunk uses uint16 indices, so neither GLES3 nor expanded triangle vertices
are needed. The 20k-triangle companion is used only during interaction / low mode.
"""
import struct
import numpy as np
import trimesh

MAGIC = b'MCN2'

def write_mesh(path, vertices, faces):
    normals = np.zeros_like(vertices)
    triangles = vertices[faces]
    face_normals = np.cross(triangles[:, 1]-triangles[:, 0], triangles[:, 2]-triangles[:, 0])
    for corner in range(3):
        np.add.at(normals, faces[:, corner], face_normals)
    normals /= np.maximum(np.linalg.norm(normals, axis=1, keepdims=True), 1e-12)
    normals = np.rint(normals*127).astype('i1')
    # At most 60,000 distinct vertices, even if no triangle shares a vertex.
    chunks = []
    for start in range(0, len(faces), 20000):
        ids, indices = np.unique(faces[start:start+20000], return_inverse=True)
        indices = indices.reshape(-1)
        packed = np.zeros(len(ids), dtype=[('position','<f4',3),('normal','i1',4)])
        packed['position'] = vertices[ids]
        packed['normal'][:, :3] = normals[ids]
        chunks.append(struct.pack('<II',len(ids),len(indices))+packed.tobytes()+indices.astype('<u2').tobytes())
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(MAGIC+struct.pack('<I',len(chunks))+b''.join(chunks))
    return len(chunks)

def convert_mesh(raw, full_path, interactive_path):
    count = struct.unpack_from('<I',raw)[0]
    source = np.frombuffer(raw,'<f4',count=count*3,offset=4).reshape(-1,3).astype('float64')/1000
    faces = np.frombuffer(raw,'<u4',offset=4+count*12).reshape(-1,3)
    vertices = source[:,[0,2,1]].copy()
    vertices[:,1] *= -1
    chunks = write_mesh(full_path,vertices,faces)
    if len(faces)>20000:
        reduced = trimesh.Trimesh(vertices=vertices,faces=faces,process=False).simplify_quadric_decimation(face_count=20000)
        small_vertices, small_faces = reduced.vertices,reduced.faces
    else:
        small_vertices, small_faces = vertices,faces
    write_mesh(interactive_path,small_vertices,small_faces)
    return dict(vertices=len(vertices),triangles=len(faces),originalTriangles=len(faces),
                interactiveTriangles=len(small_faces),chunks=chunks,
                center=vertices.mean(axis=0).tolist(),min=vertices.min(axis=0).tolist(),max=vertices.max(axis=0).tolist())
