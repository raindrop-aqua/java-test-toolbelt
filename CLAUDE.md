# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

DbJigu (DB治具) is a test helper for setting up and verifying database state from plain-text fixture files. It is **not distributed as a JAR**. Users copy the single file `src/test/java/com/prism7/testtoolbelt/DbJigu.java` into their project's test sources (e.g. `src/test/java/<their package>/utils`) and change the package. It lives under `src/test` here too, matching where it is used, so it can never end up in a production artifact. Everything else in this repo exists only to develop and test that file.

Because of how it is distributed, `DbJigu.java` must:

- stay a single self-contained file that depends only on the JDK and JDBC. No Spring or other libraries. Helper types go in as private nested classes.
- compile on Java 17. `options.release = 17` in `build.gradle` enforces this.
- keep working on several databases. The main target is PostgreSQL, and Oracle should keep working too, because it has been used there. Avoid DB-specific SQL.
- never commit, roll back, or close the `Connection` it is given. In Spring tests, callers pass `DataSourceUtils.getConnection(dataSource)` so the test transaction can roll back.
- throw on errors instead of swallowing them, so that a mistake can never pass silently.
- record its version in the class Javadoc (`v2.4.0`) and in `build.gradle`. Bump it when behavior changes so users can tell which version they copied.
- keep the MIT license notice in the comment above `package`. Users copy only this file, so the notice has to travel with it (`LICENSE` holds the full text).

## Commands

```bash
./gradlew build                                        # compile + test
./gradlew test --tests 'DbJiguTest$パターン番号'          # one nested test class
./gradlew test --tests '*OrderServiceTest*'              # the guide's examples
./gradlew test -Pdbjigu.url=jdbc:postgresql://host:5432/postgres   # override the DB (also dbjigu.user / dbjigu.password)
node --test tools/test/*.test.js                         # fixture editor (tools/fixture-editor.html)
```

## Fixture format

The Javadoc in `DbJigu.java` is the user-facing spec, so keep it up to date.

```
[TableName]
{#|col1|col2@}
1,2|val1|val2
|val1|<null>
```

- A line that starts with `//` (after trimming) is a comment and is skipped. There are no end-of-line comments, because values such as URLs contain `//`.
- Files are UTF-8 and the separator is `|`. All fields are trimmed, and trailing empty fields are kept (`split(Pattern.quote("|"), -1)`; `split` takes a regex, so the separator must be quoted). `<null>` means NULL.
- A header that starts with `#` makes the first field of each row its pattern numbers (comma-separated). A pattern number is a number or a name (`gold`). An empty pattern field means the row belongs to every pattern, but an empty item inside it (`1,,2`) is an error. When patterns are passed to a method, it handles only rows in those patterns plus the common rows. Passing a pattern that no row uses is an error.
- Patterns are compared through `patternKey`: anything that reads as an integer (`\p{Nd}`, so a full-width `１` counts, as it did with the old `Integer.parseInt`) is compared as a number (`01`, `+1`, and `1` are the same), and names ignore case (`Locale.ROOT`). The fixture editor's `FixtureCore.patternKey` must stay the same.
- Every public method has three overloads: no patterns, `int...`, and `String...`. The no-pattern overload is required, because without it `importFrom(file)` would be ambiguous between the two varargs methods and fail to compile. The `int...` overload exists so that callers written before names were allowed keep compiling.
- A mark at the end of a column name sets the comparison: `@` (not compared), `<` `<=` `>` `>=` (the DB value is on the left), `!=` (written as `(col <> ? OR col IS NULL)`), and `%` (prefix `LIKE ? ESCAPE '!'`, string columns only). Two-character marks are checked first in the `Operator` enum, and combining marks is an error. Importing ignores the marks. A repeated column is allowed when verifying, for ranges, but is an error when importing. `<null>` means `IS NULL` for every mark except `!=`, where it means `IS NOT NULL`. LOB columns are compared in Java (`matchesInJava`) and can't use the ordering marks.
- When a row fails, verification adds hints under it (`explain`): for a missing row, it searches again leaving out one condition at a time (`findRows` with `excluded`) and shows each column whose removal makes the row match, with up to three distinct DB values; for a row that must not exist, it shows the found rows' values of the marked columns. Only the first 10 failed rows per verification are explained, and the extra SQL runs only on failure. `verify*` still returns the number of failed rows. If the extra SQL fails, the row is still reported as failed and the hint says it could not be checked, because the verification result must not change.
- Values are converted according to the column's JDBC type, which is read from `ResultSetMetaData` of `SELECT cols FROM table WHERE 1 = 0`. Plain `setString` fails on PostgreSQL for date and numeric columns. CHAR columns are compared using `RTRIM(col)`. LOB columns (CLOB, NCLOB, LONGVARCHAR) are left out of the WHERE clause and compared in Java, because Oracle cannot compare CLOBs with `=`. PostgreSQL `text` is reported as VARCHAR, so the LOB path is not exercised by the current tests.

