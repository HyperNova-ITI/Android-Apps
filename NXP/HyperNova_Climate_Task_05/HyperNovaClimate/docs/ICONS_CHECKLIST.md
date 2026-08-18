# Climate icons — download checklist

Save each into `app/src/main/res-icons/drawable/` using the **exact filename**,
overwriting the placeholder. Color is handled by the app (`app:tint`), so ignore
the file's own color; download the **outline / unfilled** variant.

- Material Symbols: set Style **Rounded**, Weight **400**, Grade **0**, Optical size **24**, Fill **off**, then download **Android** (`.xml`).
- MDI (Pictogrammers): download **SVG**, then Android Studio → right-click `res-icons` → *New → Vector Asset → Local file*.

---

## Source A — Material Symbols (Rounded)
Browse: https://fonts.google.com/icons

- [ ] `ic_back.xml` — Header back — **arrow_back** — https://fonts.google.com/icons?selected=Material+Symbols+Rounded:arrow_back
- [ ] `ic_logo.xml` — Header brand mark *(or keep HyperNova mark)* — **ac_unit** — https://fonts.google.com/icons?selected=Material+Symbols+Rounded:ac_unit
- [ ] `ic_thermostat.xml` — Cabin temp — **thermostat** — https://fonts.google.com/icons?selected=Material+Symbols+Rounded:thermostat
- [ ] `ic_eco.xml` — Air quality — **eco** — https://fonts.google.com/icons?selected=Material+Symbols+Rounded:eco
- [ ] `ic_weather.xml` — Outside temp — **light_mode** *(or partly_cloudy_day)* — https://fonts.google.com/icons?selected=Material+Symbols+Rounded:light_mode
- [ ] `ic_power.xml` — Power — **power_settings_new** — https://fonts.google.com/icons?selected=Material+Symbols+Rounded:power_settings_new
- [ ] `ic_auto.xml` — Auto mode — **hdr_auto** *(or auto_mode)* — https://fonts.google.com/icons?selected=Material+Symbols+Rounded:hdr_auto
- [ ] `ic_ac.xml` — A/C — **ac_unit** — https://fonts.google.com/icons?selected=Material+Symbols+Rounded:ac_unit
- [ ] `ic_sync.xml` — Zone sync — **sync** — https://fonts.google.com/icons?selected=Material+Symbols+Rounded:sync
- [ ] `ic_fan.xml` — Fan speed — **mode_fan** *(or wind_power)* — https://fonts.google.com/icons?selected=Material+Symbols+Rounded:mode_fan
- [ ] `ic_fresh_air.xml` — Fresh air — **air** — https://fonts.google.com/icons?selected=Material+Symbols+Rounded:air
- [ ] `ic_recirculate.xml` — Recirculate — **cached** *(or autorenew)* — https://fonts.google.com/icons?selected=Material+Symbols+Rounded:cached
- [ ] `ic_seat.xml` — Zone seat mark — **airline_seat_recline_normal** *(or event_seat)* — https://fonts.google.com/icons?selected=Material+Symbols+Rounded:airline_seat_recline_normal
- [ ] `ic_minus.xml` — Decrease — **remove** — https://fonts.google.com/icons?selected=Material+Symbols+Rounded:remove
- [ ] `ic_plus.xml` — Increase — **add** — https://fonts.google.com/icons?selected=Material+Symbols+Rounded:add
- [ ] `ic_check.xml` — Confirmed / health OK — **check** *(or done)* — https://fonts.google.com/icons?selected=Material+Symbols+Rounded:check

## Source B — Material Design Icons / MDI (Pictogrammers)
Browse: https://pictogrammers.com/library/mdi/

- [ ] `ic_defrost_front.xml` — Front defrost — **car-defrost-front** — https://pictogrammers.com/library/mdi/icon/car-defrost-front/
- [ ] `ic_defrost_rear.xml` — Rear defrost — **car-defrost-rear** — https://pictogrammers.com/library/mdi/icon/car-defrost-rear/
- [ ] `ic_max_defrost.xml` — Max defrost — **car-defrost-front** *(or snowflake)* — https://pictogrammers.com/library/mdi/icon/car-defrost-front/
- [ ] `ic_seat_heat.xml` — Seat heating — **car-seat-heater** — https://pictogrammers.com/library/mdi/icon/car-seat-heater/

## Airflow direction — no clean match (keep current, or approximate)
No icon set has a proper "person + airflow" glyph. The current placeholders are
simple directional arrows and read fine — recommended to **keep these**.

- [ ] `ic_airflow_face.xml` — Airflow → face — *keep custom* (MDI approx: **arrow-up-thin** — https://pictogrammers.com/library/mdi/icon/arrow-up-thin/)
- [ ] `ic_airflow_feet.xml` — Airflow → feet — *keep custom* (MDI approx: **arrow-down-thin** — https://pictogrammers.com/library/mdi/icon/arrow-down-thin/)
- [ ] `ic_airflow_face_feet.xml` — Airflow → face+feet — *keep custom* (MDI approx: **arrow-up-down** — https://pictogrammers.com/library/mdi/icon/arrow-up-down/)
- [ ] `ic_windshield.xml` — Airflow → windshield — *keep custom* (MDI approx: **car-windshield-outline** — https://pictogrammers.com/library/mdi/icon/car-windshield-outline/)

---

### After each download
- Keep the filename identical; overwrite the file in `res-icons/drawable/`.
- Don't create a same-named file in `res/drawable` (duplicate = build error).
- Rebuild; if a color looks off, adjust that view's `app:tint` in `fragment_climate.xml`.
