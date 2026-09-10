package com.vb.wingfoil;

import io.micronaut.runtime.Micronaut;

public class Application {

    public static void main(String[] args) {
        // The tile-composite feature renders off-screen AWT images (BufferedImage); force headless
        // mode so image creation never attempts to open a display on hosts that have one.
        System.setProperty("java.awt.headless", "true");
        Micronaut.run(Application.class, args);
    }
}
