package engine.render;

import engine.ecs.Component;

/** Draws a mesh with a material at the entity's world transform. */
public class MeshRenderer extends Component {
  public final GpuMesh mesh;
  public final Material material;
  public boolean castShadows = true;

  public MeshRenderer(GpuMesh mesh, Material material) {
    this.mesh = mesh;
    this.material = material;
  }
}
