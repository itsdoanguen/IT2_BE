package vn.udn.dut.it2.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.udn.dut.it2.config.AiProperties;
import vn.udn.dut.it2.model.dto.SearchDto;
import vn.udn.dut.it2.model.entity.Regulation;
import vn.udn.dut.it2.repository.RegulationRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private final RegulationRepository regulationRepository;
    private final AiProperties aiProperties;
    private final RestClient aiRestClient;

    public SearchDto.Response search(
            String query,
            String category,
            List<String> tags,
            int limit,
            boolean useSemantic) {

        String normalizedQuery = query == null ? "" : query.trim();
        String normalizedCategory = (category == null || category.isBlank()) ? "ALL" : category.trim();
        List<String> normalizedTags = normalizeTags(tags);
        int boundedLimit = Math.max(1, Math.min(limit, 100));

        List<Regulation> candidates = regulationRepository.searchRegulations(normalizedCategory, null);
        if (!normalizedTags.isEmpty()) {
            candidates = candidates.stream()
                    .filter(reg -> hasAllTags(reg, normalizedTags))
                    .toList();
        }

        if (normalizedQuery.isBlank()) {
            List<SearchDto.SearchItem> items = candidates.stream()
                    .limit(boundedLimit)
                    .map(reg -> toSearchItem(reg, null))
                    .toList();
            return SearchDto.Response.builder()
                    .query("")
                    .category(normalizedCategory)
                    .tags(normalizedTags)
                    .total(items.size())
                    .semanticUsed(false)
                    .guardrailBlocked(false)
                    .items(items)
                    .build();
        }

        SemanticLookup semanticLookup = useSemantic
                ? querySemanticService(normalizedQuery)
                : SemanticLookup.empty(false);

        if (semanticLookup.blocked) {
            return SearchDto.Response.builder()
                    .query(normalizedQuery)
                    .category(normalizedCategory)
                    .tags(normalizedTags)
                    .total(0)
                    .semanticUsed(true)
                    .guardrailBlocked(true)
                    .guardrailMessage(semanticLookup.refusalMessage)
                    .items(List.of())
                    .build();
        }

        String lowerQuery = normalizedQuery.toLowerCase(Locale.ROOT);
        List<ScoredItem> scored = new ArrayList<>();
        for (Regulation reg : candidates) {
            double kwScore = keywordScore(reg, lowerQuery);
            SemanticHit hit = findBestHit(reg, semanticLookup);
            double semScore = hit != null ? hit.score : 0.0;
            if (kwScore > 0.0 || semScore > 0.0) {
                double combined = semScore * 2.0 + kwScore;
                scored.add(new ScoredItem(toSearchItem(reg, hit), combined));
            }
        }

        scored.sort(Comparator.comparingDouble(ScoredItem::combinedScore).reversed());
        List<SearchDto.SearchItem> items = scored.stream()
                .limit(boundedLimit)
                .map(ScoredItem::item)
                .toList();

        log.info("[SEARCH] query='{}' category={} tags={} -> total={}, semanticUsed={}, blocked={}",
                normalizedQuery, normalizedCategory, normalizedTags, items.size(), semanticLookup.used, false);

        return SearchDto.Response.builder()
                .query(normalizedQuery)
                .category(normalizedCategory)
                .tags(normalizedTags)
                .total(items.size())
                .semanticUsed(semanticLookup.used)
                .guardrailBlocked(false)
                .items(items)
                .build();
    }

    private SemanticLookup querySemanticService(String query) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("top_k", 20);
        payload.put("min_score", 0.15);
        payload.put("check_guardrail", true);

        try {
            JsonNode response = aiRestClient.post()
                    .uri(aiProperties.getRetrievalBaseUrl() + aiProperties.getRetrievalPath())
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null || response.isNull()) {
                return SemanticLookup.empty(false);
            }
            if (response.path("blocked").asBoolean(false)) {
                return new SemanticLookup(
                        true,
                        true,
                        response.path("refusal_message").asText("Câu hỏi nằm ngoài phạm vi tra cứu học vụ DUT."),
                        Map.of(),
                        Map.of());
            }

            Map<String, SemanticHit> byDocId = new HashMap<>();
            Map<String, SemanticHit> byDocNumber = new HashMap<>();
            for (JsonNode item : response.path("results")) {
                double score = item.path("score").asDouble(0.0);
                String content = item.path("content").asText("");
                JsonNode meta = item.path("metadata");
                String docId = meta.path("document_id").asText("").trim();
                String docNum = meta.path("document_number").asText("").trim();
                String clause = formatClause(meta);
                SemanticHit hit = new SemanticHit(score, clause, content);

                if (!docId.isEmpty()) {
                    byDocId.merge(docId, hit, (a, b) -> a.score >= b.score ? a : b);
                }
                if (!docNum.isEmpty()) {
                    byDocNumber.merge(docNum.toLowerCase(Locale.ROOT), hit, (a, b) -> a.score >= b.score ? a : b);
                }
            }
            return new SemanticLookup(true, false, null, byDocId, byDocNumber);
        } catch (RestClientException ex) {
            log.warn("Semantic search service unavailable at {}{}; falling back to PostgreSQL keyword search: {}",
                    aiProperties.getRetrievalBaseUrl(), aiProperties.getRetrievalPath(), ex.getMessage());
            return SemanticLookup.empty(false);
        }
    }

    private static String formatClause(JsonNode meta) {
        String article = meta.path("article_number").asText("").trim();
        String clause = meta.path("clause_number").asText("").trim();
        if (article.isEmpty()) {
            return "";
        }
        if (clause.isEmpty() || "all".equalsIgnoreCase(clause)) {
            return article;
        }
        return article + ", Khoản " + clause;
    }

    private static SemanticHit findBestHit(Regulation reg, SemanticLookup lookup) {
        if (reg.getDocumentId() != null && !reg.getDocumentId().isBlank()) {
            SemanticHit byId = lookup.byDocId.get(reg.getDocumentId().trim());
            if (byId != null) {
                return byId;
            }
        }
        if (reg.getDocumentNumber() != null && !reg.getDocumentNumber().isBlank()) {
            return lookup.byDocNumber.get(reg.getDocumentNumber().trim().toLowerCase(Locale.ROOT));
        }
        return null;
    }

    private static double keywordScore(Regulation reg, String lowerQuery) {
        double score = 0.0;
        if (containsIgnoreCase(reg.getDocumentNumber(), lowerQuery)) {
            score += 1.2;
        }
        if (containsIgnoreCase(reg.getTitle(), lowerQuery)) {
            score += 1.0;
        }
        if (containsIgnoreCase(reg.getSummary(), lowerQuery)) {
            score += 0.5;
        }
        if (containsIgnoreCase(reg.getField(), lowerQuery)) {
            score += 0.4;
        }
        if (reg.getTags() != null && reg.getTags().stream().anyMatch(t -> containsIgnoreCase(t, lowerQuery))) {
            score += 0.4;
        }
        return score;
    }

    private static boolean containsIgnoreCase(String text, String lowerNeedle) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }

    private static boolean hasAllTags(Regulation reg, List<String> requiredTags) {
        List<String> regTags = reg.getTags();
        if (regTags == null || regTags.isEmpty()) {
            return false;
        }
        for (String req : requiredTags) {
            boolean matched = regTags.stream().anyMatch(t -> t != null && t.equalsIgnoreCase(req));
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private static List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        return tags.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static SearchDto.SearchItem toSearchItem(Regulation reg, SemanticHit hit) {
        return SearchDto.SearchItem.builder()
                .id(reg.getId())
                .documentId(reg.getDocumentId())
                .documentNumber(reg.getDocumentNumber())
                .title(reg.getTitle())
                .category(reg.getCategory())
                .field(reg.getField())
                .issuer(reg.getIssuer())
                .status(reg.getStatus())
                .issuedDate(reg.getIssuedDate())
                .effectiveDate(reg.getEffectiveDate())
                .pdfUrl(reg.getPdfUrl())
                .summary(reg.getSummary())
                .tags(reg.getTags() != null ? reg.getTags() : List.of())
                .semanticScore(hit != null ? hit.score : null)
                .matchedClause(hit != null ? hit.clause : null)
                .matchedExcerpt(hit != null ? hit.excerpt : null)
                .build();
    }

    private record SemanticHit(double score, String clause, String excerpt) {}

    private record ScoredItem(SearchDto.SearchItem item, double combinedScore) {}

    private record SemanticLookup(
            boolean used,
            boolean blocked,
            String refusalMessage,
            Map<String, SemanticHit> byDocId,
            Map<String, SemanticHit> byDocNumber) {
        static SemanticLookup empty(boolean used) {
            return new SemanticLookup(used, false, null, Map.of(), Map.of());
        }
    }
}
