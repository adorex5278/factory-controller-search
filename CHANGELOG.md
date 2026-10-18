# 1.1.0

Big feature update, still 100 % client-side (nothing is sent to the server). Requires Create: Factory Controller 1.2.1.

## Search
- JEI-style prefixes in the search field: `@mod`, `#tag`, `$tooltip`, `>address`, `-exclude`.

## Address book (gauge settings screen)
- Panel beside the gauge settings: suggests processing addresses for the gauge's item (`processing/pressing`, `processing/smelting`…), from every recipe category JEI knows (Create, vanilla and other mods), plus your own categories (`config/cfc_search-adresses.json`).
- Click a line = fills the address field. "Copy" button / right-click = copies the address to paste on the Frogport.
- **Networks**: optional prefix for several logistics networks with their own machines (`network/type/name`). Default = no prefix. Select with ◀ ▶, create with **+**, forget with **−**; remembered per Factory Controller.
- Generated in the game language (English, French).

## Stats panel ("Stats" button)
- Speed per delivery address: status (slow / missing ingredients / waiting / ok), waiting gauges, longest wait, items per minute. Click a line = highlight its gauges.
- Network filter when a controller drives several networks.
- Hover tooltips on every column and button, "?" help.

## Recording & Excel report
- CSV recording (1 min to 1 h, one snapshot every 10 s while the screen is open) with an Excel report at the end: Overview (what to fix first, ranked by effort), Summary per address, Chain (root cause traced through ingredients), Timeline, Gauges, Samples, Help sheet, charts.
- Detects configuration problems: target at 0, misspelled addresses, phantom promises, overstock, loops, duplicate gauges, huge targets, machine shared by several recipes, no return (address / heating / Frogport).
- CSV separator and decimal follow your computer's regional settings.

## Analyse with AI
- One click copies all the controller's data (with instructions) to the clipboard and opens an AI chat (claude.ai by default, configurable with `ai_url` in `config/cfc_search.properties`). Attaching the Excel report and CSVs helps further.

## Robustness
- Export folder works with any launcher (instance folder, `.minecraft`…), falls back to your home folder, and as a last resort asks where to save (`export_dir` in the config).
- Tooltips and texts adapt to GUI scale, the Unicode font and long translations.
- JEI's overlay stays clear of the panels.
