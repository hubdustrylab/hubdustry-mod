# Native interface

Hubdustry uses a dark industrial interface: quiet surfaces, strong pale type,
square panels, thin rules and a yellow accent. Content previews carry the visual
detail. The shared implementation is `LibraryTheme`; it owns only Hubdustry
widget styles and fonts, without changing Mindustry's global style registry.

| Token | Value | Use |
| --- | --- | --- |
| Canvas | `#191919` | Dialog surface |
| Paper | `#1f1f1f` | Cards and search |
| Ink | `#eeeeee` | Text and icons |
| Muted | `#aaaaaa` | Secondary text |
| Line | `#383838` | Secondary controls and separators |
| Yellow | `#fffa00` | Primary actions and selected filters |
| Hover | `#30302c` | Pointer feedback |
| On accent | `#191919` | Text and icons on yellow |

Barlow Regular is the 20-unit body face; Barlow Bold supplies 28-unit headings.
Sizes follow the game's UI scale. Vietnamese uses the bundled font; other scripts
use the game's font for the whole label to keep glyph atlases consistent. Font
files and their license ship in the JAR. Icons remain native Mindustry icons.

Schematic Browser and Map Browser keep independent query and scroll state.
Search, sort and pagination remain above the content grid. Schematics use square
previews; maps use a wider card with dimensions. Detail, grouped filters and account
actions open separate dialogs. Filters select the existing system catalog.

The content width is bounded at 1280 units; columns follow the available width.
Controls use 40–64-unit targets and cards have 6-unit outer gutters. Native dialog
footer overlays stay transparent, with reserved space below the scrolling viewport.
Preview images
keep their aspect ratio. Selected filters and primary actions use yellow with dark
text; secondary controls remain neutral.
Below 600 units, search tools and pagination move onto separate rows and the
title badge contracts. Compact previews leave room for names and attribution on
narrow screens.
