package fr.adrien.cfcsearch.address;

import org.jetbrains.annotations.Nullable;

/**
 * One line of the address book panel: a category header (no address) or a clickable entry.
 * Kept outside the mixin package, which may only contain mixin classes.
 */
public record BookLine(String label, @Nullable String address, boolean header, int color) {}
