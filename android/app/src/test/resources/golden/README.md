# Golden fixtures

Copies of `schema/golden/*.json` (frozen contract, WP0). This copy exists because
reading `schema/` via a relative path at unit-test runtime is not reliable; the
copy is synced from `schema/golden/` whenever the schema changes. If a fixture
here and in `schema/golden/` disagree, `schema/` wins — fix the copy.
