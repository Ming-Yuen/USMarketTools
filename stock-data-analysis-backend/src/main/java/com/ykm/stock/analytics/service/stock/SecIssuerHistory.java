package com.ykm.stock.analytics.service.stock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 已核對的 SEC 接續生效日。證券 ticker 或公司名相同不足以建立接續。 */
@Component
public class SecIssuerHistory {
    public record Transition(String current, String previous, LocalDate effectiveFrom, String accession) {}
    private final List<Transition> transitions;

    public SecIssuerHistory(@Value("${sec.issuer-transitions:}") String entries) {
        transitions = entries.isBlank() ? List.of() : java.util.Arrays.stream(entries.split(",")).map(entry -> {
            String[] fields = entry.strip().split(":");
            if (fields.length != 4 || !fields[0].matches("[0-9]{10}") || !fields[1].matches("[0-9]{10}")
                    || fields[0].equals(fields[1]) || !fields[3].matches("[0-9]{10}-[0-9]{2}-[0-9]{6}"))
                throw new IllegalArgumentException("SEC_ISSUER_TRANSITION_INVALID:" + entry);
            return new Transition(fields[0], fields[1], LocalDate.parse(fields[2]), fields[3]);
        }).toList();
    }

    public String cikAt(String current, LocalDate day) {
        Set<String> seen = new LinkedHashSet<>();
        String cik = current;
        while (cik != null && seen.add(cik)) {
            Transition transition = transition(cik);
            if (transition == null || !day.isBefore(transition.effectiveFrom())) return cik;
            cik = transition.previous();
        }
        if (cik == null) return null;
        throw new IllegalStateException("SEC_ISSUER_TRANSITION_CYCLE:" + current);
    }

    public Set<String> predecessors(String current) {
        Set<String> previous = new LinkedHashSet<>();
        for (Transition next = transition(current); next != null; next = transition(next.previous())) {
            if (current.equals(next.previous()) || !previous.add(next.previous()))
                throw new IllegalStateException("SEC_ISSUER_TRANSITION_CYCLE:" + current);
        }
        return previous;
    }

    /** 將任意前任或現任 CIK 對稱歸一化至已登錄接續鏈的最新現任根 CIK；無接續紀錄時回傳原值。 */
    public String canonicalCik(String cik) {
        if (cik == null || cik.isBlank()) return null;
        String current = cik.trim();
        Set<String> seen = new LinkedHashSet<>();
        while (seen.add(current)) {
            Transition successor = successor(current);
            if (successor == null) return current;
            current = successor.current();
        }
        throw new IllegalStateException("SEC_ISSUER_TRANSITION_CYCLE:" + cik);
    }

    private Transition transition(String current) {
        return transitions.stream().filter(entry -> entry.current().equals(current)).findFirst().orElse(null);
    }

    private Transition successor(String previous) {
        return transitions.stream().filter(entry -> entry.previous().equals(previous)).findFirst().orElse(null);
    }
}
