package fr.adrien.cfcsearch.stats;

/** A clickable rectangle drawn in a panel last frame, with what clicking it does. */
public record Clickable(int x, int y, int w, int h, Runnable action) {

    public boolean contains(double mx, double my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