## Test database

- Tests connect to PostgreSQL 17, which runs in Apple Container (`container ls`, container name `postgresql17`, database/user/password `postgres`). Its port is not published to localhost, so the default URL uses the container IP `192.168.64.2`. Start it with `container start postgresql17` if it is stopped.
- `DbJiguTest` creates the schema from `src/test/resources/schema/postgresql.sql` inside a transaction and rolls back after each test. PostgreSQL DDL is transactional, so nothing is left in the DB. Don't create tables permanently. The DB also holds an unrelated `test_user` table.
- Fixture paths are relative to the repo root, which Gradle uses as the working directory.
- GitHub Actions (`.github/workflows/build.yml`) runs `./gradlew build` on Java 17 against a `postgres:17` service, on every pull request and push to `main`, passing `-Pdbjigu.url=jdbc:postgresql://localhost:5432/postgres`. A second job runs the fixture editor's tests. The README's Development section explains the same setup for people; keep the two consistent.

## Spring example

Users run DbJigu in Spring Boot + Spring Data JPA projects, so `src/test/java/com/prism7/testtoolbelt/spring/` contains a working example (`DbJiguSpringExampleTest`, plus a test-only `@SpringBootApplication`, entity, and repository). Spring Boot is a test dependency here only for this example, imported as a BOM with `platform()` and no Boot plugin. `DbJigu.java` itself must still not depend on Spring.

- The example gets its connection from `DataSourceUtils.getConnection(dataSource)`, calls `entityManager.flush()` before verifying, and uses `@Sql("/schema/postgresql.sql")` to create the tables inside the test transaction. An `@AfterTransaction` check confirms that the rollback happened.
- `src/test/resources/application.properties` sets the datasource (the same `dbjigu.*` overrides apply) and `ddl-auto=none`.
- Spring Boot's naming strategy rewrites even explicit `@Column` names (`MemberId` becomes `member_id`), so entity mappings use the lowercase PostgreSQL names.
- When you change the README's Spring example, keep it consistent with this test and with the Javadoc example in `DbJigu.java`.

## Guide and its example

`docs/guide.ja.md` (Japanese) and `docs/guide.md` (English) are the guide for people writing tests with DbJigu. Keep the two in sync.

- Every fixture and test shown in the guide is a real file under `src/test`: `example/OrderServiceTest` and `OrderService` (order management, plain JDBC), `src/test/resources/schema/order.sql`, and `src/test/resources/data/order/`. When you change one, update the guide so the quoted code and fixtures stay identical.
- The Spring section quotes `spring/DbJiguSpringExampleTest`.
- When DbJigu's behavior, fixture format, or error messages change, update the guide as well as the README and Javadoc.

## Fixture editor

`tools/fixture-editor.html` is a browser tool for editing fixtures as tables. Like `DbJigu.java`, it is meant to be copied, so it stays one self-contained HTML file: no external scripts, styles, fonts, or network access, and it works when opened from `file://`. The UI text is Japanese. The README's "Fixture editor" section and the guide's Tips describe it for users; keep them in sync with what it does.

### Structure

