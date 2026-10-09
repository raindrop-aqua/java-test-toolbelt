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
- record its version in the class Javadoc (`v1.0.0`). Bump it when behavior changes so users can tell which version they copied.

## Commands

```bash
./gradlew build                                        # compile + test
./gradlew test --tests 'DbJiguTest$パターン番号'          # one nested test class
./gradlew test -Pdbjigu.url=jdbc:postgresql://host:5432/postgres   # override the DB (also dbjigu.user / dbjigu.password)
```

## Fixture format

The Javadoc in `DbJigu.java` is the user-facing spec, so keep it up to date.

```
[TableName]
{#;col1;col2@}
1,2;val1;val2
;val1;<null>
```

- Files are UTF-8 and the separator is `;`. All fields are trimmed, and trailing empty fields are kept (`split(";", -1)`). `<null>` means NULL.
- A header that starts with `#` makes the first field of each row its pattern numbers (comma-separated). An empty pattern field means the row belongs to every pattern. When patterns are passed to a method, it handles only rows in those patterns plus the common rows. Passing a pattern that no row uses is an error.
- A column name ending in `@` is left out of comparison but is still imported.
- Values are converted according to the column's JDBC type, which is read from `ResultSetMetaData` of `SELECT cols FROM table WHERE 1 = 0`. Plain `setString` fails on PostgreSQL for date and numeric columns. CHAR columns are compared using `RTRIM(col)`. LOB columns (CLOB, NCLOB, LONGVARCHAR) are left out of the WHERE clause and compared in Java, because Oracle cannot compare CLOBs with `=`. PostgreSQL `text` is reported as VARCHAR, so the LOB path is not exercised by the current tests.

## Test database

- Tests connect to PostgreSQL 17, which runs in Apple Container (`container ls`, container name `postgresql17`, database/user/password `postgres`). Its port is not published to localhost, so the default URL uses the container IP `192.168.64.2`. Start it with `container start postgresql17` if it is stopped.
- `DbJiguTest` creates the schema from `src/test/resources/schema/postgresql.sql` inside a transaction and rolls back after each test. PostgreSQL DDL is transactional, so nothing is left in the DB. Don't create tables permanently. The DB also holds an unrelated `test_user` table.
- Fixture paths are relative to the repo root, which Gradle uses as the working directory.

## Conventions

Code comments, Javadoc, console messages, exception messages, and test method names are written in Japanese. The README has an English version (`README.md`) and a Japanese version (`README.ja.md`). Keep the two in sync.
