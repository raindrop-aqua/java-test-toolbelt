# DbJigu (DB治具)

English | [日本語](README.ja.md)

A test helper that sets up and verifies database state from plain-text fixture files.

DbJigu is distributed as source, not as a JAR. Copy [`DbJigu.java`](src/test/java/com/prism7/testtoolbelt/DbJigu.java) into your project's test sources (e.g. `src/test/java/<your package>/utils`) and change its package. It needs only Java 17+ and a JDBC driver.

To learn how to write tests with DbJigu, step by step with a worked example, read the [Test Writing Guide](docs/guide.md).

## Usage

```java
DbJigu jigu = new DbJigu(connection);   // in Spring tests: DataSourceUtils.getConnection(dataSource)
jigu.importFrom("src/test/resources/data/setup.txt", 1);       // import pattern 1 (or a name: "gold")
// ... run the code under test (with JPA, call entityManager.flush() first) ...
jigu.assertExists("src/test/resources/data/expected.txt", 1);  // throws AssertionError on mismatch
jigu.assertNotExists("src/test/resources/data/deleted.txt");
int mismatches = jigu.verifyExists("src/test/resources/data/expected.txt"); // returns the count instead
```

When a row does not match, the message also shows which column differed and its value in the DB, found by searching again with one condition left out at a time (`stock を除くと一致する行があります。DB の値: 99`).

DbJigu never commits, rolls back, or closes the connection you pass in.

### With Spring (Spring Boot + Spring Data JPA)

```java
@SpringBootTest
@Transactional
class MemberServiceTest {
    @Autowired DataSource dataSource;
    @PersistenceContext EntityManager entityManager;
    DbJigu jigu;

    @BeforeEach
    void setUp() {
        // Pass the connection that takes part in the test transaction, so imported data is rolled back too
        jigu = new DbJigu(DataSourceUtils.getConnection(dataSource));
        jigu.importFrom("src/test/resources/data/setup.txt");
    }

    @Test
    void test() {
        // ... run the code under test ...
        entityManager.flush();   // JPA changes reach the database only when flushed
        jigu.assertExists("src/test/resources/data/expected.txt");
    }
}
```

- Don't use `dataSource.getConnection()`. It returns a separate connection, so imported data is committed and never rolled back.
- Without `flush()`, JPA changes aren't in the database yet, and verification won't find them.

For a working example, see [DbJiguSpringExampleTest](src/test/java/com/prism7/testtoolbelt/spring/DbJiguSpringExampleTest.java).

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
| `#` as the first column | The first field of each row lists the row's pattern numbers (`1,2`) or names (`gold,silver`). An empty field means the row belongs to all patterns. |
| `<null>` | NULL. |
| `// comment` | A line starting with `//` is ignored. A comment cannot start in the middle of a line. |

- Files are UTF-8, the separator is `|`, and every field is trimmed.
- Values are converted to each column's type. Dates are written as `2024-04-01` or `2024/04/01`, timestamps as `2024-04-01 12:34:56[.fff]`, and times as `12:34[:56]`. A decimal in an integer-type column such as `INTEGER` or `BIGINT` is an error (for `NUMERIC(10)` or Oracle `NUMBER` columns, the database rounds it).
- If you pass pattern numbers (`int`) or names (`String`), only rows in those patterns and the common rows are processed. If you pass none, every row is processed. Passing a pattern that no row uses is an error.
- Names ignore case (`Gold` and `gold` are the same), and anything that reads as a number is compared as a number (`01`, `+1`, and a full-width `１` are the same as `1`). Names can't contain `|` or `,`.
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

## Fixture editor

[`tools/fixture-editor.html`](tools/fixture-editor.html) edits fixtures as tables. Download the file and open it in a browser. It needs no installation and sends nothing over the network.

