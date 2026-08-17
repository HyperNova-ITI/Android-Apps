# Climate icons — download & replace guide

All UI glyphs live in a dedicated folder, separate from the hand-authored
drawables (backgrounds, launcher, vehicle illustration):

```
app/src/main/res-icons/drawable/     ← icons go here (registered in build.gradle.kts)
app/src/main/res/drawable/           ← bg_*, ic_launcher_*, ic_vehicle_topdown
```

Both compile into the same resource namespace, so `@drawable/ic_*` keeps working.
The files there now are lightweight placeholders. Replace each one **in place,
keeping the exact filename** — the layouts and code reference these names, so no
code changes are needed when you swap the artwork.

## How to download

**Colour is controlled by the app, not the file.** Every icon is tinted from the
layout via `app:tint` (and at runtime for active/inactive states), so you can
ignore whatever colour the downloaded file ships with. Download the **outline**
(unfilled) variant for a consistent look (README §12: "same rounded outline
style").

### Source A — Material Symbols (for the general UI icons)
1. Open <https://fonts.google.com/icons>, set the toolbar to **Material Symbols**,
   Style **Rounded**, Weight **400**, Grade **0**, Optical size **24**, Fill
   **off**.
2. Search the symbol name from the table, select it.
3. Download **Android** (gives a `*.xml` vector drawable). Or download SVG and use
   Android Studio → right-click `res-icons` → *New → Vector Asset → Local file*.
4. Rename to the target filename below and drop it into
   `app/src/main/res-icons/drawable/`, overwriting the placeholder.

### Source B — Material Design Icons / MDI (for the automotive HVAC glyphs)
Material Symbols has no good "airflow-to-face", "defrost", or "heated seat"
glyphs. Use **Pictogrammers MDI** <https://pictogrammers.com/library/mdi/> — it
has proper automotive icons. Download SVG → import as Vector Asset → rename.

## Icon table

### General UI — Material Symbols (Source A)
| Save as (`res-icons/drawable/`) | Used for | Material Symbol |
|---|---|---|
| `ic_back.xml` | Header back | `arrow_back` |
| `ic_logo.xml` | Header brand mark | `ac_unit` *(or keep the HyperNova brand mark)* |
| `ic_thermostat.xml` | Cabin temp | `thermostat` |
| `ic_eco.xml` | Air quality | `eco` |
| `ic_weather.xml` | Outside temp | `light_mode` *(or `partly_cloudy_day`)* |
| `ic_power.xml` | Power | `power_settings_new` |
| `ic_auto.xml` | Auto mode | `hdr_auto` *(the "A"; or `auto_mode`)* |
| `ic_ac.xml` | A/C | `ac_unit` |
| `ic_sync.xml` | Zone sync | `sync` |
| `ic_fan.xml` | Fan speed | `mode_fan` *(or `wind_power`)* |
| `ic_fresh_air.xml` | Fresh air | `air` |
| `ic_recirculate.xml` | Recirculate | `cached` *(or `autorenew`)* |
| `ic_seat.xml` | Zone seat mark | `airline_seat_recline_normal` *(or `event_seat`)* |
| `ic_minus.xml` | Decrease | `remove` |
| `ic_plus.xml` | Increase | `add` |
| `ic_check.xml` | Confirmed / health OK | `check` *(or `done`)* |

### Automotive HVAC — MDI (Source B)
| Save as | Used for | MDI name |
|---|---|---|
| `ic_defrost_front.xml` | Front defrost | `car-defrost-front` |
| `ic_defrost_rear.xml` | Rear defrost | `car-defrost-rear` |
| `ic_max_defrost.xml` | Max defrost | `car-defrost-front` *(or `snowflake`)* |
| `ic_seat_heat.xml` | Seat heating | `car-seat-heater` |
| `ic_airflow_face.xml` | Airflow → face | `car-seat` + up arrow, or MDI `arrow-up-thin` *(no exact glyph — see note)* |
| `ic_airflow_feet.xml` | Airflow → feet | `arrow-down-thin` *(no exact glyph)* |
| `ic_airflow_face_feet.xml` | Airflow → face+feet | `arrow-up-down` *(no exact glyph)* |
| `ic_windshield.xml` | Airflow → windshield | `car-windshield-outline` *(or keep custom)* |

> **Airflow-direction glyphs:** none of the icon sets have a clean
> "person + airflow" symbol. The current placeholders are simple directional
> arrows and read fine — it's reasonable to **keep them** and only replace the
> rest. If you want the classic HVAC look, the closest is to draw/commission four
> matching glyphs, or use MDI `car-...` approximations above.

## After replacing
- Keep filenames identical — do **not** add a second file with the same name in
  `res/drawable` (duplicate resource = build error).
- Rebuild. If an icon looks the wrong colour, it's being tinted by the layout —
  that's intended; adjust the `app:tint` on that view in `fragment_climate.xml`.
- Filenames must be lowercase `a–z`, `0–9`, `_` only.
