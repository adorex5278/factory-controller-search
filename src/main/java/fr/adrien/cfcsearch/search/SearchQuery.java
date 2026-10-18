package fr.adrien.cfcsearch.search;

import io.github.nbcss.createfactorycontroller.content.block.FactoryControllerMenu;
import io.github.nbcss.createfactorycontroller.content.component.gauge.VirtualGaugeBehaviour;
import io.github.nbcss.createfactorycontroller.content.gui.widget.VirtualComponentWidget;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A parsed search string, JEI style. Space-separated terms, all of which must match:
 * <ul>
 *   <li>{@code texte}: in the component's name (first tooltip line)</li>
 *   <li>{@code @mod}: mod id or mod name of the filtered item</li>
 *   <li>{@code #tag}: one of the filtered item's tags (e.g. {@code #logs}, {@code #c:ingots})</li>
 *   <li>{@code $texte}: anywhere in the tooltip</li>
 *   <li>{@code >adresse}: in the gauge's destination address</li>
 *   <li>{@code -terme}: excludes what the term matches (e.g. {@code -@create})</li>
 * </ul>
 * Matching is case- and accent-insensitive.
 */
public final class SearchQuery {

    private record Term(char kind, String text, boolean negated) {}

    private static final String PREFIXES = "@#$>";

    private final List<Term> terms;

    private SearchQuery(List<Term> terms) {
        this.terms = terms;
    }

    public static SearchQuery parse(String raw) {
        List<Term> terms = new ArrayList<>();
        for (String token : raw.trim().split("\\s+")) {
            if (token.isEmpty()) continue;
            boolean negated = false;
            if (token.length() > 1 && token.charAt(0) == '-') {
                negated = true;
                token = token.substring(1);
            }
            char kind = ' ';
            if (token.length() > 1 && PREFIXES.indexOf(token.charAt(0)) >= 0) {
                kind = token.charAt(0);
                token = token.substring(1);
            }
            terms.add(new Term(kind, normalize(token), negated));
        }
        return new SearchQuery(terms);
    }

    public boolean isEmpty() {
        return terms.isEmpty();
    }

    public boolean matches(VirtualComponentWidget widget, FactoryControllerMenu menu) {
        Subject subject = new Subject(widget, menu);
        for (Term term : terms) {
            boolean hit = switch (term.kind) {
                case '@' -> subject.mod().contains(term.text);
                case '#' -> subject.tagsContain(term.text);
                case '$' -> subject.tooltip().contains(term.text);
                case '>' -> subject.address().contains(term.text);
                default -> subject.title().contains(term.text);
            };
            if (hit == term.negated) return false;
        }
        return true;
    }

    public static String normalize(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    /** Lazily computed, normalized views of one component, so each term only pays for what it reads. */
    private static final class Subject {
        private final VirtualComponentWidget widget;
        private final FactoryControllerMenu menu;
        private List<String> lines;
        private String mod;

        Subject(VirtualComponentWidget widget, FactoryControllerMenu menu) {
            this.widget = widget;
            this.menu = menu;
        }

        private List<String> lines() {
            if (lines != null) return lines;
            lines = new ArrayList<>();
            List<FormattedCharSequence> tooltip;
            try {
                tooltip = widget.getTooltip(menu, false);
            } catch (RuntimeException e) {
                return lines;
            }
            if (tooltip == null) return lines;
            for (FormattedCharSequence line : tooltip) {
                StringBuilder sb = new StringBuilder();
                line.accept((index, style, codePoint) -> {
                    sb.appendCodePoint(codePoint);
                    return true;
                });
                lines.add(normalize(sb.toString()));
            }
            return lines;
        }

        String title() {
            List<String> l = lines();
            return l.isEmpty() ? "" : l.getFirst();
        }

        String tooltip() {
            return String.join("\n", lines());
        }

        private ItemStack item() {
            return widget.behaviour() instanceof VirtualGaugeBehaviour gauge ? gauge.filter : ItemStack.EMPTY;
        }

        String address() {
            return widget.behaviour() instanceof VirtualGaugeBehaviour gauge && gauge.recipeAddress != null
                    ? normalize(gauge.recipeAddress) : "";
        }

        String mod() {
            if (mod != null) return mod;
            ItemStack stack = item();
            if (stack.isEmpty()) return mod = "";
            String namespace = BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace();
            String name = ModList.get().getModContainerById(namespace)
                    .map(c -> c.getModInfo().getDisplayName()).orElse("");
            return mod = normalize(namespace + " " + name);
        }

        boolean tagsContain(String text) {
            ItemStack stack = item();
            if (stack.isEmpty()) return false;
            return stack.getTags().anyMatch(tag -> {
                ResourceLocation id = tag.location();
                return normalize(id.toString()).contains(text);
            });
        }
    }
}
