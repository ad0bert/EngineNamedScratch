package engine.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import engine.render.MeshFactory;

class ResourceScopeTest {
  private final ResourceTest.FakeBackend backend = new ResourceTest.FakeBackend();
  private final ResourceManager res = new ResourceManager(this.backend);

  @Test
  void closingAScopeReleasesOnlyWhatWasCreatedInIt() {
    final var shared = this.res.texture("textures/cubeFace.png");
    final var sharedCube = this.res.shape("cube:1");

    final var level = this.res.openScope();
    assertSame(shared, this.res.texture("textures/cubeFace.png"), "already cached: not the level's");
    final var levelMesh = this.res.mesh("objects/box.obj");
    final var levelShape = this.res.shape("sphere:1");
    final var generated = this.res.mesh(MeshFactory.cube(2f));
    assertEquals("objects/box.obj", this.res.keyOf(levelMesh));

    level.close();
    assertFalse(level.isOpen());
    assertEquals(3, this.backend.disposed, "the OBJ mesh, the sphere and the generated mesh");
    assertNull(this.res.keyOf(levelMesh), "forgotten");
    assertNull(this.res.keyOf(levelShape));
    assertNull(this.res.keyOf(generated));
    assertSame(shared, this.res.texture("textures/cubeFace.png"));
    assertSame(sharedCube, this.res.shape("cube:1"));
    assertNotSame(levelMesh, this.res.mesh("objects/box.obj"), "loaded again after its scope closed");

    level.close();
    assertEquals(3, this.backend.disposed, "closing twice does nothing");
  }

  @Test
  void modelsAndTheirTexturesBelongToTheScopeTheyWereLoadedIn() {
    final var level = this.res.openScope();
    final var model = this.res.model("objects/capsule/capsule.obj");
    final int meshes = model.parts().size();
    level.close();
    assertTrue(this.backend.disposed >= meshes + 1, "parts and the texture: " + this.backend.disposed);
    assertNotSame(model, this.res.model("objects/capsule/capsule.obj"));
  }

  @Test
  void closingAnOuterScopeAlsoClosesTheInnerOnes() {
    final var outer = this.res.openScope();
    this.res.shape("cube:1");
    final var inner = this.res.openScope();
    this.res.shape("cube:2");

    outer.close();
    assertFalse(inner.isOpen());
    assertEquals(2, this.backend.disposed);
    inner.close();
    assertEquals(2, this.backend.disposed);
  }

  @Test
  void innerScopesReleaseBeforeTheOuterOne() {
    final var outer = this.res.openScope();
    final var a = this.res.shape("cube:1");
    try (var inner = this.res.openScope()) {
      this.res.shape("cube:2");
      assertSame(a, this.res.shape("cube:1"));
    }
    assertEquals(1, this.backend.disposed);
    assertSame(a, this.res.shape("cube:1"), "the outer scope's mesh stays");
    outer.close();
    assertEquals(2, this.backend.disposed);
  }

  @Test
  void sceneClearRemovesEverythingAtTheNextFlushButKeepsLaterAdds() {
    final var scene = new engine.ecs.Scene();
    final var old = scene.add(new engine.ecs.Entity("old"));
    scene.flush();
    final var pending = scene.add(new engine.ecs.Entity("pending"));
    scene.clear();
    final var next = scene.add(new engine.ecs.Entity("next"));
    assertTrue(scene.entities().contains(old), "deferred until the flush");
    scene.flush();
    assertEquals(java.util.List.of(next), scene.entities());
    assertFalse(scene.entities().contains(pending));
  }
}
