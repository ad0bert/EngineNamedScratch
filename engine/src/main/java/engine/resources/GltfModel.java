package engine.resources;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import engine.animation.AnimationClip;
import engine.animation.Animator;
import engine.ecs.Entity;
import engine.ecs.Scene;
import engine.ecs.Transform;
import engine.render.GpuMesh;
import engine.render.GpuTexture;
import engine.render.Material;
import engine.render.MeshData;
import engine.render.MeshRenderer;
import engine.render.Skin;
import lombok.Getter;

/**
 * A loaded glTF asset with its GPU resources (meshes, textures, engine {@link Material}s), created by
 * {@link ResourceManager#gltf}. {@link #instantiate} builds an entity per node with the node's transform, renderers
 * for its mesh (skinned ones with a {@link Skin} over the joint entities) and, if the asset has animations, an
 * {@link Animator} on the root holding them as named clips. {@link #model()} offers the static meshes as a flat
 * {@link Model} for code that does not need the hierarchy. Instances share meshes and materials.
 */
public final class GltfModel {
  @Getter private final GltfLoader.Asset asset;
  private final String path;
  private final List<List<GpuMesh>> meshes = new ArrayList<>();
  @Getter private final List<Material> materials = new ArrayList<>();
  private final Material fallback = new Material();
  private final ResourceManager resources;
  private Model model;

  GltfModel(GltfLoader.Asset asset, String path, ResourceManager resources) {
    this.asset = asset;
    this.path = path;
    this.resources = resources;
    for (int m = 0; m < asset.meshes().size(); m++) {
      final List<GpuMesh> prims = new ArrayList<>();
      final var primitives = asset.meshes().get(m).primitives();
      for (int p = 0; p < primitives.size(); p++) {
        prims.add(resources.mesh(path + "#mesh" + m + "." + p, primitives.get(p).mesh()));
      }
      this.meshes.add(prims);
    }
    for (int i = 0; i < asset.materials().size(); i++) {
      this.materials.add(material(asset.materials().get(i)));
    }
    this.fallback.pbr(1f, 1f); // glTF's default material: white, metallic 1, roughness 1
  }

  private Material material(GltfLoader.Material g) {
    final var m = new Material();
    m.color(g.baseColor()[0], g.baseColor()[1], g.baseColor()[2], g.baseColor()[3]);
    m.transparent = "BLEND".equals(g.alphaMode());
    if ("MASK".equals(g.alphaMode())) {
      m.alphaCutoff = g.alphaCutoff();
    }
    m.pbr(g.metallic(), g.roughness());
    m.unlit = g.unlit();
    m.texture = texture(g.baseColorTexture(), true);
    m.metalRoughMap = texture(g.metallicRoughnessTexture(), false);
    m.normalMap = texture(g.normalTexture(), false);
    m.normalScale = g.normalScale();
    m.emissive.set(g.emissive()[0], g.emissive()[1], g.emissive()[2]);
    m.emissiveMap = texture(g.emissiveTexture(), true);
    if (m.emissiveMap != null && m.emissive.lengthSquared() == 0f) {
      m.emissive.set(1f);
    }
    m.clampUv = g.baseColorTexture() != null && g.baseColorTexture().clamp();
    return m;
  }

  private GpuTexture texture(GltfLoader.TextureRef ref, boolean srgb) {
    if (ref == null) {
      return null;
    }
    final var image = this.asset.images().get(ref.image());
    final var key = this.path + "#image" + ref.image();
    return srgb ? this.resources.texture(key, image) : this.resources.dataTexture(key + "|linear", image);
  }

  /** The animations, as clips whose channels name the nodes (entity names after {@link #instantiate}). */
  public List<AnimationClip> animations() {
    return this.asset.animations();
  }

  /** The GPU meshes of one glTF mesh, one per primitive. */
  public List<GpuMesh> meshes(int mesh) {
    return this.meshes.get(mesh);
  }

