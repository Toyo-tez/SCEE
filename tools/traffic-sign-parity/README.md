# Traffic sign parity with @osm-traffic-signs/converter

Reference outputs of the converter (pinned to the exact version in `package.json` and
`package-lock.json`) for `TrafficSignConverterParityTest` in the app. Not part of the app.

```
cd tools/traffic-sign-parity
npm ci                      # once, needs network, installs into the git-ignored node_modules
node reference.mjs --check  # verifies that reference.jsonl is what the converter produces now
node reference.mjs          # rewrites reference.jsonl (after a change of the version or the catalogue)
```

The regular test run (`./gradlew :app:testAndroidHostTest`) only reads `reference.jsonl` and needs
neither Node nor network. The main sign x additional sign product (9852 cases) is not written out
in the file: only the cases where the converter changes the value are listed, the test generates all
pairs itself and checks the count. Tarball `@osm-traffic-signs/converter-0.8.0.tgz` has SHA-256
`f0f112f9f7daaf0801000104fac17f19c58fc872016afc7b6930d2f3a23ad70f`, see
`app/src/commonMain/composeResources/files/traffic_signs/VERSION`.
