# DbJigu Test Writing Guide

English | [日本語](guide.ja.md)

This guide shows how to write tests that use the database with DbJigu (DB治具). It assumes you know the basics of JUnit 5 and Spring Boot.

Every example in this guide is a real, passing test. The code is in [OrderServiceTest](../src/test/java/com/prism7/testtoolbelt/example/OrderServiceTest.java) and the fixtures are in [data/order](../src/test/resources/data/order). Test method names and fixture data in this repository are written in Japanese, so they appear in Japanese here too.

## Contents

1. [Introduction](#1-introduction)
2. [Writing tests as Given-When-Then](#2-writing-tests-as-given-when-then)
3. [Fixture format](#3-fixture-format)
4. [Learning by use case](#4-learning-by-use-case)
5. [How verification works](#5-how-verification-works)
6. [Tips](#6-tips)
7. [Limitations and troubleshooting](#7-limitations-and-troubleshooting)

---

## 1. Introduction

### 1.1 Setup

1. Copy [`DbJigu.java`](../src/test/java/com/prism7/testtoolbelt/DbJigu.java) into your project's test sources (for example `src/test/java/<your package>/utils`).
2. Change the `package` line at the top of the file to match where you copied it.
3. Make sure the JDBC driver for your database is a test dependency.

All you need is Java 17 or later and a JDBC driver. The version at the top of the Javadoc (for example `v2.3.0`) tells you which version you copied.

### 1.2 API at a glance

| Method | What it does | When the result is not as expected |
|---|---|---|
| `importFrom(file, patterns...)` | Inserts the rows of the file into the DB | — (returns the number of rows inserted) |
| `assertExists(file, patterns...)` | Verifies that each row of the file **exists** in the DB | `AssertionError` |
| `assertNotExists(file, patterns...)` | Verifies that each row of the file does **not** exist in the DB | `AssertionError` |
| `verifyExists(file, patterns...)` | Same check as `assertExists` | Returns the number of rows that were not found |
| `verifyNotExists(file, patterns...)` | Same check as `assertNotExists` | Returns the number of rows that were found |

- Usually `importFrom` and `assert*` are all you need. Use `verify*` when you want to check how many rows differ.
- `patterns` is optional. Without it, every row is used. You can pass numbers (`1`) or names (`"gold"`) ([3.4](#34-pattern-numbers)).
- A mistake in a file or an SQL error throws an exception (such as `IllegalArgumentException`), not an `AssertionError`. A mistake never lets a test pass silently.
- DbJigu never commits, rolls back, or closes the `Connection` you give it.

---

## 2. Writing tests as Given-When-Then

A test with DbJigu has three steps.

| Step | What you do | DbJigu |
|---|---|---|
| **Given** | Prepare the data the test needs | `jigu.importFrom("given.txt")` |
| **When** | Run the code under test | — |
| **Then** | Verify that the DB is in the expected state | `jigu.assertExists("expected.txt")` |

### 2.1 Basic example (JUnit + JDBC)

This test checks that restocking a product increases its stock.

```java
class OrderServiceTest {

    private static final String DATA = "src/test/resources/data/order/";

    private Connection connection;
    private DbJigu jigu;
    private OrderService service;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(url, user, password);
        connection.setAutoCommit(false);          // so the test can roll back at the end
        // (creating the tables is omitted)
        jigu = new DbJigu(connection);
        service = new OrderService(connection);
    }

    @AfterEach
    void tearDown() throws SQLException {
        connection.rollback();                    // DbJigu does not roll back, so the test does
        connection.close();
    }

    @Test
    void 入荷すると在庫が増える() throws SQLException {   // restocking increases the stock
        // Given
        jigu.importFrom(DATA + "restock/given.txt");

        // When
        service.restock("P002", 30);

        // Then
        jigu.assertExists(DATA + "restock/expected.txt");
    }
}
```

[restock/given.txt](../src/test/resources/data/order/restock/given.txt) (data to insert)

```
[product]
{product_code|product_name|unit_price|stock}
P001|ボールペン|120|100
P002|ノート|300|50
```

[restock/expected.txt](../src/test/resources/data/order/restock/expected.txt) (expected state)

```
[product]
{product_code|stock}
P001|100
P002|80
```

The expected file only needs the columns you want to check. Columns you leave out (such as `product_name`) are not compared.

### 2.2 Spring example

In a `@Transactional` Spring Boot + Spring Data JPA test, follow these two rules.

```java
@SpringBootTest
@Transactional
class DbJiguSpringExampleTest {

    @Autowired DataSource dataSource;
    @Autowired MemberRepository memberRepository;
    @PersistenceContext EntityManager entityManager;
    DbJigu jigu;

    @BeforeEach
    void setUp() {
        // (1) Pass the Connection that takes part in the test transaction
        jigu = new DbJigu(DataSourceUtils.getConnection(dataSource));
        jigu.importFrom(DATA + "setup.txt");
    }

    @Test
    void JPAで登録した結果を検証する() {   // verify what JPA saved
        memberRepository.save(new Member(BigDecimal.valueOf(10), "bob"));
        entityManager.flush();     // (2) Write JPA changes to the DB before verifying

        jigu.assertExists(DATA + "expected_registered.txt");
    }
}
```

1. **Use `DataSourceUtils.getConnection(dataSource)`.** `dataSource.getConnection()` returns a different Connection, so the inserted data is committed and stays in the DB.
2. **Call `entityManager.flush()` before verifying.** JPA does not write changes to the DB until it flushes, so verification would not find them.

A working example is [DbJiguSpringExampleTest](../src/test/java/com/prism7/testtoolbelt/spring/DbJiguSpringExampleTest.java).

---

## 3. Fixture format

### 3.1 Basics

```
[TableName]
{column1|column2|column3}
value1|value2|value3
value1|<null>|value3
```

| Element | Meaning |
|---|---|
| `[TableName]` | Starts a table. A file can have several tables, and the same table can appear twice. |
| `{column1\|column2}` | Column names. Write them on the line after `[TableName]`. |
| `value1\|value2` | Each line is one row in the DB. |
| `<null>` | NULL |
| Blank line | Ignored. Use blank lines to separate tables. |
| `// comment` | A line starting with `//` is ignored ([Comments](#comments)). |

- Files are UTF-8 and the separator is `|`. Spaces around each value are removed.
- Values are converted to the column's type (read from the DB).

| Column type | Examples |
|---|---|
| Number | `120` `123.45` `2.5e-3` (a decimal in an integer-type column such as `INTEGER` is an error; for `NUMERIC(10)` or Oracle `NUMBER` columns, the database rounds it) |
| Date | `2024-04-01` `2024/04/01` |
| Timestamp | `2024-04-01 10:00` `2024-04-01 10:00:30` `2024-04-01 10:00:30.123` |
| Time | `10:00` `10:00:30` |
| Boolean | `true` `false` (also `t` `f` `1` `0` `yes` `no` `y` `n`) |

- When importing, columns you leave out get the DB default (NULL if there is no default).
- When verifying, columns you leave out are not compared.

### 3.2 Column marks (verification only)

When verifying, a mark at the end of a column name changes the comparison from exact match to another condition.

| Mark | Condition (DB value on the left) | When to use it |
|---|---|---|
| none | DB value = file value | Normal comparison |
| `@` | Not compared | Columns whose value you cannot know, such as the current time or a generated ID |
| `>` `>=` `<` `<=` | DB value > file value, and so on | "Updated after this time", "falls in this range" |
| `!=` | DB value ≠ file value (rows where the DB value is NULL also match) | "The value changed" |
| `%` | DB value starts with the file value (prefix match) | Strings followed by a timestamp or similar |

Combined with `<null>`:

| Written as | Condition |
|---|---|
| `<null>` in a `!=` column | `IS NOT NULL` (the column has a value) |
| `<null>` in a column with any other mark | `IS NULL` |

- A column can have only one mark.
- You can write the same column twice, so `{ordered_at>=|ordered_at<}` specifies a range.
- You can write anything in an `@` column. This guide uses `-`. If you also import the same file, that value is inserted.
- `%` works only on string columns. `%` and `_` in the value are treated as literal characters.
- The order of strings depends on the DB collation, so use `<` and `>` for numbers and timestamps.
- When importing, marks are ignored and the values are inserted as written.

### 3.3 Example import and verify files

These are from the test that places an order (details in [4.2](#42-verifying)).

[place/given.txt](../src/test/resources/data/order/place/given.txt) (import)

```
[customer]
{customer_id|customer_name|customer_rank}
C001|山田 太郎|REGULAR

[product]
{product_code|product_name|unit_price|stock}
P001|ボールペン|120|100
P002|ノート|300|50
```

[place/expected.txt](../src/test/resources/data/order/place/expected.txt) (verify)

```
[orders]
{order_no|customer_id|status|total_amount|note|shipped_at|ordered_at@|updated_at@}
A001|C001|RECEIVED|960|<null>|<null>|-|-

[order_item]
{order_no|line_no|product_code|quantity|amount}
A001|1|P001|3|360
A001|2|P002|2|600

[product]
{product_code|stock}
P001|97
P002|48
```

### 3.4 Pattern numbers

If the column list starts with `#`, the first field of each row is that row's **pattern numbers**. You can put the data for several test cases in one file and switch between them by pattern number.

```
[customer]
{#|customer_id|customer_name|customer_rank}
1|C001|山田 太郎|GOLD
2|C001|山田 太郎|SILVER
3|C001|山田 太郎|REGULAR

[product]
{product_code|product_name|unit_price|stock}
P002|ノート|300|50
```

| Pattern field | Meaning |
|---|---|
| `1` | Row for pattern 1 |
| `1,3` | Row for patterns 1 and 3 |
| `gold` | Row for the pattern named gold |
| `gold,silver` | Row for the patterns gold and silver |
| empty (the line starts with `\|`) | Row shared by every pattern |
| Table without `#` | Always used |

Calling `jigu.importFrom(file, 1)` uses only the rows for pattern 1 and the shared rows. In the example above, it inserts customer `C001` (GOLD) and product `P002`.

- Without pattern numbers, every row is used.
- Passing a pattern number that no row has is an error, so a typo in the number can never make a test pass without checking anything.
- You can pass several patterns, as in `jigu.importFrom(file, 1, 3)`.
- You can write names instead of numbers. Pass a name as a string, as in `jigu.importFrom(file, "gold")` ([4.2](#naming-patterns)).
- Names ignore case (`Gold` and `gold` are the same pattern). Anything that reads as a number is compared as a number (`01`, `+1`, and a full-width `１` are the same pattern as `1`).
- Names can't contain `|` or `,`. Spaces around them are removed.
- A typo such as `1、2` or `1.2` is read as one name, not as patterns 1 and 2. The fixture editor warns about it.

---

## 4. Learning by use case

### 4.0 The example: order management

This guide uses order management as its example. The tables are in [schema/order.sql](../src/test/resources/schema/order.sql).

| Table | Content | Main columns |
|---|---|---|
| `customer` | Customers | `customer_id`, `customer_rank` (GOLD / SILVER / REGULAR) |
| `product` | Products | `product_code`, `unit_price`, `stock` |
| `orders` | Orders | `order_no`, `status` (RECEIVED / SHIPPED / CANCELED), `total_amount`, `note`, `ordered_at`, `shipped_at`, `updated_at` |
| `order_item` | Order lines | `order_no`, `line_no`, `product_code`, `quantity`, `amount` |

`orders` references `customer`, and `order_item` references `orders` and `product` (foreign keys).

[OrderService](../src/test/java/com/prism7/testtoolbelt/example/OrderService.java), the code under test, does the following.

| Method | Business operation |
|---|---|
| `restock` | Restock. Increases the stock. |
| `placeOrder` | Place an order. Inserts the order and its lines and decreases the stock. Applies a discount by customer rank (GOLD 10%, SILVER 5%). Inserts nothing if the stock is not enough. |
| `cancel` | Cancel. Only received orders can be canceled. Deletes the order lines, returns the stock, and records "キャンセル: reason (time)" in the note. |
| `shipOrderedBefore` | Ship. Marks every received order placed before the cutoff time as shipped. |

### 4.1 Importing

#### A single table

For a single table, write one `[TableName]`.

```
[product]
{product_code|product_name|unit_price|stock}
P001|ボールペン|120|100
P002|ノート|300|50
```

#### Several tables

[cancel/given.txt](../src/test/resources/data/order/cancel/given.txt) is the starting data for the cancel test.

```
// 顧客
[customer]
{customer_id|customer_name|customer_rank}
C001|山田 太郎|REGULAR

// 商品
[product]
{product_code|product_name|unit_price|stock}
P001|ボールペン|120|96
P002|ノート|300|48

// 受注
[orders]
{order_no|customer_id|status|total_amount|note|ordered_at|shipped_at|updated_at}
A001|C001|RECEIVED|960|<null>|2024-04-01 10:00|<null>|2024-04-01 10:00
A002|C001|SHIPPED|120|<null>|2024-04-01 11:00|2024-04-02 09:00|2024-04-02 09:00

// 受注明細
[order_item]
{order_no|line_no|product_code|quantity|amount}
A001|1|P001|3|360
A001|2|P002|2|600
A002|1|P001|1|120
```

Lines starting with `//` are comments. Writing the table's name in your own language makes the file easier to read.

Tables are **inserted in the order they are written**. With foreign keys, write the referenced (parent) table first. Here the order is `customer` → `product` → `orders` → `order_item`.

#### Pattern numbers

[place_by_rank/given.txt](../src/test/resources/data/order/place_by_rank/given.txt) holds three cases that differ only in the customer rank (the example from [3.4](#34-pattern-numbers)).

```
[customer]
{#|customer_id|customer_name|customer_rank}
1|C001|山田 太郎|GOLD
2|C001|山田 太郎|SILVER
3|C001|山田 太郎|REGULAR

[product]
{product_code|product_name|unit_price|stock}
P002|ノート|300|50
```

All three rows use customer ID `C001`, but only one pattern is inserted at a time, so the primary key never collides. Write only the values that change per pattern, and keep the data that does not change (`product`) shared.

### 4.2 Verifying

#### A single table

[restock/expected.txt](../src/test/resources/data/order/restock/expected.txt) checks that the stock of the restocked product `P002` went up and that the other product, `P001`, did not change.

```
[product]
{product_code|stock}
P001|100
P002|80
```

#### Several tables

Placing an order changes three tables: orders, order lines, and stock.

```java
@Test
void 受注と明細が登録され在庫が減る() throws SQLException {   // order and lines are inserted, stock goes down
    // Given
    jigu.importFrom(DATA + "place/given.txt");

    // When
    service.placeOrder("A001", "C001", List.of(new OrderLine("P001", 3), new OrderLine("P002", 2)));

    // Then
    jigu.assertExists(DATA + "place/expected.txt");
    jigu.assertNotExists(DATA + "place/not_exists.txt");
}
```

[place/expected.txt](../src/test/resources/data/order/place/expected.txt) is the file from [3.3](#33-example-import-and-verify-files).

[place/not_exists.txt](../src/test/resources/data/order/place/not_exists.txt) checks that no extra rows were inserted ([5.2](#52-extra-rows-are-not-detected)).

```
[orders]
{order_no!=}
A001

[order_item]
{line_no>}
2
```

- `orders` has no order other than `A001`.
- `order_item` has no line numbered 3 or higher.

#### Pattern numbers (`@ParameterizedTest` + `@CsvSource`)

Pass the pattern number as a test argument, and one test method covers several cases.

```java
@ParameterizedTest(name = "パターン{0}: {1}", quoteTextArguments = false)
@CsvSource(delimiter = '|', textBlock = """
        1 | GOLD は10%引き
        2 | SILVER は5%引き
        3 | REGULAR は割引なし
        """)
void 会員ランクに応じて割り引く(int pattern, String description) throws SQLException {   // discount by customer rank
    // Given
    jigu.importFrom(DATA + "place_by_rank/given.txt", pattern);

    // When
    service.placeOrder("A001", "C001", List.of(new OrderLine("P002", 10)));

    // Then
    jigu.assertExists(DATA + "place_by_rank/expected.txt", pattern);
}
```

[place_by_rank/expected.txt](../src/test/resources/data/order/place_by_rank/expected.txt)

```
[orders]
{#|order_no|customer_id|total_amount}
1|A001|C001|2700
2|A001|C001|2850
3|A001|C001|3000

[product]
{#|product_code|stock}
|P002|40
```

- Pass the **same pattern number** to import and to verify.
- The stock (`P002` at 40) is the same in every pattern, so its row has an empty pattern field and is shared.
- `description` is only there for the test name. The result shows names like "パターン1: GOLD は10%引き" (pattern 1: GOLD gets 10% off), so you can see which case failed.

#### Naming patterns

You can write names instead of pattern numbers. Then the file itself shows which case each row is for, and you no longer need an argument (`description`) just for the test name. Here is the same test written with names.

[place_by_rank_named/given.txt](../src/test/resources/data/order/place_by_rank_named/given.txt)

```
[customer]
{#|customer_id|customer_name|customer_rank}
gold|C001|山田 太郎|GOLD
silver|C001|山田 太郎|SILVER
regular|C001|山田 太郎|REGULAR

[product]
{product_code|product_name|unit_price|stock}
P002|ノート|300|50
```

[place_by_rank_named/expected.txt](../src/test/resources/data/order/place_by_rank_named/expected.txt)

```
[orders]
{#|order_no|customer_id|total_amount}
gold|A001|C001|2700
silver|A001|C001|2850
regular|A001|C001|3000

[product]
{#|product_code|stock}
|P002|40
```

```java
@ParameterizedTest(name = "{0}", quoteTextArguments = false)
@ValueSource(strings = {"gold", "silver", "regular"})
void 会員ランクに応じて割り引く_名前で指定(String rank) throws SQLException {
    // Given
    jigu.importFrom(DATA + "place_by_rank_named/given.txt", rank);

    // When
    service.placeOrder("A001", "C001", List.of(new OrderLine("P002", 10)));

    // Then
    jigu.assertExists(DATA + "place_by_rank_named/expected.txt", rank);
}
```

- Pass names as strings. You can pass several, as in `jigu.importFrom(file, "gold", "silver")`.
- The test result shows the name you passed, such as `gold`.
- A misspelled name (such as `"glod"`) is a pattern that no row has, so it is an error.

#### Using column marks

Use column marks ([3.2](#32-column-marks-verification-only)) for columns whose value changes on every run, or that you want to check against a range.

**`@`: not compared** ([place/expected.txt](../src/test/resources/data/order/place/expected.txt))

```
[orders]
{order_no|customer_id|status|total_amount|note|shipped_at|ordered_at@|updated_at@}
A001|C001|RECEIVED|960|<null>|<null>|-|-
```

The order time and update time are the time of the run, so they are not compared. Leaving the columns out gives the same result, but writing them with `@` tells the reader that "these get a value, but we don't compare it".

**`>` and `%`: the update time moved forward, and the note starts with a fixed text** ([cancel/expected.txt](../src/test/resources/data/order/cancel/expected.txt))

```
[orders]
{order_no|status|note%|updated_at>}
A001|CANCELED|キャンセル: お客様都合|2024-04-01 10:00

[order_item]
{order_no|line_no}
A002|1

[product]
{product_code|stock}
P001|99
P002|50
```

- `updated_at>`: the update time is later than the inserted value (`2024-04-01 10:00`).
- `note%`: the note starts with "キャンセル: お客様都合" (canceled: customer's request). The actual value has a time after it, like "キャンセル: お客様都合（2026-10-09 12:34）", so a prefix match is used.

To check that the lines of the canceled order were deleted, use [cancel/not_exists.txt](../src/test/resources/data/order/cancel/not_exists.txt) with `assertNotExists`.

```
[order_item]
{order_no}
A001
```

**`>=` `<` `!=`: a range, and "has a value"** ([ship/expected.txt](../src/test/resources/data/order/ship/expected.txt))

The shipping test inserts the following data ([ship/given.txt](../src/test/resources/data/order/ship/given.txt)) and ships with the cutoff time `2024-04-10 00:00`.

```
[customer]
{customer_id|customer_name|customer_rank}
C001|山田 太郎|REGULAR

[orders]
{order_no|customer_id|status|total_amount|ordered_at|shipped_at|updated_at}
A001|C001|RECEIVED|120|2024-04-09 23:59:59|<null>|2024-04-09 23:59:59
A002|C001|RECEIVED|120|2024-04-10 00:00|<null>|2024-04-10 00:00
A003|C001|CANCELED|120|2024-04-08 10:00|<null>|2024-04-08 12:00
A004|C001|SHIPPED|120|2024-04-05 10:00|2024-04-06 15:00|2024-04-06 15:00
```

The `note` column is not written, so it is inserted as NULL (the DB default). The expected state after shipping is:

```
[orders]
{status|ordered_at>=|ordered_at<|shipped_at!=}
SHIPPED|2024-04-09|2024-04-10|<null>

[orders]
{order_no|status|shipped_at}
A002|RECEIVED|<null>
A003|CANCELED|<null>
A004|SHIPPED|2024-04-06 15:00
```

- First table: the order received on April 9 (from `2024-04-09` up to but not including `2024-04-10`) is shipped and has a shipping time (`!=` with `<null>` means `IS NOT NULL`).
- Second table: `A002`, received exactly at the cutoff, `A003`, which was canceled, and `A004`, which was already shipped, are unchanged.
- The same table is written twice to keep checks with different conditions apart.

[ship/not_exists.txt](../src/test/resources/data/order/ship/not_exists.txt) checks that no order received before the cutoff is still in the received state.

```
[orders]
{status|ordered_at<}
RECEIVED|2024-04-10
```

#### Verifying that nothing changed

For an operation that fails, reuse the Given file in Then to check that the DB did not change.

```java
@Test
void 在庫が足りなければ何も登録しない() {   // nothing is inserted when the stock is short
    // Given
    jigu.importFrom(DATA + "place/given.txt");

    // When
    assertThrows(IllegalStateException.class,
            () -> service.placeOrder("A001", "C001", List.of(new OrderLine("P002", 51))));

    // Then: the data is as inserted, and there are no orders or order lines
    jigu.assertExists(DATA + "place/given.txt");
    jigu.assertNotExists(DATA + "place/out_of_stock_not_exists.txt");
}
```

[place/out_of_stock_not_exists.txt](../src/test/resources/data/order/place/out_of_stock_not_exists.txt)

```
[orders]
{order_no!=}
<null>

[order_item]
{order_no!=}
<null>
```

`!=` with `<null>` means `IS NOT NULL`. On a column that is never NULL, such as a primary key, it matches any row. Used with `assertNotExists`, it checks that **the table is empty**.

---

## 5. How verification works

### 5.1 Each row is checked for existence

For each row of the file, `assertExists` runs an SQL query like the following and checks whether a matching row **exists** in the DB.

```sql
SELECT 1 FROM orders WHERE order_no = ? AND status = ? AND note LIKE ? ESCAPE '!' AND updated_at > ?
```

### 5.2 Extra rows are not detected

Because of this, `assertExists` does not check:

- that the DB has no **extra rows** that are not in the file
- the **number of rows** in a table
- that two rows of the file match **different** rows in the DB (it passes even if both match the same row)

When it matters that no extra rows were created, add `assertNotExists`.

| What to check | File | Method |
|---|---|---|
| No order other than `A001` | `{order_no!=}` / `A001` | `assertNotExists` |
| No line numbered 3 or higher | `{line_no>}` / `2` | `assertNotExists` |
| No orders at all | `{order_no!=}` / `<null>` | `assertNotExists` |

To check an exact count, use `assertEquals` on something like the return value of `OrderService.shipOrderedBefore` or Spring Data's `count()`.

### 5.3 Output on failure

Verification prints a result for each row of the file to the console (`○` means as expected, `×` means not as expected).

```
○: src/test/resources/data/order/cancel/expected.txt:3 [orders] A001|CANCELED|キャンセル: お客様都合|2024-04-01 10:00
○: src/test/resources/data/order/cancel/expected.txt:7 [order_item] A002|1
×: src/test/resources/data/order/cancel/expected.txt:11 [product] P001|98
○: src/test/resources/data/order/cancel/expected.txt:12 [product] P002|50
```

When `assertExists` fails, the `×` rows go into the `AssertionError` message. The `file:line` tells you which row did not match. (The message text is Japanese; it means "rows not found in the DB".)

```
java.lang.AssertionError: DBに存在しない行があります: src/test/resources/data/order/cancel/expected.txt
src/test/resources/data/order/cancel/expected.txt:11 [product] P001|98
```

---

## 6. Tips

### Where to put files and how to name them

Make a directory per business operation and name files by their role.

```
src/test/resources/data/order/
├── cancel/
│   ├── given.txt        … Given: data to insert
│   ├── expected.txt     … Then: rows that must exist (assertExists)
│   └── not_exists.txt   … Then: rows that must not exist (assertNotExists)
└── place/
    └── ...
```

File paths are relative to the working directory of the test run (the project root for Gradle and Maven).

### Import order and foreign keys

Tables are inserted in the order they appear in the file. Write parent tables first.

### Generated key columns

In PostgreSQL, inserting explicit values into an `IDENTITY` or `serial` column does not advance its sequence. If the code under test then generates a key, it can collide with the value you inserted. Either leave the generated column out of the fixture and let the DB generate it, or use large values that the code under test will not generate.

### Columns that get the current time

- You don't need to compare it → `@`
- You want to check that it was updated → compare with the inserted value using `>`

### Comments

Lines starting with `//` are ignored for both import and verification. Leading spaces are ignored, so you can indent them.

- Write a table's name in your own language, or what the data is for
- Temporarily leave a row out of import and verification (`// A001|1|P001|3|360`)

A comment cannot start in the middle of a line. The `//` in `A001|https://example.com` is part of the value. If you comment out every row to verify, you get an error, so a test never passes without verifying anything.

### Fixture editor

Open [tools/fixture-editor.html](../tools/fixture-editor.html) in a browser to edit fixtures as tables.

- It finds the mistakes that DbJigu throws an exception for, such as a row whose number of values doesn't match the header or a pattern number that isn't a number, before you run the test.
- When you save with "桁揃え" (align) on, the `|` characters are lined up. Fields are trimmed, so the meaning doesn't change. However, when verification fails, the message shows the row as written, including the padding.
- When you choose a pattern number, only the rows imported or verified for that pattern (its own rows and the common rows) are shown at full strength.
- You can copy a range from Excel and paste it into a cell.

### Empty strings and NULL

`<null>` is NULL, and an empty field (the middle of `A001||C001`) is an empty string. An empty field at the end of a line is also an empty string. Note that Oracle treats an empty string as NULL (this is not tested in this repository).

### CHAR columns

CHAR columns are compared without trailing spaces. Writing `abc` matches `abc       ` stored in a `CHAR(10)` column.

### When the data is not rolled back

DbJigu does not delete the data it inserts. The rollback of the test transaction removes it. If the code under test runs outside the test transaction, the data can stay in the DB, or the code may not see the inserted data:

- the code under test uses `@Transactional(propagation = REQUIRES_NEW)`
- the code under test updates the DB in another thread (asynchronous processing)
- the test calls the code over HTTP with `@SpringBootTest(webEnvironment = RANDOM_PORT)`

In these cases, delete the data yourself after the test.

---

## 7. Limitations and troubleshooting

### 7.1 Values you cannot write

| What you cannot write | Why |
|---|---|
| A value containing `\|` | It is the separator, and there is no way to escape it |
| A value with leading or trailing spaces | Spaces around values are removed |
| The string `<null>` | It means NULL |
| A value that puts `[` or `{` at the start of a line | The line is read as a table name or column list (under discussion in [#12](https://github.com/raindrop-aqua/java-test-toolbelt/issues/12)) |
| A value that starts a line with `//` | The line is read as a comment |
| A comment in the middle of a line | Only a `//` at the start of a line makes a comment. A line starting with `#` is read as a data row |

### 7.2 Error messages

Error messages are in Japanese and start with `file:line`.

| Message | Cause and fix |
|---|---|
| `列数(n)と値の数(m)が一致しません` | The number of values does not match the number of columns. Count the `\|`. In a table with pattern numbers, also check that you did not forget the leading `\|`. |
| `パターン番号 [n] の行がありません` | No row in the file has the pattern number you passed. |
| `検証対象の行がありません` | There are no rows to verify. The file has column names but no data rows. |
| `値を列の型に変換できません` | The value does not fit the column type. Check how dates and numbers are written ([3.1](#31-basics)). |
| `列の型を取得できません` | The table name or a column name is wrong. |
| `投入に失敗しました` | A duplicate primary key, a foreign key violation, a NOT NULL violation, or similar. The exception includes the SQL error. |
| `[テーブル名] の後に {列名1\|列名2} を記述してください` | The line after the table name is not a column list. |
| `データの前に {列名1\|列名2} を記述してください` | There is data before the column list. |
| `投入では同じ列を2回書けません` | A file that repeats a column (for a range) is being used for import. |
| `% は文字列の列だけに使えます` | `%` is on a number or date column. |
| `列名の末尾の記号は1つだけ付けられます` | A column has two marks, as in `amount@<`. |
| `LOB 列は大小比較できません` | `<` `<=` `>` or `>=` is on a LOB column such as a CLOB. |
| `空のパターン番号があります` | The pattern field has an empty item, as in `1,,2` or `gold,`. |
| `パターン番号に使えない値です` | A pattern passed to the method is empty or `null`, or contains `,` or `\|`. |
| `テーブル名は [テーブル名] の形式で記述してください` | A line starting with `[` does not end with `]`, or the table name is empty (`[]` or `[ ]`). |
| `列名は {列名1\|列名2} の形式で記述してください` | A line starting with `{` does not end with `}`. |
| `空の列名があります` / `列名がありません` | The column list has an empty name (as in `{a\|\|b}`) or no names at all. |
| `検証に失敗しました` | The verification SQL failed. Check that no ordering mark such as `>` is on a column type that cannot be compared. The exception includes the SQL error. |
| `ファイルを読み込めません` | The file path is wrong. Paths are relative to the project root. |

### 7.3 It should match but fails

| Check | Fix |
|---|---|
| Did you flush JPA changes? | Call `entityManager.flush()` before verifying |
| Are you using the same Connection? | In Spring, use `DataSourceUtils.getConnection(dataSource)` |
| Do the seconds and milliseconds of timestamps match? | Write the needed precision, or compare with a `>=` and `<` range |
| Are you comparing a column that gets the current time? | Add `@` |
| Did you pass the same pattern number to import and verify? | Pass the same number |
