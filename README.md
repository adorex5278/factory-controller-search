# Factory Controller Search

Client-side addon for [Create: Factory Controller](https://github.com/nbcss/create-factory-controller) **1.2.1** (NeoForge 1.21.1).
Nothing runs on the server and nothing is sent anywhere: everything is read from the data the controller screen already receives.

Download: [Modrinth](https://modrinth.com/mod/factory-controller-search) · Changes: [CHANGELOG.md](CHANGELOG.md)

## Features

| Where | What |
|---|---|
| Controller screen, top-right | **Search** field. Matches outlined, others dimmed, `n/total` counter. Enter / Shift+Enter centres the view on the next / previous match, Ctrl+F focuses, Esc leaves, right-click clears. JEI-style prefixes: `text`, `@mod`, `#tag`, `$tooltip`, `>address`, `-exclude`. Case- and accent-insensitive. |
| Controller screen, **Stats** button | Per delivery address: status (slow / missing ingredients / waiting / ok), waiting gauges, longest wait, items per minute. Click a row = search `>address`. Network filter, hover help on every column and button. |
| Stats panel, bottom | **CSV recording** (1 min … 1 h, a snapshot every 10 s) ending with an **Excel report**: Overview (fixes ranked by gauges unblocked ÷ effort), Summary, Chain (root cause traced upstream), Timeline, Gauges, Samples, Help, charts. **Analyse with AI** copies everything to the clipboard and opens an AI chat. |
| Gauge settings screen | **Address book**: processing addresses suggested for the gauge's item from every JEI recipe category (vanilla recipe types without JEI), plus user categories. Click = fill the address, Copy / right-click = clipboard. Optional **network prefix** (`network/type/name`). |

## How it hooks into Factory Controller

Two client mixins (`src/main/java/fr/adrien/cfcsearch/mixin`), compiled against 1.2.1:

- **`FactoryControllerScreenMixin`** → `FactoryControllerScreen`
  - `init` TAIL: creates the search `EditBox` next to the settings button.
  - `renderBoard`, before the 2nd `PoseStack.popPose()` (canvas pose still active): match outlines / dimming.
  - `renderBoard` TAIL: search field, Stats toggle and panel. `render` HEAD `@ModifyVariable` hides the mouse from the board while it is over our panels; `render` TAIL draws our tooltips.
  - `containerTick`: samples `componentWidgets` once per second.
  - `keyPressed`, `mouseClicked`, `mouseScrolled` HEAD; adds `charTyped`.
- **`ConfigureRecipeScreenMixin`** → `ConfigureRecipeScreen`: address book drawn in `renderBg` TAIL, fills the shadowed `addressBox`; input via `mouseClicked` / `keyPressed` / `charTyped` / `mouseScrolled` HEAD.

Data used (all public fields of `VirtualGaugeBehaviour`): `filter`, `count`, `unit`, `stockLevel`, `promisedCount`, `satisfied`, `waitingForNetwork`, `redstonePowered`, `recipeAddress`, `recipeOutput`, `craftBatch`, `customRequestTimer`, `promiseLimit`, `lastRequestTick`, `mode`, `requestMode`, `incomingConnections()` (`LogisticsConnection.from / amount / success`).

The JEI plugin (`compat/`) is optional: it lists recipe categories for the address suggestions and keeps JEI's overlay out of the panels (`IGlobalGuiHandler`).

Other packages: `search/` (query parser), `address/` (address book, network prefixes, recipe type detection), `stats/` (sampling, CSV, hand-written `.xlsx` writer, analysis, AI text, tooltips).

## Files

- `config/cfc_search-adresses.json`: address book (generated in the game language).
- `config/cfc_search-reseaux.json`: network prefixes per controller.
- `config/cfc_search.properties`: `ai_url`, `export_dir`.
- `<game dir>/cfc_search_exports/`: CSV, Excel reports, AI texts.

Languages: `en_us`, `fr_fr` (`src/main/resources/assets/cfc_search/lang`).

## Building

Requires Java 21. Put these jars (not redistributed here) in `libs/`:

- `create-1.21.1-6.0.10.jar`
- `createfactorycontroller-1.2.1-neoforge-1.21.1.jar`
- `jei-1.21.1-neoforge-19.44.0.401.jar`

Then:

```
./gradlew build
```

The jar is written to `build/libs/`.

## License

MIT, see [LICENSE](LICENSE).
