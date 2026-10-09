package com.prism7.testtoolbelt.spring;

import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Integer> {

    Optional<Member> findByMemberId(BigDecimal memberId);
}
