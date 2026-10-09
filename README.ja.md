# DbJigu（DB治具）

[English](README.md) | 日本語

テキストファイル（フィクスチャ）を使って、テストデータのDB投入とDB内容の検証を行うテスト用ツールです。

JAR では配布しません。[`DbJigu.java`](src/test/java/com/prism7/testtoolbelt/DbJigu.java) を利用するプロジェクトのテストソース（例：`src/test/java/<パッケージ>/utils`）にコピーし、パッケージ名を変更して使います。必要なのは Java 17 以上と JDBC ドライバだけです。

## 使い方

```java
DbJigu jigu = new DbJigu(connection);   // Spring のテストでは DataSourceUtils.getConnection(dataSource)
jigu.importFrom("src/test/resources/data/setup.txt", 1);       // パターン1を投入
// ... テスト対象の処理を実行（JPA の場合は先に entityManager.flush() を呼ぶ）...
jigu.assertExists("src/test/resources/data/expected.txt", 1);  // 一致しなければ AssertionError
jigu.assertNotExists("src/test/resources/data/deleted.txt");
int mismatches = jigu.verifyExists("src/test/resources/data/expected.txt"); // 例外の代わりに件数を返す
```

DbJigu は、渡された Connection のコミット、ロールバック、クローズを行いません。

## フィクスチャの書式

```
[Member]
{#|MemberId|MemberName|UpdatedAt@}
|0|common|<null>
1|1|john|2024-04-01 12:34:56
1,2|2|sam|2024/04/02
```

| 要素 | 意味 |
|---|---|
| `[テーブル名]` | テーブルの区切り。1ファイルに複数のテーブルを書けます。 |
| `{列名1\|列名2}` | 列名。末尾に `@` を付けた列は、投入はしますが比較はしません。 |
| 先頭の列が `#` | 各行の先頭の項目が、その行のパターン番号（`1,2`）になります。空にすると全パターン共通の行になります。 |
| `<null>` | NULL |

- 文字コードは UTF-8、区切り文字は `|` です。各項目の前後の空白は取り除きます。
- 値は列の型に合わせて変換します。日付は `2024-04-01` または `2024/04/01`、日時は `2024-04-01 12:34:56[.fff]` の形式で書きます。
- パターン番号を指定すると、そのパターンの行と共通の行だけを処理します。指定しなければ全行を処理します。どの行にもないパターン番号を指定するとエラーになります。
- LOB 列（Oracle の CLOB など）は、SQL ではなく Java 側で比較します。

## 開発

テストは PostgreSQL で実行します。DB の準備は [CLAUDE.md](CLAUDE.md) を参照してください。

```bash
./gradlew build
```
