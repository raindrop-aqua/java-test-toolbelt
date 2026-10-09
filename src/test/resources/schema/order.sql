-- ガイド（docs/guide.ja.md）の受注管理の例で使うテーブル

CREATE TABLE customer (
    customer_id   VARCHAR(10) PRIMARY KEY,
    customer_name VARCHAR(50) NOT NULL,
    customer_rank VARCHAR(10) NOT NULL
);

CREATE TABLE product (
    product_code VARCHAR(10) PRIMARY KEY,
    product_name VARCHAR(50) NOT NULL,
    unit_price   NUMERIC(10) NOT NULL,
    stock        INTEGER     NOT NULL
);

CREATE TABLE orders (
    order_no     VARCHAR(10)  PRIMARY KEY,
    customer_id  VARCHAR(10)  NOT NULL REFERENCES customer (customer_id),
    status       VARCHAR(10)  NOT NULL,
    total_amount NUMERIC(10)  NOT NULL,
    note         VARCHAR(100),
    ordered_at   TIMESTAMP    NOT NULL,
    shipped_at   TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL
);

CREATE TABLE order_item (
    order_no     VARCHAR(10) NOT NULL REFERENCES orders (order_no),
    line_no      INTEGER     NOT NULL,
    product_code VARCHAR(10) NOT NULL REFERENCES product (product_code),
    quantity     INTEGER     NOT NULL,
    amount       NUMERIC(10) NOT NULL,
    PRIMARY KEY (order_no, line_no)
);
