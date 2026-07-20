package com.macekill.addon.modules.macekill;

import java.util.List;

public record Config(
    double moveDistance,
    boolean swingHand,
    boolean autoTotem,
    boolean kehd,
    boolean enableArmorDestroy,
    int ignoreArmorValue,
    List<String> destroyHeights,
    List<String> killHeights
) {}
