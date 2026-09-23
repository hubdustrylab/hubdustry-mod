# Native archive interface

Hubdustry owns its archive layout and components. `LibraryBrowser` composes a
full-screen archive from `ArchiveUi` primitives; it does not inherit the game's
mod-browser layout. `LibraryTheme` owns scoped fonts and control states without
changing Mindustry's global style registry.

The composition uses a pale navigation rail against a charcoal workspace, large
display type, a cryo-cyan vertical spine, fine technical rules, grid-backed previews,
cut corners and striped accents. Shapes are drawn natively without image assets
or a web runtime. Native dialog transitions remain available.

| Token | Value | Use |
| --- | --- | --- |
| Canvas | `#191919` | Workspace |
| Paper | `#1f1f1f` | Cards and search |
| Ink | `#eeeeee` | Text and icons |
| Muted | `#aaaaaa` | Secondary text |
| Line | `#383838` | Rules and controls |
| Accent | `#6ecdec` | Active navigation and primary states |
| Hover | `#263b43` | Pointer feedback |
| Accent hover | `#a6eaff` | Illuminated primary action |
| Selection | `#205263` | Text selection |
| Bone | `#e7e7df` | Navigation and prominent actions |
| Black | `#111313` | Text on pale surfaces and preview field |
| Grid | `#292d2c` | Technical preview guides |

Barlow Regular supplies 20-unit body text. Barlow Bold has separate 28-unit and
72-unit atlases, so large headings stay sharp. Sizes follow the game's UI scale.
Vietnamese uses the bundled font; unsupported scripts use the native game font
for the whole label. Font files and their license ship in the JAR.

Schematic and map archives retain independent query, filters, pagination and
scroll state. At 900 units and above, a fixed navigation rail exposes both pages;
the first query result occupies a prominent preview panel above the remaining
collection. This presentation follows query ordering and does not invent an
editorial endorsement or rating. Smaller screens use compact navigation and a
responsive collection, reaching one column at phone widths.

Search, filters, sort and pagination remain real controls. Images fit without
cropping and map dimensions remain visible. Grouped filters, account actions
and details use separate surfaces. Tags select the system catalog. The main
archive has no floating footer over the results; Back is in the navigation.

Preview images come from the backend's verified renderer output with
`thumbnail=true`, preserving schematic floors and map terrain. Renditions are
at most 1024 pixels per side and 2 MiB; the client retains its decode limits.
