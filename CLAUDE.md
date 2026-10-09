# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A small Java library of test helpers for setting up and verifying database state from plain-text fixture files. It's built with Spring Boot 3.2 / Java 17 / Gradle 8.7. `TestToolbeltApplication` exists only so `@SpringBootTest` has a context to boot. The real code is two classes that both take a `JdbcTemplate`:

- `FileDbImporter.importFrom(path)` loads a fixture file into the DB. It batch-inserts each table section with `PreparedStatement`s and binds every value with `setString`.
- `FileDbComparator.verifyExists(path)` / `verifyNotExists(path)` check each data row against the DB and return the number of mismatches, so `0` means success. Each row is printed to stdout as `○` (match) or `×` (mismatch).

## Commands

```bash
./gradlew build                                   # compile + test
./gradlew test                                    # all tests
./gradlew test --tests FileDbComparatorTest       # single test class
./gradlew test --tests 'FileDbComparatorTest.test' # single test method
```

## Fixture file format

Both classes parse the same line-oriented format. The parsing loop is duplicated in each class, so a format change has to be made in both.

```
[TableName]
{col1;col2;col3}
val1;val2;val3
```

- The separator is `;`. Blank lines are skipped. A file can contain several `[Table]` sections.
- Comparator only: if a field name ends in `@` (e.g. `clob_column@`), that column is left out of the WHERE clause.
- The comparator trims fields, but the importer does not. The comparator also builds its SQL by string concatenation (`col = 'value'`), so values containing `'` will break it.
- Both classes catch exceptions, print the stack trace, and keep going. An I/O or SQL error in the comparator therefore shows up as a wrong count, not a thrown exception.

## Test database

- Tests use SQLite at `./database/mydata`, configured in `src/test/resources/application.yml`. This DB file is committed to git, and it holds the schema (`TestTable`, `Member`). The repo has no migration or DDL scripts. To add a table for tests, change the schema in `database/mydata` itself.
- Fixture paths and the JDBC URL are relative to the working directory, so tests must run from the repo root, which Gradle does by default.
- The test classes are `@Transactional`, so inserts are rolled back and the committed DB stays empty. Keep it that way.
- `sqlite-jdbc` is a `testImplementation` dependency only. The main source set has no JDBC driver, and `src/main/resources/application.properties` configures no datasource.

## Conventions

Code comments, Javadoc, and console messages are written in Japanese. The README is in English.
