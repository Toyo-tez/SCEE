# Updating the traffic sign catalogues

The catalogues of traffic signs (`app/src/commonMain/composeResources/files/traffic_signs/`: one
`{CC}.json` and `{CC}/*.xml` (images converted from SVG) per country, the index `catalogues.json`,
`VERSION`) come from the npm package
[`@osm-traffic-signs/converter`](https://www.npmjs.com/package/@osm-traffic-signs/converter)
(source: https://github.com/osmberlin/osm-traffic-sign-tool, data AGPL-3.0-or-later). The pinned
version and the SHA-256 of its tarball are in `app/build.gradle.kts` (`trafficSignsVersion`,
`trafficSignsSha256`) and in `traffic_signs/VERSION`. Nothing updates itself: the update is a manual
Gradle task, `:app:updateTrafficSigns` (the presets tasks `updatePresets`,
`downloadAndConvertPresetIcons` and `generateTagSuggestions` are for other data and not involved).

## Commands (PowerShell, from the repository root)

```powershell
# 1. Look: download, verify, validate, compare. Writes only a report, changes nothing.
.\gradlew.bat :app:updateTrafficSigns -PtrafficSignsVersion=latest      # or a version like 0.9.0
#    prints the SHA-256 to pin and the path of the report (app\build\traffic-signs-update\traffic-signs-update-<version>.md)

# 2. Read the report. Then apply, with the hash that was printed:
.\gradlew.bat :app:updateTrafficSigns -PtrafficSignsVersion=0.9.0 -PtrafficSignsSha256=<sha256> -PtrafficSignsApply=true
#    changes that need a manual review (below) are refused until you add -PtrafficSignsAcceptReview=true

# 3. Pin the version: edit trafficSignsVersion / trafficSignsSha256 in app\build.gradle.kts, then the follow-ups:
cd tools\traffic-sign-parity
npm install --save-exact "@osm-traffic-signs/converter@0.9.0"
node reference.mjs            # rewrites reference.jsonl: read the differences, do not just accept them
cd ..\..
.\gradlew.bat -p buildSrc test
.\gradlew.bat :app:testAndroidHostTest
```
Without parameters, `.\gradlew.bat :app:updateTrafficSigns` checks the pinned version against the files
(it reports "identical"; running it repeatedly changes nothing).

## What the task does
1. Reads the registry data of the version (`latest` is resolved), downloads the tarball and verifies it: the given SHA-256, or, for a new version without one, the integrity (sha512) of the registry.
2. Finds all catalogues in the package (`dist/data/{CC}.json`). **There is no list of countries**: a country that is added to the package is imported by the next update, without any change of code or configuration.
3. Stages everything in memory per country: `{CC}.json` (unchanged), the images, the index, `VERSION`. Validates the catalogue as a whole (JSON, country, version, list of signs, redirects to nothing or contradicting each other) and each sign. Only the signs are imported that
   - can be read (required fields, known `kind`, no duplicate id), otherwise `invalid_entry`,
   - have an id that the app reads back as exactly this sign: no `,` `;` `+`, no country prefix, no named value like `none`, only letters, digits, spaces, `.` `-` `_` `/` and one value in brackets at the end, or a free text in quotation marks (`osmValuePartProblem`, the same rule in buildSrc and the app); otherwise `unsupported_id` (e.g. `BE:B1+M1`, `PL:B-9,T-22`),
   - do not share an image name with another sign (`image_name_collision`),
   - have an SVG (`no_image`) with a licence (`no_image_licence`) that Svg2Vector converts without error (`defective_image`; warnings are accepted and listed).
   A sign that is left out never stops the import of its country. A new country that can not be read at all is reported and left out; an existing one stops the update.
4. Compares with the existing files per country: signs added, removed, changed (and which fields), images changed, new or removed fields of the entries, changes of the signs of the curated rules, signs that were imported and are not anymore.
5. Writes the report with a table of imported, skipped (no image, no licence, unsupported id) and erroneous (invalid entry, defective image, image name collision) signs per country, and the list of each with the reason. Only with `-PtrafficSignsApply=true`: copies the previous files to `app\build\traffic-signs-update\backup`, writes only the files that differ and deletes the images that are gone. If writing fails, the previous files are restored. The history (`app\traffic-signs-update\history\<old>-to-<new>.md`) is written on success.
6. On any problem (download, hash, archive, a broken existing catalogue) nothing is changed.

The import is deterministic: the same package gives the same files (countries and signs sorted), and running it again changes nothing.

## The index `catalogues.json`
Written by the task, read by the app (`TrafficSignCatalogues`): per imported country the number of signs in the catalogue, imported, skipped and erroneous, the licences of the images and the signs that are left out with the reason. The app offers the editor for exactly the countries in it, and leaves out the listed signs. The catalogues themselves are loaded only when a way or node of that country is edited (no network, nothing at app start).

## Countries and derived tags
The traffic sign editor exists for every imported country. Other tags than `traffic_sign*` are derived only for the countries with curated rules (`WAY_RULES` in `TrafficSignWayTagging.kt`, so far only `DE`); the tag recommendations of the catalogues are never applied. Rules for another country can be added there (and their signs to `rule-signs.txt` as `CC:sign`) without changing the import. Additional signs can only be combined with main signs if the catalogue says which ones fit (`compatibility`, so far only in `DE`).

## Files that a update changes
`traffic_signs/{CC}.json`, `traffic_signs/{CC}/*.xml` (added, changed, removed), `traffic_signs/catalogues.json`, `traffic_signs/VERSION`, `app/traffic-signs-update/history/*.md`. By hand: `app/build.gradle.kts` (pin), `tools/traffic-sign-parity/` (`package.json`, `package-lock.json`, `reference.jsonl`), the expected values in `TrafficSignCatalogueTest` (version, number of signs), see the report. `TrafficSignCatalogueUpdateGuardTest` fails until the version, the build pin, `VERSION`, the parity reference and the tooling agree.

## Recognize new, changed and removed signs
In the report: `added`, `removed`, `changed` (sign: fields), `images changed`, and under "Structure" the added and removed fields. `git diff --stat` and `git diff -- app/src/commonMain/composeResources/files/traffic_signs/DE.json` show the same in the repository.

## Undo
Before applying: nothing to undo. After applying: `git checkout -- app/src/commonMain/composeResources/files/traffic_signs app/build.gradle.kts` (and delete the new history file), or copy the files from `app\build\traffic-signs-update\backup` back.

## Changes that need a manual review (`-PtrafficSignsAcceptReview=true`)
- the signs of the curated tag rules (`rule-signs.txt`, one `CC:sign` per line, the 11 `WAY_RULES` of `DE`), also if they are not imported anymore: kind, signId, recommendations, compatibility, redirects, values, or removal; and redirects that start or end at them. **The rules themselves are in the code and are never changed by an update**; a new or changed catalogue entry cannot create tags (tested), but a changed rule sign may mean that a rule should be reviewed (`TrafficSignRuleCatalogueTest` compares the rules with the catalogue).
- a changed structure of the entries (fields added or removed).
- removed signs, and signs that were imported before and are not anymore (e.g. their image is missing now): existing values with them stay as unknown signs and are kept.
- a country that is in the existing files but not in the package anymore.
- changes of `kind`, `signId`, `redirects`, `redirectDashValues`, `signValue` of any sign: they change how existing values are read.
- always: after a new version, the converter parity (`reference.jsonl`) has to be regenerated and its differences read; a new version without that makes `TrafficSignCatalogueUpdateGuardTest` fail on purpose.
