## What this changes

<!-- One or two sentences. If it is a fix, say what was broken and how it showed. -->

## Why

<!-- The problem, not only the mechanism. Link an issue if there is one. -->

## Which gate did you run?

Please tick what you actually ran, not what you expect CI to run.

- [ ] `node tools/verify.mjs`
- [ ] `node tools/protocol-test.mjs`
- [ ] `cd mc-plugin && gradle build`
- [ ] `node tools/smoke-server.mjs --jar <jar> --project paper --version 1.21.1` (and/or `--project folia`)
- [ ] `php -l` over the PHP files, and `composer validate` where relevant

## Checklist

- [ ] Any document I changed is updated in **both** languages (unsuffixed file = English, `*.zh-CN.md` = Simplified Chinese), keeping the same headings, code fences and table rows.
- [ ] Version numbers are untouched, or this is a release and all three agree (`mc-plugin/gradle.properties`, `Version.java`, `flarum-extension/composer.json`) with a matching `v` tag.
- [ ] `flarum-extension/js/dist` was rebuilt with `npm run build` if the frontend source changed - it is not edited by hand.
- [ ] No `security.secret`, player personal data or chat transcripts are included in this diff.
- [ ] If behaviour changed, `docs/VERIFICATION.md` and its Chinese counterpart say what is now proven and what still is not.
