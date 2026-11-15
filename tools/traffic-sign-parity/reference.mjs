// Generates (or checks) the reference outputs of @osm-traffic-signs/converter for the SCEE parity
// test (app/src/commonTest/.../TrafficSignConverterParityTest.kt).
//
//   cd tools/traffic-sign-parity && npm ci && node reference.mjs          # (re)write reference.jsonl
//   cd tools/traffic-sign-parity && npm ci && node reference.mjs --check  # verify reference.jsonl, no write
//
// The inputs are generated deterministically from the bundled DE catalogue (same file as in the
// converter package, checked below) and a fixed list of hand-written edge cases. The expected
// outputs come only from the converter, never from SCEE code.

import { createHash } from 'node:crypto'
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { trafficSignTagToSigns, signsToTrafficSignTagValue } from '@osm-traffic-signs/converter'

const here = dirname(fileURLToPath(import.meta.url))
const packageDir = join(here, 'node_modules/@osm-traffic-signs/converter')
const packageJson = JSON.parse(readFileSync(join(packageDir, 'package.json'), 'utf8'))
const catalogueFile = join(here, '../../app/src/commonMain/composeResources/files/traffic_signs/DE.json')
const sha256 = (buf) => createHash('sha256').update(buf).digest('hex')

const catalogueBytes = readFileSync(catalogueFile)
const converterCatalogueBytes = readFileSync(join(packageDir, 'dist/data/DE.json'))
if (sha256(catalogueBytes) !== sha256(converterCatalogueBytes)) {
  throw new Error('app DE.json differs from the DE.json of the converter package')
}
const catalogue = JSON.parse(catalogueBytes.toString('utf8'))
const signs = catalogue.signs
const isModifier = (s) => s.kind !== 'traffic_sign'
const mains = signs.filter((s) => !isModifier(s))
const modifiers = signs.filter(isModifier)

/** [category, input] */
const cases = []
const add = (category, input, meta) => cases.push([category, input, meta])

// 1 every sign alone, with and without country prefix
for (const s of signs) add('single', `DE:${s.osmValuePart}`)
for (const s of signs) add('single_no_prefix', s.osmValuePart)

// 2 redirects: every redirect source (and in lower case), dash values
for (const s of signs) {
  for (const r of s.redirects ?? []) {
    add('redirect', `DE:${r.from}`)
    if (r.from.toLowerCase() !== r.from) add('redirect_lowercase', `DE:${r.from.toLowerCase()}`)
  }
  if (s.redirectDashValues) for (const v of ['30', '7.5', '5']) add('redirect_dash_value', `DE:${s.signId}-${v}`)
}

// 3 variable values (custom values for signs with a default value)
for (const s of signs.filter((x) => x.signValue != null)) {
  const fmt = s.valuePrompt?.format
  const values = fmt === 'opening_hours' || fmt === 'time_restriction'
    ? ['16:00-18:00', 'Mo-Fr 07:00-09:00', '08:00-11:00,16:00-18:00']
    : ['1', '30', '7.5', '120']
  for (const v of values) add('variable_value', `DE:${s.signId}[${v}]`)
  add('variable_value_missing', `DE:${s.signId}`)
}

// 4 main sign x compatible additional sign (comma = belongs together)
for (const m of mains) {
  if (m.compatibility?.canReceiveModifiers === false) continue
  const incompatible = new Set(m.compatibility?.incompatibleModifiers ?? [])
  for (const mod of modifiers) {
    if (incompatible.has(mod.osmValuePart) || incompatible.has(mod.signId)) continue
    add('main_with_modifier', `DE:${m.osmValuePart},${mod.osmValuePart}`, [m.osmValuePart, mod.osmValuePart])
  }
}

// 5 independent signs and longer groups, deterministic
mains.forEach((m, i) => {
  const n = mains[(i + 1) % mains.length]
  const o = mains[(i + 7) % mains.length]
  const mod = modifiers[i % modifiers.length]
  add('independent', `DE:${m.osmValuePart};${n.osmValuePart}`)
  add('group_chain', `DE:${m.osmValuePart},${mod.osmValuePart};${n.osmValuePart},${modifiers[(i + 3) % modifiers.length].osmValuePart};${o.osmValuePart}`)
})

// 6 named values
const named = ['city_limit', 'maxspeed', 'none', 'destination', 'yes', 'variable', 'hazard', 'signals', 'give_way', 'stop', 'variable_message']
for (const v of [...named, 'no', 'NO', 'None', 'DE:none', 'DE:yes', 'de:none']) add('named', v)
const sampleSigns = [mains[0], mains[10], mains[50], mains[100]].map((s) => s.osmValuePart)
for (const v of named) {
  for (const s of sampleSigns) {
    add('named_with_sign', `${v};DE:${s}`)
    add('sign_with_named', `DE:${s};${v}`)
    add('named_with_comma', `${v},DE:${s}`)
    add('sign_comma_named', `DE:${s},${v}`)
  }
}

// 7 country prefixes
for (const v of [
  'de:250', 'De:250', 'DE:250;DE:274-30', 'DE:250,DE:1020-30', 'DE:250;de:274-30', ' DE:250 ', 'DE: 250', 'DE:  250 , 1020-30',
  'AT:250', 'DE:250;AT:52', 'AT:52;DE:250', 'FR:250', 'DE:DE:250', '250', '250,1020-30', 'DE:250,1020-30;274-30', 'xx:250',
]) add('prefix', v)

