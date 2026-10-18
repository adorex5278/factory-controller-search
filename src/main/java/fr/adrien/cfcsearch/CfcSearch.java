package fr.adrien.cfcsearch;

import fr.adrien.cfcsearch.stats.CsvRecorder;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only addon: the screens are changed by the mixins in {@code fr.adrien.cfcsearch.mixin}. */
@Mod(value = CfcSearch.MOD_ID, dist = Dist.CLIENT)
public final class CfcSearch {
    public static final String MOD_ID = "cfc_search";

    public CfcSearch() {
        // A CSV recording must end on time even if its controller screen was closed meanwhile.
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> CsvRecorder.checkEnd());
    }
}
