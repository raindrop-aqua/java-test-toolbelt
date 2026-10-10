# DbJigu テスト実装ガイド

[English](guide.md) | 日本語

DbJigu（DB治具）を使って、DB を扱うテストを書くためのガイドです。JUnit 5 と Spring Boot の基本を知っている人を想定しています。

このガイドに載せている例は、すべて実際に動くテストです。コードは [OrderServiceTest](../src/test/java/com/prism7/testtoolbelt/example/OrderServiceTest.java)、フィクスチャは [data/order](../src/test/resources/data/order) にあります。

## 目次

1. [はじめに](#1-はじめに)
2. [Given-When-Then で書く](#2-given-when-then-で書く)
3. [フィクスチャの書式](#3-フィクスチャの書式)
4. [ユースケースで学ぶ](#4-ユースケースで学ぶ)
5. [検証の仕組みと注意点](#5-検証の仕組みと注意点)
6. [Tips](#6-tips)
7. [制限事項とトラブルシューティング](#7-制限事項とトラブルシューティング)

---

## 1. はじめに

### 1.1 導入

1. [`DbJigu.java`](../src/test/java/com/prism7/testtoolbelt/DbJigu.java) を、プロジェクトのテストソース（例：`src/test/java/<パッケージ>/utils`）にコピーします。
2. ファイル先頭の `package` を、コピー先のパッケージに書き換えます。
3. 使う DB の JDBC ドライバが、テストの依存関係に入っていることを確認します。

必要なのは Java 17 以上と JDBC ドライバだけです。Javadoc の先頭に書かれたバージョン（例：`v2.3.0`）で、どの版をコピーしたか分かります。

### 1.2 API 早見表

| メソッド | 何をするか | 期待と違うとき |
|---|---|---|
| `importFrom(file, patterns...)` | ファイルの行を DB に投入する | — （投入した件数を返す） |
| `assertExists(file, patterns...)` | ファイルの各行が DB に**ある**ことを検証する | `AssertionError` |
| `assertNotExists(file, patterns...)` | ファイルの各行が DB に**ない**ことを検証する | `AssertionError` |
| `verifyExists(file, patterns...)` | `assertExists` と同じ判定をする | 一致しなかった行の件数を返す |
| `verifyNotExists(file, patterns...)` | `assertNotExists` と同じ判定をする | 一致した行の件数を返す |

- 通常は `importFrom` と `assert*` だけで足ります。`verify*` は「何件違うか」を確かめたいときに使います。
- `patterns` は省略できます。省略すると全行が対象になります。数値（`1`）でも名前（`"gold"`）でも渡せます（[3.4](#34-パターン番号)）。
- ファイルの書き間違いや SQL のエラーは、`AssertionError` ではなく例外（`IllegalArgumentException` など）になります。間違いを見過ごして成功することはありません。
- DbJigu は、渡された `Connection` のコミット、ロールバック、クローズを行いません。

---

## 2. Given-When-Then で書く

DbJigu を使ったテストは、次の3段で書きます。

| 段 | やること | DbJigu |
|---|---|---|
| **Given** | テストの前提となるデータを用意する | `jigu.importFrom("given.txt")` |
| **When** | テスト対象を実行する | — |
| **Then** | DB が期待どおりの状態になったか検証する | `jigu.assertExists("expected.txt")` |

### 2.1 基本の例（JUnit + JDBC）

商品を入荷すると在庫が増えることを確かめるテストです。

```java
class OrderServiceTest {

    private static final String DATA = "src/test/resources/data/order/";

    private Connection connection;
    private DbJigu jigu;
    private OrderService service;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(url, user, password);
        connection.setAutoCommit(false);          // テスト終了時にロールバックするため
        // （テーブルの作成は省略）
        jigu = new DbJigu(connection);
        service = new OrderService(connection);
    }

    @AfterEach
    void tearDown() throws SQLException {
        connection.rollback();                    // DbJigu はロールバックしないので、テスト側で行う
        connection.close();
    }

    @Test
    void 入荷すると在庫が増える() throws SQLException {
        // Given
        jigu.importFrom(DATA + "restock/given.txt");

        // When
        service.restock("P002", 30);

        // Then
        jigu.assertExists(DATA + "restock/expected.txt");
    }
}
```

[restock/given.txt](../src/test/resources/data/order/restock/given.txt)（投入するデータ）

```
[product]
{product_code|product_name|unit_price|stock}
P001|ボールペン|120|100
P002|ノート|300|50
```

[restock/expected.txt](../src/test/resources/data/order/restock/expected.txt)（期待する状態）

```
[product]
{product_code|stock}
P001|100
P002|80
```

期待する状態のファイルには、確かめたい列だけを書けば十分です。書かなかった列（`product_name` など）は比較しません。

### 2.2 Spring の例

Spring Boot + Spring Data JPA の `@Transactional` なテストでは、次の2点を守ります。

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
        // ① テストのトランザクションに参加している Connection を渡す
        jigu = new DbJigu(DataSourceUtils.getConnection(dataSource));
        jigu.importFrom(DATA + "setup.txt");
    }

    @Test
    void JPAで登録した結果を検証する() {
        memberRepository.save(new Member(BigDecimal.valueOf(10), "bob"));
        entityManager.flush();     // ② JPA の変更を DB に書き出してから検証する

        jigu.assertExists(DATA + "expected_registered.txt");
    }
}
```

1. **`DataSourceUtils.getConnection(dataSource)` を使う。** `dataSource.getConnection()` は別の Connection になるので、投入したデータがコミットされて残ります。
2. **検証の前に `entityManager.flush()` を呼ぶ。** JPA の変更は flush するまで DB に書かれないので、検証で見つかりません。

動く例は [DbJiguSpringExampleTest](../src/test/java/com/prism7/testtoolbelt/spring/DbJiguSpringExampleTest.java) にあります。

---

## 3. フィクスチャの書式

### 3.1 基本形

```
[テーブル名]
{列名1|列名2|列名3}
値1|値2|値3
値1|<null>|値3
```

| 要素 | 意味 |
|---|---|
| `[テーブル名]` | テーブルの始まり。1ファイルに複数書けます。同じテーブルを2回書いてもかまいません。 |
| `{列名1\|列名2}` | 列名。`[テーブル名]` の次の行に書きます。 |
| `値1\|値2` | 1行が DB の1行に対応します。 |
| `<null>` | NULL |
| 空行 | 無視します。テーブルの区切りを見やすくするのに使えます。 |
| `// コメント` | `//` で始まる行は無視します（[コメント](#コメント)）。 |

- 文字コードは UTF-8、区切り文字は `|` です。値の前後の空白は取り除きます。
- 値は列の型（DB から取得）に合わせて変換します。

| 列の型 | 書き方の例 |
|---|---|
| 数値 | `120` `123.45` `2.5e-3`（`INTEGER` などの整数型の列に小数を書くとエラー。`NUMERIC(10)` や Oracle の `NUMBER` の列では DB が丸める） |
| 日付 | `2024-04-01` `2024/04/01` |
| 日時 | `2024-04-01 10:00` `2024-04-01 10:00:30` `2024-04-01 10:00:30.123` |
| 時刻 | `10:00` `10:00:30` |
| 真偽値 | `true` `false`（`t` `f` `1` `0` `yes` `no` `y` `n` も可） |

- 投入では、書かなかった列は DB の既定値（既定値が無ければ NULL）になります。
- 検証では、書かなかった列は比較しません。

### 3.2 列名の記号（検証のとき）

検証のときは、列名の末尾に記号を付けると、完全一致以外の条件で比較できます。

| 記号 | 条件（左が DB の値） | 使いどころ |
|---|---|---|
| なし | DBの値 = ファイルの値 | ふつうの比較 |
| `@` | 比較しない | 実行時刻や採番値など、値が決まらない列 |
| `>` `>=` `<` `<=` | DBの値 > ファイルの値 など | 「この日時より後に更新された」「この範囲に入る」 |
| `!=` | DBの値 ≠ ファイルの値（DB の値が NULL の行も含む） | 「値が変わった」 |
| `%` | DBの値がファイルの値で始まる（前方一致） | 後ろに日時などが付く文字列 |

`<null>` と組み合わせたときは、次のようになります。

| 書き方 | 条件 |
|---|---|
| `!=` の列に `<null>` | `IS NOT NULL`（値が入っている） |
| それ以外の記号の列に `<null>` | `IS NULL` |

- 記号は1つの列に1つだけ付けられます。
- 同じ列を2回書けるので、`{ordered_at>=|ordered_at<}` のように範囲を指定できます。
- `@` の列には何を書いてもかまいません。このガイドでは `-` と書いています。ただし、そのファイルを投入にも使うと、書いた値が投入されます。
- `%` は文字列の列だけに使えます。値に含まれる `%` と `_` は文字そのものとして扱います。
- 文字列の大小は DB の照合順序で決まるため、`<` や `>` は数値と日時に使ってください。
- 投入のときは記号を無視して、値をそのまま投入します。

### 3.3 投入と検証のファイルの例

商品を注文するテストの例です（詳しくは [4.2](#42-検証)）。

[place/given.txt](../src/test/resources/data/order/place/given.txt)（投入）

```
[customer]
{customer_id|customer_name|customer_rank}
C001|山田 太郎|REGULAR

[product]
{product_code|product_name|unit_price|stock}
P001|ボールペン|120|100
P002|ノート|300|50
```

[place/expected.txt](../src/test/resources/data/order/place/expected.txt)（検証）

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

### 3.4 パターン番号

列名の先頭を `#` にすると、各行の先頭の項目がその行の**パターン番号**になります。1つのファイルに複数のテストケースのデータをまとめて書き、パターン番号で切り替えられます。

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

| パターン番号の書き方 | 意味 |
|---|---|
| `1` | パターン1の行 |
| `1,3` | パターン1とパターン3の行 |
| `gold` | パターン gold の行（名前） |
| `gold,silver` | パターン gold とパターン silver の行 |
| 空（行の先頭が `\|`） | 全パターン共通の行 |
| 列名に `#` が無いテーブル | 常に処理する |

`jigu.importFrom(file, 1)` と呼ぶと、パターン1の行と共通の行だけを処理します。上の例では、顧客 `C001`（GOLD）と商品 `P002` を投入します。

- パターン番号を省略すると、全行を処理します。
- どの行にも無いパターン番号を渡すとエラーになります。番号の書き間違いで、何も検証せずに成功することはありません。
- `jigu.importFrom(file, 1, 3)` のように、複数のパターンを渡すこともできます。
- 数値の代わりに名前も書けます。名前は `jigu.importFrom(file, "gold")` のように文字列で渡します（[4.2](#パターン番号に名前を使う)）。
- 名前の大文字と小文字は区別しません（`Gold` と `gold` は同じパターン）。数値として読めるものは数値として比べます（`01`、`+1`、全角の `１` は `1` と同じパターン）。
- 名前には `|` と `,` を使えません。前後の空白は取り除きます。
- `1、2` や `1.2` のような書き間違いは、パターン 1 と 2 ではなく、1つの名前として読まれます。フィクスチャエディタでは警告になります。

---

## 4. ユースケースで学ぶ

### 4.0 題材：受注管理

このガイドでは、受注管理を題材にします。テーブルは [schema/order.sql](../src/test/resources/schema/order.sql) にあります。

| テーブル | 内容 | 主な列 |
|---|---|---|
| `customer` | 顧客 | `customer_id`、`customer_rank`（GOLD / SILVER / REGULAR） |
| `product` | 商品 | `product_code`、`unit_price`、`stock`（在庫） |
| `orders` | 受注 | `order_no`、`status`（RECEIVED / SHIPPED / CANCELED）、`total_amount`、`note`、`ordered_at`、`shipped_at`、`updated_at` |
| `order_item` | 受注明細 | `order_no`、`line_no`、`product_code`、`quantity`、`amount` |

`orders` は `customer` を、`order_item` は `orders` と `product` を参照しています（外部キー）。

テスト対象の [OrderService](../src/test/java/com/prism7/testtoolbelt/example/OrderService.java) は、次の業務を行います。

| メソッド | 業務 |
|---|---|
| `restock` | 入荷する。在庫を増やす。 |
| `placeOrder` | 注文する。受注と明細を登録し、在庫を減らす。会員ランクで割り引く（GOLD 10%、SILVER 5%）。在庫が足りなければ何も登録しない。 |
| `cancel` | キャンセルする。受付中の注文だけが対象。明細を削除して在庫を戻し、備考に「キャンセル: 理由（日時）」を記録する。 |
| `shipOrderedBefore` | 出荷する。締め日時より前に受け付けた受付中の注文を、まとめて出荷済みにする。 |

### 4.1 投入

#### 単一テーブル

1つのテーブルだけを使うなら、`[テーブル名]` は1つです。

```
[product]
{product_code|product_name|unit_price|stock}
P001|ボールペン|120|100
P002|ノート|300|50
```

#### 複数テーブル

[cancel/given.txt](../src/test/resources/data/order/cancel/given.txt) は、キャンセルのテストの前提データです。

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

`//` で始まる行はコメントです。テーブルの日本語名を書いておくと読みやすくなります。

テーブルは**書いた順に投入**します。外部キーがある場合は、参照される側（親）を先に書きます。この例では `customer` → `product` → `orders` → `order_item` の順です。

#### パターン番号

[place_by_rank/given.txt](../src/test/resources/data/order/place_by_rank/given.txt) は、会員ランクだけが違う3つのケースを1つのファイルに書いています（[3.4](#34-パターン番号) の例）。

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

3行とも顧客ID `C001` ですが、1回に投入するのは1つのパターンなので、主キーは重複しません。テストで変えたい値だけをパターンごとに書き、変わらないデータ（`product`）は共通にします。

### 4.2 検証

#### 単一テーブル

[restock/expected.txt](../src/test/resources/data/order/restock/expected.txt)：入荷した商品 `P002` の在庫が増え、他の商品 `P001` は変わっていないことを確かめます。

```
[product]
{product_code|stock}
P001|100
P002|80
```

#### 複数テーブル

注文すると、受注・明細・在庫の3つのテーブルが変わります。

```java
@Test
void 受注と明細が登録され在庫が減る() throws SQLException {
    // Given
    jigu.importFrom(DATA + "place/given.txt");

    // When
    service.placeOrder("A001", "C001", List.of(new OrderLine("P001", 3), new OrderLine("P002", 2)));

    // Then
    jigu.assertExists(DATA + "place/expected.txt");
    jigu.assertNotExists(DATA + "place/not_exists.txt");
}
```

[place/expected.txt](../src/test/resources/data/order/place/expected.txt) は [3.3](#33-投入と検証のファイルの例) のファイルです。

[place/not_exists.txt](../src/test/resources/data/order/place/not_exists.txt) は、余計な行が登録されていないことを確かめます（[5.2](#52-余分な行は検出しない)）。

```
[orders]
{order_no!=}
A001

[order_item]
{line_no>}
2
```

- `orders` に、`A001` 以外の受注が無い。
- `order_item` に、3行目以降の明細が無い。

#### パターン番号（`@ParameterizedTest` + `@CsvSource`）

パターン番号をテストの引数にすると、1つのテストメソッドで複数のケースを確かめられます。

```java
@ParameterizedTest(name = "パターン{0}: {1}", quoteTextArguments = false)
@CsvSource(delimiter = '|', textBlock = """
        1 | GOLD は10%引き
        2 | SILVER は5%引き
        3 | REGULAR は割引なし
        """)
void 会員ランクに応じて割り引く(int pattern, String description) throws SQLException {
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

- 投入と検証で**同じパターン番号**を渡します。
- 在庫（`P002` が 40）はどのパターンでも同じなので、パターン番号を空にした共通の行にしています。
- `description` はテスト名に表示するためだけの引数です。テストの実行結果に「パターン1: GOLD は10%引き」のように表示され、どのケースが失敗したか分かりやすくなります。

#### パターン番号に名前を使う

パターン番号には、数値の代わりに名前を書けます。ファイルを見ただけでどのケースの行か分かり、テスト名のための引数（`description`）も要らなくなります。上と同じテストを、名前で書いた例です。

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

- 名前は文字列で渡します。`jigu.importFrom(file, "gold", "silver")` のように、複数も渡せます。
- テストの実行結果には、渡した名前（`gold` など）がそのまま表示されます。
- 名前の書き間違い（`"glod"` など）は、どの行にも無いパターンとしてエラーになります。

#### 列名の記号を使う

値が実行のたびに変わる列や、範囲で確かめたい列には、列名の記号（[3.2](#32-列名の記号検証のとき)）を使います。

**`@`：比較しない**（[place/expected.txt](../src/test/resources/data/order/place/expected.txt)）

```
[orders]
{order_no|customer_id|status|total_amount|note|shipped_at|ordered_at@|updated_at@}
A001|C001|RECEIVED|960|<null>|<null>|-|-
```

注文日時と更新日時は実行した時刻になるので、比較しません。列ごと書かなくても結果は同じですが、`@` を付けて書いておくと「値は入るが比較しない」ことが読み手に伝わります。

**`>` と `%`：更新日時が新しくなり、備考が決まった文言で始まる**（[cancel/expected.txt](../src/test/resources/data/order/cancel/expected.txt)）

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

- `updated_at>`：更新日時が、投入した値（`2024-04-01 10:00`）より後になっている。
- `note%`：備考が「キャンセル: お客様都合」で始まっている。実際の値は「キャンセル: お客様都合（2026-10-09 12:34）」のように後ろに日時が付くので、前方一致で確かめます。

キャンセルした注文の明細が削除されたことは、[cancel/not_exists.txt](../src/test/resources/data/order/cancel/not_exists.txt) を `assertNotExists` で確かめます。

```
[order_item]
{order_no}
A001
```

**`>=` `<` `!=`：範囲と「値が入っている」**（[ship/expected.txt](../src/test/resources/data/order/ship/expected.txt)）

出荷のテストでは、次のデータ（[ship/given.txt](../src/test/resources/data/order/ship/given.txt)）を投入し、締め日時を `2024-04-10 00:00` にして出荷します。

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

`note` 列は書いていないので、NULL（DB の既定値）で投入されます。出荷後に期待する状態は次のとおりです。

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

- 1つ目：4月9日（`2024-04-09` 以上 `2024-04-10` 未満）に受け付けた注文が出荷済みになり、出荷日時が入っている（`!=` と `<null>` で `IS NOT NULL`）。
- 2つ目：締め日時ちょうどに受け付けた `A002`、キャンセル済みの `A003`、出荷済みの `A004` は変わっていない。
- 同じテーブルを2回書いて、条件の違う確認を分けています。

[ship/not_exists.txt](../src/test/resources/data/order/ship/not_exists.txt) は、締め日時より前に受け付けた注文が受付中のまま残っていないことを確かめます。

```
[orders]
{status|ordered_at<}
RECEIVED|2024-04-10
```

#### 何も変わっていないことを検証する

エラーになる操作では、Given のファイルをそのまま Then に使うと、DB が変わっていないことを確かめられます。

```java
@Test
void 在庫が足りなければ何も登録しない() {
    // Given
    jigu.importFrom(DATA + "place/given.txt");

    // When
    assertThrows(IllegalStateException.class,
            () -> service.placeOrder("A001", "C001", List.of(new OrderLine("P002", 51))));

    // Then: 投入したデータのまま変わっておらず、受注と明細は1件も無い
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

`!=` と `<null>` の組み合わせは `IS NOT NULL` なので、主キーなど NULL にならない列に使うと「どれか1行でもある」という条件になります。これを `assertNotExists` で使うと、**テーブルが空である**ことを確かめられます。

---

## 5. 検証の仕組みと注意点

### 5.1 1行ずつ「ある・ない」を確かめる

`assertExists` は、ファイルの1行ごとに次のような SQL を実行して、一致する行が DB に**あるか**を確かめます。

```sql
SELECT 1 FROM orders WHERE order_no = ? AND status = ? AND note LIKE ? ESCAPE '!' AND updated_at > ?
```

### 5.2 余分な行は検出しない

この仕組みのため、`assertExists` は次のことを確かめません。

- DB に、ファイルに書いていない**余分な行**が無いこと
- テーブルの**件数**
- ファイルの2行が、DB の**別々の行**に一致していること（同じ1行に一致しても成功します）

「余計な行ができていないこと」も大事なテストでは、`assertNotExists` を組み合わせます。

| 確かめたいこと | ファイル | メソッド |
|---|---|---|
| `A001` 以外の受注が無い | `{order_no!=}` / `A001` | `assertNotExists` |
| 3行目以降の明細が無い | `{line_no>}` / `2` | `assertNotExists` |
| 受注が1件も無い | `{order_no!=}` / `<null>` | `assertNotExists` |

件数そのものを確かめたいときは、`OrderService.shipOrderedBefore` の戻り値や、Spring Data の `count()` などを `assertEquals` で確かめてください。

### 5.3 失敗したときの出力

検証では、ファイルの行ごとに結果をコンソールに出力します（`○` は期待どおり、`×` は期待と違う行）。

```
○: src/test/resources/data/order/cancel/expected.txt:3 [orders] A001|CANCELED|キャンセル: お客様都合|2024-04-01 10:00
○: src/test/resources/data/order/cancel/expected.txt:7 [order_item] A002|1
×: src/test/resources/data/order/cancel/expected.txt:11 [product] P001|98
○: src/test/resources/data/order/cancel/expected.txt:12 [product] P002|50
```

`assertExists` が失敗すると、`×` の行が `AssertionError` のメッセージに入ります。`ファイル:行番号` から、どの行が一致しなかったかが分かります。

行は、値の前後の空白を除いて `|` でつなぎ直したものを表示します。`|` の位置を空白で揃えたファイルでも、`A001|C001|RECEIVED` のように表示されます。

```
java.lang.AssertionError: DBに存在しない行があります: src/test/resources/data/order/cancel/expected.txt
src/test/resources/data/order/cancel/expected.txt:11 [product] P001|98
```

---

## 6. Tips

### ファイルの置き場所と名前

テスト対象の業務ごとにディレクトリを作り、役割の分かる名前にすると迷いません。

```
src/test/resources/data/order/
├── cancel/
│   ├── given.txt        … Given：投入するデータ
│   ├── expected.txt     … Then：あるべき行（assertExists）
│   └── not_exists.txt   … Then：あってはいけない行（assertNotExists）
└── place/
    └── ...
```

ファイルのパスは、テストを実行するときの作業ディレクトリ（Gradle や Maven ではプロジェクトのルート）からの相対パスです。

### 投入順と外部キー

テーブルはファイルに書いた順に投入します。親テーブルを先に書いてください。

### 自動採番の列

PostgreSQL の `IDENTITY` や `serial` の列に値を指定して投入しても、シーケンスは進みません。その後にテスト対象が採番すると、投入した値と重複することがあります。採番する列はフィクスチャに書かずに DB に採番させるか、テスト対象が採番しない大きな値を使ってください。

### 実行時刻が入る列

- 比較しなくてよい → `@`
- 「更新された」ことを確かめたい → 投入した値と `>` で比較する

### コメント

`//` で始まる行は、投入でも検証でも無視します。行の先頭の空白は無視するので、字下げしてもかまいません。

- テーブルの日本語名や、データの意図を書いておく
- 一時的に、ある行を投入や検証の対象から外す（`// A001|1|P001|3|360`）

行の途中からはコメントにできません。`A001|https://example.com` の `//` は値の一部です。なお、検証する行をすべてコメントにするとエラーになるので、何も検証せずに成功することはありません。

### フィクスチャエディタ

[tools/fixture-editor.html](../tools/fixture-editor.html) をブラウザで開くと、フィクスチャを表の形で編集できます。

- 列数の不一致やパターン番号の書き間違いなど、DbJigu が例外にする誤りを、テストを実行する前に見つけられます。
- 「桁揃え」で保存すると `|` の位置が縦に揃います。値の前後の空白は取り除かれるので、意味は変わりません。検証の結果にも、揃えた空白を除いた行が表示されます（[5.3](#53-失敗したときの出力)）。
- パターン番号で絞り込むと、そのパターンで投入・検証される行（そのパターンの行と共通の行）だけが濃く表示されます。
- Excel で作ったデータは、範囲をコピーしてセルに貼り付けられます。

### 空文字と NULL

`<null>` は NULL、何も書かない（`A001||C001` の真ん中）と空文字です。行の末尾の空の項目も空文字になります。なお、Oracle は空文字を NULL として扱います（この挙動は、このリポジトリのテストでは確認していません）。

### CHAR 型の列

CHAR 型の列は、末尾の空白を除いて比較します。`abc` と書けば、`CHAR(10)` に入った `abc       ` と一致します。

### ロールバックされないケース

DbJigu は投入したデータを削除しません。テストのトランザクションのロールバックで元に戻します。次のように、テスト対象がテストのトランザクションの外で動くと、データが残ったり、投入したデータが見えなかったりします。

- テスト対象が `@Transactional(propagation = REQUIRES_NEW)` を使っている
- テスト対象が別スレッド（非同期処理）で DB を更新する
- `@SpringBootTest(webEnvironment = RANDOM_PORT)` で HTTP 経由でテスト対象を呼ぶ

このような場合は、テストの後に自分でデータを削除する必要があります。

---

## 7. 制限事項とトラブルシューティング

### 7.1 書けない値

| 書けないもの | 理由 |
|---|---|
| `\|` を含む値 | 区切り文字のため（エスケープの方法はありません） |
| 前後に空白がある値 | 値の前後の空白は取り除くため |
| 文字列の `<null>` | NULL として扱うため |
| 行の先頭が `[` や `{` になる値 | テーブル名や列名の行として読むため（[#12](https://github.com/raindrop-aqua/java-test-toolbelt/issues/12) で検討中） |
| 行の先頭が `//` になる値 | コメントの行として読むため |
| 行の途中からのコメント | `//` は行の先頭だけをコメントとして扱います。`#` で始まる行はデータの行として読みます |

### 7.2 エラーメッセージと対処

エラーメッセージの先頭には `ファイル:行番号` が付きます。

| メッセージ | 原因と対処 |
|---|---|
| `列数(n)と値の数(m)が一致しません` | 列名と値の数が違います。`\|` の数を確かめてください。パターン番号のあるテーブルで、行の先頭の `\|` を忘れていないかも確かめてください。 |
| `パターン番号 [n] の行がありません` | 渡したパターン番号の行が、ファイルのどこにもありません。 |
| `検証対象の行がありません` | 検証する行が1行もありません。列名の行だけで、データの行が無いファイルです。 |
| `値を列の型に変換できません` | 列の型に合わない値です。日付や数値の書き方を確かめてください（[3.1](#31-基本形)）。 |
| `列の型を取得できません` | テーブル名か列名が間違っています。 |
| `投入に失敗しました` | 主キーの重複、外部キー違反、NOT NULL 違反などです。原因の SQL エラーが例外に含まれます。 |
| `[テーブル名] の後に {列名1\|列名2} を記述してください` | テーブル名の次に列名の行がありません。 |
| `データの前に {列名1\|列名2} を記述してください` | 列名の行より前にデータがあります。 |
| `投入では同じ列を2回書けません` | 範囲指定のために同じ列を2回書いたファイルを、投入に使っています。 |
| `% は文字列の列だけに使えます` | 数値や日付の列に `%` を付けています。 |
| `列名の末尾の記号は1つだけ付けられます` | `amount@<` のように記号を2つ付けています。 |
| `LOB 列は大小比較できません` | CLOB などの LOB 列に `<` `<=` `>` `>=` を付けています。 |
| `空のパターン番号があります` | パターン番号の項目に、空の番号（`1,,2` や `gold,` など）があります。 |
| `パターン番号に使えない値です` | メソッドに渡したパターン番号（名前）が、空や `null` か、`,` や `\|` を含んでいます。 |
| `テーブル名は [テーブル名] の形式で記述してください` | `[` で始まる行が `]` で終わっていないか、テーブル名が空（`[]` や `[ ]`）です。 |
| `列名は {列名1\|列名2} の形式で記述してください` | `{` で始まる行が `}` で終わっていません。 |
| `空の列名があります` / `列名がありません` | 列名の行に、空の項目（`{a\|\|b}` など）があるか、列名が1つもありません。 |
| `検証に失敗しました` | 検証の SQL がエラーになりました。比較できない型の列に `>` などを付けていないか確かめてください。原因の SQL エラーが例外に含まれます。 |
| `ファイルを読み込めません` | ファイルのパスが間違っています。パスはプロジェクトのルートからの相対パスです。 |

### 7.3 一致するはずなのに失敗する

| 確かめること | 対処 |
|---|---|
| JPA の変更を flush したか | 検証の前に `entityManager.flush()` を呼ぶ |
| 同じ Connection を使っているか | Spring では `DataSourceUtils.getConnection(dataSource)` を使う |
| 日時の秒やミリ秒まで一致しているか | 必要な精度まで書くか、`>=` と `<` の範囲で比較する |
| 実行時刻が入る列を比較していないか | `@` を付ける |
| パターン番号を投入と検証で揃えたか | 同じ番号を渡す |
