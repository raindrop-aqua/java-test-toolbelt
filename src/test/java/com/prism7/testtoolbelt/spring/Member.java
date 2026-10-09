package com.prism7.testtoolbelt.spring;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Spring での利用例で使うエンティティ（テーブル定義は schema/postgresql.sql）。
 * PostgreSQL は引用符の無い名前を小文字で保持するため、テーブル名と列名は小文字で指定する
 */
@Entity
@Table(name = "member")
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "memberid")
    private BigDecimal memberId;

    @Column(name = "membername")
    private String memberName;

    protected Member() {
    }

    public Member(BigDecimal memberId, String memberName) {
        this.memberId = memberId;
        this.memberName = memberName;
    }

    public BigDecimal getMemberId() {
        return memberId;
    }

    public String getMemberName() {
        return memberName;
    }

    public void setMemberName(String memberName) {
        this.memberName = memberName;
    }
}
