package engine.resources;

import java.util.List;

import org.joml.Vector3f;

import engine.ecs.Entity;
import engine.render.GpuMesh;
import engine.render.Material;
import engine.render.MeshRenderer;
import lombok.Getter;

/** A loaded OBJ: one mesh + material per usemtl group, sharing GPU resources through the {@link ResourceManager}. */
public final class Model {
  public record Part(GpuMesh mesh, Material material) {
  }

  @Getter private final List<Part> parts;
  private final Vector3f min = new Vector3f(Float.MAX_VALUE);
  private final Vector3f max = new Vector3f(-Float.MAX_VALUE);

  Model(List<Part> parts) {
    this.parts = List.copyOf(parts);
    for (Part p : parts) {
      this.min.min(p.mesh().data().min());
      this.max.max(p.mesh().data().max());
    }
  }

  public Vector3f halfExtents() {
    return new Vector3f(this.max).sub(this.min).mul(0.5f);
  }

  public Vector3f center() {
    return new Vector3f(this.min).add(this.max).mul(0.5f);
  }

  /** Adds one {@link MeshRenderer} per part to the entity. */
  public Entity attachTo(Entity entity) {
    for (Part p : this.parts) {
      entity.add(new MeshRenderer(p.mesh(), p.material()));
    }
    return entity;
  }
}