- Edit each table as a grid, then save it. The row and column buttons (move, add, duplicate, comment out, delete) can be hidden with "行・列の操作" (row and column tools) in the toolbar.
- Even in a table with many columns and rows, the scroll bars stay at the edge of the window. The column names, the line numbers, and the table name stay visible while you scroll.
- The "テーブル" (tables) tab in the side panel lists the tables in file order, with their row counts and numbers of errors and warnings. Click one to jump to it.
- Register DDL (`.sql` files with `CREATE TABLE`) in the "スキーマ" (schema) tab to use the table definitions without connecting to a database.
  - When you type a table name, the registered tables are suggested. When you pick one, you can add all its columns, only the required ones (the primary key and NOT NULL columns without a default), or only the primary key. When you type a column name, the columns you haven't written yet are suggested with their types.
  - Each column name shows its type (such as `VARCHAR(10) NN`) under it. Turn this on or off with "型" (types) in the toolbar.
  - Table and column names not in the schema, and required columns missing from the file, are reported as warnings in the check results. The editor can't tell a file for importing from one for verifying, so the warning about required columns also appears for verification files, where it can be ignored.
  - Values are checked against the column types with the same rules DbJigu uses to convert them: date formats and dates that don't exist, decimals in integer columns, the digits of `NUMERIC(p,s)`, the length of `VARCHAR(n)`, booleans, empty strings in number columns, and so on. A value the database would round, such as `1.5` in a `NUMERIC(10)` column, is also reported, because verification would then not match.
  - A table whose foreign-key parent appears only later in the file gets an import-order warning (fine for a verification file).
  - It lists the columns of the table you are editing, with their types, primary key, and NOT NULL. A required column missing from the file gets ⚠, and a table or column name not in the schema shows similar names. Click a column to jump to it in the table.
  - It shows the parent tables of foreign keys, and marks a parent that comes later in the file with "先に投入" (import it first). You can search by table name or column name.
  - Only `CREATE TABLE` is read. `ALTER TABLE` and other statements are ignored (you can see which ones). It is tested with PostgreSQL DDL. Common Oracle syntax (`VARCHAR2`, `NUMBER`, quoted names, and so on) is also handled, but hasn't been tried on real Oracle DDL.
  - The registered DDL is saved in the browser. Use "↻" to read the files again. If the browser can't save it, register it each time you open the editor.
- The check results and patterns are shown in a side panel on the right. Close it with the buttons on the right edge to give the tables more room. In a narrow window, it opens over the tables.
- With "桁揃え" (align) on, the `|` characters are lined up so the file is easy to read as text too. DbJigu trims every field, so the alignment does not change the meaning.
- It checks the file with the same rules DbJigu uses (for example, a row whose number of values doesn't match the header, or an empty pattern number such as `1,,2`) and shows the line numbers.
- Choose a pattern number to dim the rows that are not part of it, and see how many rows each table has per pattern.
- Paste a range copied from Excel into a cell, or copy a table as TSV to paste into Excel.
- In Chrome and Edge, it saves back to the file you opened. Other browsers download the file instead.

It does not connect to a database. Without registered DDL, it can't check table names or column names. With DDL, it also checks values against the column types, but it judges from the type names in the DDL rather than the database's actual (JDBC) types, so run the test to be sure.

## Development

Tests connect to PostgreSQL 17. Tables are created inside the test transaction and rolled back at the end, so nothing is left in the database.

1. Prepare PostgreSQL 17. The database, user, and password are all `postgres`. With Docker, you can start one like this:

   ```bash
   docker run -d --name dbjigu-postgres -p 5432:5432 -e POSTGRES_PASSWORD=postgres postgres:17
   ```

2. Run the tests. The default URL is `jdbc:postgresql://192.168.64.2:5432/postgres` (the maintainer's environment), so pass your own with `-P`:

   ```bash
   ./gradlew build -Pdbjigu.url=jdbc:postgresql://localhost:5432/postgres
   ```

   You can also change the user and password with `-Pdbjigu.user=...` and `-Pdbjigu.password=...`. To run only some tests, use `--tests` (for example `./gradlew test --tests '*OrderServiceTest*'`).

The fixture editor's tests need only Node.js 22 or later (and Chrome for the UI tests, which are skipped without it):

```bash
node --test tools/test/*.test.js
```

GitHub Actions runs the same tests on every pull request and every push to `main` ([.github/workflows/build.yml](.github/workflows/build.yml)).

## License

[MIT License](LICENSE). If you copy `DbJigu.java`, keep the copyright notice at the top of the file.
