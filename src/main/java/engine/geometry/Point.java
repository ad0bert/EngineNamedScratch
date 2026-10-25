package engine.geometry;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class Point {
  float x;
  float y;
  float z;

  public void scale(float scale) {
    this.x += scale;
    this.y += scale;
    this.z += scale;
  }

  public void moveX(float dist) {
    this.x += dist;
  }

  public void moveY(float dist) {
    this.y += dist;
  }

  public void moveZ(float dist) {
    this.z += dist;
  }

  public void moveXY(float x, float y) {
    moveX(x);
    moveY(y);
  }

  public void moveXZ(float x, float z) {
    moveX(x);
    moveZ(z);
  }

  public void moveYZ(float y, float z) {
    moveY(y);
    moveZ(z);
  }

  public void moveXYZ(float x, float y, float z) {
    moveXY(x, y);
    moveZ(z);
  }

  public void setXYZ(float x, float y, float z) {
    this.x = x;
    this.y = y;
    this.z = z;
  }
}
