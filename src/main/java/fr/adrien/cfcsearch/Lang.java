package fr.adrien.cfcsearch;

import net.minecraft.client.resources.language.I18n;

/** Translated text from {@code assets/cfc_search/lang/<langue>.json}, keys prefixed with {@code cfc_search.}. */
public final class Lang {

    private Lang() {}

    public static String t(String key, Object... args) {
        return I18n.get(CfcSearch.MOD_ID + "." + key, args);
    }
}
