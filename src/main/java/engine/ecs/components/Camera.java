package engine.ecs.components;

import com.jogamp.opengl.glu.GLU;

import engine.geometry.Point;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.FieldDefaults;
import lombok.val;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@FieldDefaults(level = AccessLevel.PRIVATE)
public class Camera extends AbstractComponent {

  @Builder.Default
  Point offset = new Point(0f, 0f, 0f);
  @Builder.Default
  Point viewDirection = new Point(0f, 0f, -1f);
  final Point UP = new Point(0f, 1f, 0f);

  public void getWorldToViewMatrix(GLU glu) {
    val position = this.getEntity().getPosition();
    glu.gluLookAt(position.getX(), position.getY(), position.getZ(),
        position.getX() + this.viewDirection.getX(),
        position.getY() + this.viewDirection.getY(),
        position.getZ() + this.viewDirection.getZ(),
        this.UP.getX(), this.UP.getY(), this.UP.getZ());
  }

}
