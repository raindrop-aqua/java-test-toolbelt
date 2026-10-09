# DbJigu (DB治具)

English | [日本語](README.ja.md)

A test helper that sets up and verifies database state from plain-text fixture files.

DbJigu is distributed as source, not as a JAR. Copy [`DbJigu.java`](src/test/java/com/prism7/testtoolbelt/DbJigu.java) into your project's test sources (e.g. `src/test/java/<your package>/utils`) and change its package. It needs only Java 17+ and a JDBC driver.

## Usage

```java
DbJigu jigu = new DbJigu(connection);   // in Spring tests: DataSourceUtils.getConnection(dataSource)
jigu.importFrom("src/test/resources/data/setup.txt", 1);       // import pattern 1
// ... run the code under test (with JPA, call entityManager.flush() first) ...
jigu.assertExists("src/test/resources/data/expected.txt", 1);  // throws AssertionError on mismatch
jigu.assertNotExists("src/test/resources/data/deleted.txt");
int mismatches = jigu.verifyExists("src/test/resources/data/expected.txt"); // returns the count instead
```

DbJigu never commits, rolls back, or closes the connection you pass in.

## Fixture format

```
[Member]
{#|MemberId|MemberName|UpdatedAt@}
|0|common|<null>
1|1|john|2024-04-01 12:34:56
1,2|2|sam|2024/04/02
```

| Element | Meaning |
|---|---|
| `[TableName]` | Starts a table section. A file can contain several sections. |
| `{col1\|col2}` | Column names. A name ending in `@` is imported but not compared. |
| `#` as the first column | The first field of each row lists the row's pattern numbers (`1,2`). An empty field means the row belongs to all patterns. |
| `<null>` | NULL. |

- Files are UTF-8, the separator is `|`, and every field is trimmed.
- Values are converted to each column's type. Dates are written as `2024-04-01` or `2024/04/01`, and timestamps as `2024-04-01 12:34:56[.fff]`.
- If you pass pattern numbers, only rows in those patterns and the common rows are processed. If you pass none, every row is processed. Passing a pattern number that no row uses is an error.
- LOB columns (such as Oracle CLOB) are compared in Java rather than in SQL.

### Comparison conditions

When verifying, a mark at the end of a column name changes how that column is compared. Each column can have only one mark. Importing ignores the marks and inserts the values as written.

| Mark | Condition (the DB value is on the left) |
|---|---|
| none | DB value = file value |
| `@` | not compared |
| `<` `<=` `>` `>=` | DB value < file value, and so on |
| `!=` | DB value ≠ file value. Rows where the DB value is NULL also count as not equal. |
| `%` | DB value starts with the file value (prefix match). String columns only. |

```
[Orders]
{order_no|amount>=|amount<|status!=|note%|updated_at@}
A001|100|200|CANCELED|Express|-
```

- `<null>` always means `IS NULL`, whatever the mark. With `!=` it means `IS NOT NULL`.
- When verifying, the same column can appear twice, which lets you specify a range as shown above. Importing a header with a repeated column is an error.
- In a `%` value, `%` and `_` are matched literally.
- How strings sort depends on the database's collation, so use `<`, `>`, and the other ordering marks mainly for numbers and dates.
- LOB columns can't use `<`, `<=`, `>`, or `>=`.

## Development

Tests run against PostgreSQL. See [CLAUDE.md](CLAUDE.md) for the database setup.

```bash
./gradlew build
```
