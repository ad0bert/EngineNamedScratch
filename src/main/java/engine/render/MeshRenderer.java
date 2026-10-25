package engine.render;

import engine.ecs.Component;

/** Draws a mesh with a material at the entity's world transform. */
public class MeshRenderer extends Component {
  public final Mesh mesh;
  public final Material material;

  public MeshRenderer(Mesh mesh, Material material) {
    this.mesh = mesh;
    this.material = material;
  }
}
