package engine.geometry;

public class Point {
    private float x;
    private float y;
    private float z;

    public Point() {
        this.x = 0;
        this.y = 0;
        this.z = 0;
    }

    public Point(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

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

    public float getX() {
        return this.x;
    }

    public float getY() {
        return this.y;
    }

    public float getZ() {
        return this.z;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("(").append(x);
        sb.append(",").append(y);
        sb.append(",").append(z);
        sb.append("");
        return sb.toString();
    }
}
