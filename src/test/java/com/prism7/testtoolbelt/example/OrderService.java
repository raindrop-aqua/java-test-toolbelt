package com.prism7.testtoolbelt.example;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * ガイド（docs/guide.ja.md）の例で使うテスト対象。受注管理の業務を JDBC だけで実装した簡単なサービス。
 * テーブル定義は schema/order.sql。
 */
public class OrderService {

    private static final DateTimeFormatter NOTE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Connection connection;

    public OrderService(Connection connection) {
        this.connection = connection;
    }

    /** 注文する商品と数量 */
    public record OrderLine(String productCode, int quantity) {
    }

    /**
     * 入荷する。商品の在庫を増やす
     */
    public void restock(String productCode, int quantity) throws SQLException {
        update("UPDATE product SET stock = stock + ? WHERE product_code = ?", quantity, productCode);
    }

    /**
     * 注文する。受注と明細を登録し、在庫を減らす。
     * 合計金額は会員ランクで割り引く（GOLD は10%、SILVER は5%、1円未満は切り捨て）。
     * 在庫が足りない商品があれば、何も登録せずに例外を投げる
     */
    public void placeOrder(String orderNo, String customerId, List<OrderLine> lines) throws SQLException {
        List<BigDecimal> amounts = new ArrayList<>();
        for (OrderLine line : lines) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT unit_price, stock FROM product WHERE product_code = ?")) {
                ps.setString(1, line.productCode());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || rs.getInt("stock") < line.quantity()) {
                        throw new IllegalStateException("在庫が足りません: " + line.productCode());
                    }
                    amounts.add(rs.getBigDecimal("unit_price").multiply(BigDecimal.valueOf(line.quantity())));
                }
            }
        }
        BigDecimal total = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discounted = total.multiply(BigDecimal.valueOf(100 - discountRate(customerId)))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN);

        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        update("INSERT INTO orders (order_no, customer_id, status, total_amount, ordered_at, updated_at)"
                + " VALUES (?, ?, 'RECEIVED', ?, ?, ?)", orderNo, customerId, discounted, now, now);
        for (int i = 0; i < lines.size(); i++) {
            OrderLine line = lines.get(i);
            update("INSERT INTO order_item (order_no, line_no, product_code, quantity, amount) VALUES (?, ?, ?, ?, ?)",
                    orderNo, i + 1, line.productCode(), line.quantity(), amounts.get(i));
            update("UPDATE product SET stock = stock - ? WHERE product_code = ?", line.quantity(), line.productCode());
        }
    }

    /**
     * キャンセルする。受付中（RECEIVED）の注文だけキャンセルできる。
     * 明細を削除して在庫を戻し、備考に「キャンセル: 理由（日時）」を記録する
     */
    public void cancel(String orderNo, String reason) throws SQLException {
        if (!"RECEIVED".equals(status(orderNo))) {
            throw new IllegalStateException("受付中の注文だけキャンセルできます: " + orderNo);
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT product_code, quantity FROM order_item WHERE order_no = ?")) {
            ps.setString(1, orderNo);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    restock(rs.getString("product_code"), rs.getInt("quantity"));
                }
            }
        }
        update("DELETE FROM order_item WHERE order_no = ?", orderNo);

        LocalDateTime now = LocalDateTime.now();
        update("UPDATE orders SET status = 'CANCELED', note = ?, updated_at = ? WHERE order_no = ?",
                "キャンセル: " + reason + "（" + now.format(NOTE_TIME_FORMAT) + "）", Timestamp.valueOf(now), orderNo);
    }

    /**
     * 出荷する。締め日時より前に受け付けた受付中の注文を、まとめて出荷済み（SHIPPED）にする
     *
     * @return 出荷した件数
     */
    public int shipOrderedBefore(LocalDateTime cutoff) throws SQLException {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        return update("UPDATE orders SET status = 'SHIPPED', shipped_at = ?, updated_at = ?"
                + " WHERE status = 'RECEIVED' AND ordered_at < ?", now, now, Timestamp.valueOf(cutoff));
    }

    private int discountRate(String customerId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT customer_rank FROM customer WHERE customer_id = ?")) {
            ps.setString(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("顧客がいません: " + customerId);
                }
                return switch (rs.getString(1)) {
                    case "GOLD" -> 10;
                    case "SILVER" -> 5;
                    default -> 0;
                };
            }
        }
    }

    private String status(String orderNo) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT status FROM orders WHERE order_no = ?")) {
            ps.setString(1, orderNo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private int update(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
        }
    }
}
