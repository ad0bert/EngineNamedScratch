package engine.geometry;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class Triangle {
  Point edge1;
  Point edge2;
  Point edge3;

  public void scale(float scale) {
    this.edge1.scale(scale);
    this.edge2.scale(scale);
    this.edge3.scale(scale);
  }

  public void moveX(float dist) {
    this.edge1.moveX(dist);
    this.edge2.moveX(dist);
    this.edge3.moveX(dist);
  }

  public void moveY(float dist) {
    this.edge1.moveY(dist);
    this.edge2.moveY(dist);
    this.edge3.moveY(dist);
  }

  public void moveZ(float dist) {
    this.edge1.moveZ(dist);
    this.edge2.moveZ(dist);
    this.edge3.moveZ(dist);
  }
}