  /**
   * Creates the asset's entities in the scene and returns the root: the scene's only root node, or a new entity
   * named after the file holding several.
   */
  public Entity instantiate(Scene scene) {
    final var nodes = this.asset.nodes();
    final var entities = new Entity[nodes.size()];
    for (int i = 0; i < nodes.size(); i++) {
      final var n = nodes.get(i);
      final var e = new Entity(n.name());
      e.transform().position().set(n.translation());
      e.transform().rotation().set(n.rotation());
      e.transform().scale().set(n.scale());
      entities[i] = e;
    }
    for (int i = 0; i < nodes.size(); i++) {
      if (nodes.get(i).parent() >= 0) {
        entities[i].transform().setParent(entities[nodes.get(i).parent()].transform());
      }
    }
    final var roots = this.asset.roots();
    final Entity root;
    if (roots.length == 1) {
      root = entities[roots[0]];
    } else {
      root = new Entity(this.path.substring(this.path.lastIndexOf('/') + 1));
      for (int r : roots) {
        entities[r].transform().setParent(root.transform());
      }
      scene.add(root);
    }
    final List<Skin> skins = new ArrayList<>();
    for (var s : this.asset.skins()) {
      final var joints = new Transform[s.joints().length];
      for (int j = 0; j < joints.length; j++) {
        joints[j] = entities[s.joints()[j]].transform();
      }
      skins.add(new Skin(joints, s.inverseBind()));
    }
    for (int i = 0; i < nodes.size(); i++) {
      final var n = nodes.get(i);
      if (n.mesh() >= 0) {
        final var prims = this.asset.meshes().get(n.mesh()).primitives();
        for (int p = 0; p < prims.size(); p++) {
          final int mat = prims.get(p).material();
          final var r = new MeshRenderer(this.meshes.get(n.mesh()).get(p), mat >= 0 ? this.materials.get(mat)
              : this.fallback);
          if (n.skin() >= 0 && prims.get(p).mesh().isSkinned()) {
            r.skin = skins.get(n.skin());
          }
          entities[i].add(r);
        }
      }
      scene.add(entities[i]);
    }
    if (!this.asset.animations().isEmpty()) {
      final var animator = root.add(new Animator());
      this.asset.animations().forEach(animator::addClip);
    }
    return root;
  }

  /**
   * The non-skinned meshes as a flat model, with each node's world transform baked into a copy of its vertices (one
   * part per primitive). Skinned meshes are left out: they need {@link #instantiate}.
   */
  public Model model() {
    if (this.model != null) {
      return this.model;
    }
    final var nodes = this.asset.nodes();
    final var world = new Matrix4f[nodes.size()];
    final List<Model.Part> parts = new ArrayList<>();
    for (int i = 0; i < nodes.size(); i++) {
      worldMatrix(i, world);
      final var n = nodes.get(i);
      if (n.mesh() < 0 || n.skin() >= 0) {
        continue;
      }
      final var prims = this.asset.meshes().get(n.mesh()).primitives();
      for (int p = 0; p < prims.size(); p++) {
        final var data = prims.get(p).mesh();
        final var baked = world[i].equals(new Matrix4f(), 0f) ? this.meshes.get(n.mesh()).get(p)
            : this.resources.mesh(this.path + "#baked" + i + "." + p, bake(data, world[i]));
        final int mat = prims.get(p).material();
        parts.add(new Model.Part(baked, mat >= 0 ? this.materials.get(mat) : this.fallback));
      }
    }
    this.model = new Model(parts);
    return this.model;
  }

  private Matrix4f worldMatrix(int i, Matrix4f[] world) {
    if (world[i] == null) {
      final var n = this.asset.nodes().get(i);
      final var local = new Matrix4f().translationRotateScale(n.translation(), n.rotation(), n.scale());
      world[i] = n.parent() >= 0 ? new Matrix4f(worldMatrix(n.parent(), world)).mul(local) : local;
    }
    return world[i];
  }

  private static MeshData bake(MeshData data, Matrix4f m) {
    final float[] v = data.vertices().clone();
    final var normal = m.normal(new Matrix3f());
    final var p = new Vector3f();
    for (int o = 0; o < v.length; o += MeshData.FLOATS_PER_VERTEX) {
      m.transformPosition(p.set(v[o], v[o + 1], v[o + 2]));
      v[o] = p.x;
      v[o + 1] = p.y;
      v[o + 2] = p.z;
      normal.transform(p.set(v[o + 3], v[o + 4], v[o + 5])).normalize();
      v[o + 3] = p.x;
      v[o + 4] = p.y;
      v[o + 5] = p.z;
      final int t = o + MeshData.TANGENT_OFFSET;
      m.transformDirection(p.set(v[t], v[t + 1], v[t + 2]));
      if (p.lengthSquared() > 0f) {
        p.normalize();
      }
      v[t] = p.x;
      v[t + 1] = p.y;
      v[t + 2] = p.z;
    }
    return MeshData.trianglesWithTangents(v, data.indices());
  }
}
