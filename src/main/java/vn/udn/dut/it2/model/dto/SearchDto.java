package vn.udn.dut.it2.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

public class SearchDto {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SearchItem {
        private Long id;
        private String documentId;
        private String documentNumber;
        private String title;
        private String category;
        private String field;
        private String issuer;
        private String status;
        private LocalDate issuedDate;
        private LocalDate effectiveDate;
        private String pdfUrl;
        private String summary;
        private List<String> tags;
        private Double semanticScore;
        private String matchedClause;
        private String matchedExcerpt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Response {
        private String query;
        private String category;
        private List<String> tags;
        private int total;
        private int page;
        private int size;
        private int totalItems;
        private int totalPages;
        private List<String> availableTags;
        private boolean semanticUsed;
        private boolean guardrailBlocked;
        private String guardrailMessage;
        private List<SearchItem> items;
    }
}
