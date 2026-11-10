# Contributing

Thanks for helping. Bug reports, fixes and documentation improvements are welcome.

## Setup

Requirements: Node 20 or newer, JDK 17 or newer, and an Android SDK with platform 34
(`ANDROID_HOME` set).

```sh
npm install
npm run build        # TypeScript and rollup
npm run lint         # prettier check
cd android && ./gradlew build   # compile, lint and unit tests of the Android library
```

`npm run verify` runs both builds.

## Guidelines

- Keep the plugin generic. No app specific code, names or endpoints. Use `example.org` in examples.
- The public API lives in `src/definitions.ts`. Change the README API reference with it.
- The plugin supports Capacitor 6, 7 and 8. Do not use APIs that are newer than Capacitor 6.
- Add a unit test for pure logic (see `android/src/test`).
- Add a line to `CHANGELOG.md` under Unreleased.
- Commit messages: short imperative subject, details in the body when needed.

## Pull requests

Describe what changed and why, and how you tested it (device, Android version, distributor).
By contributing you agree that your contribution is licensed under the MIT license.