- `<script id="core">` defines `FixtureCore`, which parses, writes, and checks fixtures without touching the DOM. The tests load this block on its own, so keep it free of DOM access and keep the `id`.
- The second `<script>` is the UI. The model (`state.doc`) is the source of truth while the table view is shown. The table is rebuilt from the model after every structural change (`mutate`). Typing in a cell updates the model in place without rebuilding, so focus is kept.
- Model: `doc = { eol, sections, trailing }`. A section is `{ name, comments, headerComments, columns: [{ name, mark }], hasPattern, rows }`, and a row is `{ kind: 'data', pattern, cells }`, `{ kind: 'comment', text }`, or `{ kind: 'blank' }`. Issue positions use `s` (section), `r` (`'name'`, `'h'`, or a row index), and `f` (field index, where 0 is the pattern number when `hasPattern`).

### Rules it must keep

- Read files exactly as `DbJigu.readFile` does: Java-style `trim()` (only characters up to U+0020, so a full-width space is kept), two-character marks first, a second `{...}` line in a table starts a new table, and the same structural errors with the same messages. When the fixture format or DbJigu's error checks or messages change, update `FixtureCore` and its tests too.
- After a structural error, keep reading so the rest of the file can be shown, but don't report errors that only follow from the first one.
- Saving must never change what DbJigu reads. Aligned output pads fields with spaces and starts data rows with one space to line up with `{`; this is safe only because DbJigu trims every field.
- A comment right after a data row belongs to that table (a commented-out row). A comment after a blank line belongs to the next table (a heading). This keeps comments where they were when the file is saved.
- Pattern numbers are compared the same way as in DbJigu (`patternKey`), and the pattern filter shows each pattern as it was first written in the file.
- Checks that DbJigu turns into exceptions are `error`. Things that work but are often a mistake are `warn`. A repeated column is only `info`, because it is correct in verify files (ranges).

### Decided not to do

- **Connecting to a database.** The editor stays offline, so it can't check table names, column names, or value types. A separate tool could generate fixture skeletons from a database later.
- **Creating expected files from the current database contents.** That turns the code's actual output into the expected result, so the test can't fail. Don't add it to the editor or to a future generator.
- **Escaping `|` or writing values that start with `[`, `{`, or `//`.** DbJigu can't read them (issue #12), so the editor reports them as errors instead.

### Tests

```bash
node --test tools/test/*.test.js     # FixtureCore and the UI (no npm packages needed)
```

- `core.test.js` round-trips every fixture under `src/test/resources/data` (with and without alignment) and tests parsing, writing, checks, and TSV. Fixtures added to the repo are picked up automatically.
- `ui.test.js` opens the page in headless Chrome with `ui-harness.js` injected and checks the result of real UI operations. It is skipped when Chrome isn't found (`CHROME_PATH` overrides the location), except in CI, where a missing Chrome is a failure.
- `ime.test.js` drives the page through the Chrome DevTools Protocol (`chrome.js`) to send IME input (`Input.imeSetComposition`), which `--dump-dom` can't do. It needs Node 22 or later for the built-in `WebSocket`, and is skipped without it or without Chrome, except in CI.
- Keyboard handlers must ignore keys while an IME is composing (`isComposing`, `keyCode` 229, or between `compositionstart` and `compositionend`). Otherwise the Enter that confirms a conversion also moves to the next row, and the confirmed text is typed again there (#27).
- GitHub Actions runs these in the `fixture-editor` job.
- When a change could affect what DbJigu reads (parsing or alignment), also run `./gradlew test` against a copy of the repo whose fixtures were rewritten with aligned output. Every test should pass: since v2.3.1, the rows shown in verification output are the trimmed values joined with `|`, so padding doesn't appear in messages.

## Code review

`.claude/agents/dbjigu-reviewer.md` is a project subagent for reviewing `DbJigu.java`. It reports bugs it has verified, without fixing them, and applies behavior-preserving simplifications after confirming that `./gradlew build` passes. Use it after changing `DbJigu.java`.

## Conventions

Code comments, Javadoc, console messages, exception messages, and test method names are written in Japanese. The README has an English version (`README.md`) and a Japanese version (`README.ja.md`). Keep the two in sync.

Issues and PRs are written in Japanese. Follow the templates in `.github/` (`ISSUE_TEMPLATE/feature_request.md`, `ISSUE_TEMPLATE/bug_report.md`, `pull_request_template.md`), and fill in the PR checklist.
