package engine.util;

import test.main.TestApp;

public class Timer {
    private static double t = 0.0;
    private static double dt = 0.01;

    private static double currentTime = System.nanoTime();
    private static double accumulator = 0.0;

    public static void updateGame(TestApp game) {
        double newTime = System.nanoTime();// time();
        double frameTime = newTime - currentTime;
        if (frameTime > 0.25) {
            frameTime = 0.25;
        }
        currentTime = newTime;
        accumulator += frameTime;
        while (accumulator >= dt) {
            game.gameLogic(t, dt);
            // integrate( currentState, t, dt );
            t += dt;
            accumulator -= dt;
        }

    }

}