// 8 normalization: spaces, empty parts, redirects of groups
for (const v of [
  'DE:250,,1020-30', 'DE:250,1020-30;', ';DE:250', ',DE:250', 'DE:250;;274-30', 'DE:250 ; 274-30', 'DE:250 , 1020-30', 'DE:274[30],1020-30',
  'DE:241', 'DE:242', 'DE:250,KFZ frei', 'DE:260,Kleinkrafträder frei', 'DE:274[30]', 'DE:274.1[25]',
]) add('normalization', v)

// 9 brackets
for (const v of [
  'DE:250[3]', 'DE:274[30]', 'DE:274[30', 'DE:274[30]]', 'DE:274[[30]]', 'DE:1042-31[Mo-Sa 18:00-19:00]', 'DE:1040-30[16:00-18:00]',
  'DE:1040-31[08:00-11:00,16:00-18:00]', 'DE:1042-32[PH off;Mo-Sa 8:30-11:30,16:00-18:00]', 'DE:274[]', 'DE:250,1020-32[42]', 'DE:1020-32[42]',
  'DE:1020-32[42];274[30]', 'DE:262[7.5]', 'DE:262-7.5', 'DE:262[7,5]', 'DE:274[30]x',
]) add('brackets', v)

// 10 unknown signs
for (const v of [
  'DE:9999', 'DE:9999-99', 'DE:foo', 'DE:250,9999-99;1234', 'DE:250,9999;123', 'DE:9999,250', 'DE:1234;5678', 'DE:Foo Bar', 'DE:250,foo bar',
]) add('unknown', v)

// 11 free texts
for (const v of [
  'DE:250,"Kfz-Verkehr frei"', 'DE:250,"Anwohner, Taxi frei"', 'DE:250,"Lieferverkehr; Anlieger"', 'DE:250,"A=B"', 'DE:250,"Anwohner=ja"',
  'DE:250,"traffic_sign=x"', 'DE:250,"x [y] z"', 'DE:250,"a,b",1020-30', 'DE:250,"one","two, three"', 'DE:250,"one";274-30,"two; three"',
  'DE:"Fahrräder"', 'DE:"Sonder, Zeichen";250', '"Kfz-Verkehr frei"', 'DE:250,"DE:123"', 'DE:250,"de:abc"', 'city_limit;DE:250,"a,b"',
  'DE:250,"unclosed', 'DE:250,"a""b"', 'DE:250,""', 'DE:250,"   "',
]) add('freetext', v)

// 12 key prefixes
for (const v of [
  'traffic_sign=DE:250', 'traffic_sign:forward=DE:250', 'traffic_sign:backward=DE:250', 'bicycle:right:traffic_sign=DE:250', 'traffic_sign =DE:250',
  'traffic_sign=', 'traffic_sign=none', 'traffic_sign=city_limit;DE:310', 'foo=DE:250', 'DE:250=x', 'traffic_sign=DE:250,"a=b"', 'traffic_sign=traffic_sign=DE:250',
]) add('key_prefix', v)

// 13 damaged or ambiguous values
for (const v of ['', ' ', 'DE:', 'DE: ', ';', ',', ',,', 'DE:250]', ']DE:250', 'DE:"', '"', 'DE:250,"x', 'DE:250,x"', '[', 'DE:[', 'DE:250,[1020-30]', 'DE:\t250']) add('damaged', v)

// ------------------------------------------------------------------------------------------------

const signByPart = new Map(signs.map((s) => [s.osmValuePart, s]))

const rows = []
// The main sign x additional sign product (almost 10 000 cases) is not written out: the converter
// is expected to leave such a value as it is (value unchanged, each part recognized, kind from the
// catalogue). Only cases where the converter does something else are listed, so that the reference
// file stays small. The test generates all pairs itself and checks the count.
let productCount = 0
cases.forEach(([category, input, meta]) => {
  const parsed = trafficSignTagToSigns(input, 'DE')
  const parts = parsed.map((s) => [s.osmValuePart, s.kind, s.recognized ? 1 : 0])
  const value = signsToTrafficSignTagValue(parsed, 'DE')
  if (category === 'main_with_modifier') {
    productCount++
    const expectedParts = meta.map((v) => [v, signByPart.get(v).kind, 1])
    const identity = value === input && JSON.stringify(parts) === JSON.stringify(expectedParts)
    if (identity) return
  }
  rows.push({ c: category, i: input, p: parts, o: value })
})
rows.forEach((r, n) => { r.n = n })

const header = {
  converter: `${packageJson.name}@${packageJson.version}`,
  catalogueSha256: sha256(catalogueBytes),
  count: cases.length,
  rows: rows.length,
  mainWithModifierProduct: productCount,
}
const text = [JSON.stringify(header), ...rows.map((r) => JSON.stringify(r))].join('\n') + '\n'
const target = join(here, 'reference.jsonl')

if (process.argv.includes('--check')) {
  if (!existsSync(target)) throw new Error('reference.jsonl does not exist')
  const current = readFileSync(target, 'utf8')
  if (current !== text) {
    console.error('reference.jsonl is not up to date, run: node reference.mjs')
    process.exit(1)
  }
  console.log(`reference.jsonl is up to date: ${rows.length} cases, ${header.converter}`)
} else {
  writeFileSync(target, text)
  const counts = {}
  for (const r of rows) counts[r.c] = (counts[r.c] ?? 0) + 1
  console.log(`${header.converter}: wrote ${header.count} cases (${rows.length} rows) to reference.jsonl`)
  console.log(JSON.stringify(counts))
}
